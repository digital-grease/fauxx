#!/usr/bin/env python3
"""Build app/src/main/assets/location_anchors.json: where Location Spoofing may put the decoy (#322 item 12).

A spoofed location must never place the decoy at a sensitive place. A broker that records the
visit attaches the inference (a clinic, a church, a shelter) to the real user's device. The FTC's
orders against the location data brokers Gravy Analytics and Venntel (January 2025), Mobilewalla
(January 2025) and Kochava (May 2026) list the places whose visits brokers may no longer sell.
Fauxx keeps away from all of them. Per the owner's ruling it also keeps away from the sensitive
purchases the crawl list avoids (#322 item 7). CATEGORIES lists both.

For each city in city_coords.json, this script:

  1. Reads the sensitive places within reach from Overture Maps (places, land use and buildings)
     and from OpenStreetMap's points of interest (OpenStreetMap US's Layercake extract). The two
     complement each other: Overture's places are mostly business listings (clinics, offices,
     shops), OpenStreetMap is stronger on institutions (places of worship, schools, social
     facilities), and in some countries one of them is nearly empty.
  2. Samples a point every 20 m along the public main roads and pedestrian streets within 7.5 km
     of the city. Residential streets are left out, so a spoof never rests outside someone's
     home, and so are bridges and tunnels.
  3. Measures each point's clearance: its distance to the nearest sensitive place, up to 700 m.
     An area such as a hospital's grounds or a military site counts by its bounding box, which is
     never closer than the area itself.
  4. Keeps up to 12 points at least 180 m clear: the app's 150 m buffer plus room to stand or
     stroll. They are spread at least 400 m apart and drawn with a lean toward the centre.

The app starts a spoofed route at one of these points and keeps every fix within the point's
clearance minus 150 m of it. A distance to a set of places changes by no more than the distance
moved, so no fix comes within 150 m of a sensitive place.

Cities whose map data is too thin to trust are left out (the owner's ruling). Overture lists 287
places around Medina against 110,000 around New York, so Medina would look clear only because its
sensitive places are missing. LEFT_OUT_* below sets the test, and the asset records why each city
was left out.

Mobilewalla's order also lists the sites of political demonstrations while they take place. A
fixed list cannot follow those.

This runs on a developer machine and takes a few hours for the 806 cities. The app reads only the
asset and never contacts Overture or OpenStreetMap.

Data:
  Overture Maps Foundation, release OVERTURE_RELEASE: places (CDLA Permissive 2.0); land use,
    buildings and roads (derived from OpenStreetMap, ODbL). https://overturemaps.org
  OpenStreetMap US Layercake, points of interest (OpenStreetMap contributors, ODbL).
    https://layercake.openstreetmap.us

Requires duckdb and shapely. Each city is cached, so an interrupted run resumes where it stopped.

Usage:
  uv run --with duckdb --with shapely python3 scripts/build_location_anchors.py --cache DIR
      [--workers 6] [--only "Rome, Italy"] [--cities app/src/main/assets/city_coords.json]
      [--out app/src/main/assets/location_anchors.json]
"""

from __future__ import annotations

import argparse
import concurrent.futures
import gzip
import hashlib
import json
import math
import os
import re
import sys
import threading

import duckdb
import numpy as np
import shapely

OVERTURE_RELEASE = "2026-11-18.0"
OVERTURE = f"s3://overturemaps-us-west-2/release/{OVERTURE_RELEASE}"
LAYERCAKE_POIS = "https://data.openstreetmap.us/layercake/pois.parquet"

BUFFER_M = 150          # no spoofed fix comes closer than this to a sensitive place
MIN_CLEARANCE_M = 180   # an anchor leaves at least 30 m to stand or stroll in
MAX_CLEARANCE_M = 700   # clearances are measured up to this
RADIUS_M = 7500         # anchors lie within this distance of the city's point
CORE_M = 2000           # the data-coverage test looks at this distance of the city's point
SPACING_M = 400         # anchors of a city are at least this far apart
ANCHORS_PER_CITY = 12
SAMPLE_STEP_M = 20
CENTRE_LEAN_M = 2500    # an anchor this far out is drawn half as often as one at the centre

# Public main roads and pedestrian streets. Not residential streets, service roads, motorways,
# footpaths or tracks.
ROAD_CLASSES = ("primary", "secondary", "tertiary", "unclassified", "pedestrian")

# A city is left out when, within CORE_M of its point, either source lists fewer places of any
# kind, or the two together list fewer sensitive places, than these. The sources cover different
# places (OpenStreetMap: places of worship, schools, social facilities; Overture: clinics, offices,
# shops), so a city thin in either is probably missing a whole kind of sensitive place. Across the
# 806 cities of November 2026 the medians are 3,590 Overture places, 1,727 OpenStreetMap places
# and 778 sensitive places; these cut-offs sit near the tenth percentile.
LEFT_OUT_MIN_PLACES = 300
LEFT_OUT_MIN_SENSITIVE = 100

CATEGORIES = {
    # The FTC orders' sensitive locations (union of the three orders).
    "medical": "Hospitals, clinics, doctors' and dentists' offices, mental health and addiction treatment, care homes",
    "religious": "Places of worship and religious organisations",
    "correctional": "Prisons and jails",
    "union": "Labor union offices",
    "minors": "Schools, kindergartens and childcare",
    "lgbtq": "LGBTQ+ venues and organisations",
    "ethnic": "Cultural centres and associations serving an ethnic or national community",
    "shelter_social": "Shelters and social services, including for the homeless, survivors of domestic violence, refugees and immigrants",
    "military_police": "Military sites and police stations",
    # The sensitive purchases the crawl list also avoids (#322 item 7).
    "pharmacy": "Pharmacies and drugstores",
    "death": "Funeral homes, crematoriums and cemeteries",
    "alcohol": "Liquor stores",
    "gambling": "Casinos, betting shops and lottery sellers",
    "adult": "Adult venues and shops",
    "cannabis_tobacco": "Cannabis, tobacco and vape shops",
    "legal": "Law offices, bail bonds and courthouses",
    "lending": "Payday lenders, pawn shops and debt services",
    "political": "Political party offices",
    "guns": "Gun shops and shooting ranges",
    "baby": "Baby and maternity shops",
    "associations": "Charities, NGOs and veterans' organisations",
}
CLASS_IDS = {name: i for i, name in enumerate(CATEGORIES)}

# --- Overture places: taxonomy names ---

OVERTURE_NAMES = {
    "medical": {"senior_living_facility", "nursing_home", "assisted_living_facility", "retirement_home", "hospice"},
    "religious": {"religious_organization", "religious_landmark", "pilgrimage_site", "monastery", "historic_mission",
                  "convent", "religious_and_spiritual_goods_store", "church_supply_store"},
    "correctional": {"jail_or_prison", "prison", "detention_center", "correctional_facility"},
    "union": {"labor_union"},
    "minors": {"school", "preschool", "elementary_school", "middle_school", "high_school", "private_school",
               "public_school", "charter_school", "montessori_school", "religious_school", "after_school_program",
               "educational_camp", "child_care_and_day_care", "day_care_preschool", "nanny_service",
               "youth_organization", "scout_hall", "kindergarten", "childrens_hospital"},
    "lgbtq": {"gay_bar", "gay_and_lesbian_services_organization"},
    "ethnic": {"cultural_center"},
    "shelter_social": {"social_or_community_service", "social_and_human_service", "homeless_shelter", "food_bank",
                       "social_welfare_center", "department_of_social_service", "foster_care_service",
                       "disability_services_and_support_organization", "senior_citizen_service",
                       "crisis_intervention_service", "immigration_and_naturalization_office", "immigration_law",
                       "community_center", "housing_authority", "social_security_service", "women_shelter",
                       "refugee_center"},
    "military_police": {"military_site", "military_base", "police_station", "military_office", "law_enforcement"},
    "pharmacy": {"pharmacy", "drugstore", "pharmacy_and_drug_store"},
    "death": {"funeral_home", "funeral_service", "cremation_service", "cemetery", "memorial_park",
              "funeral_services_and_cemeteries", "mortuary"},
    "alcohol": {"liquor_store", "beer_wine_spirits_store"},
    "gambling": {"casino", "betting_center", "lottery_vendor", "bingo_hall", "gaming_venue"},
    "adult": {"adult_entertainment_venue", "strip_club", "erotic_massage_venue", "adult_store"},
    "cannabis_tobacco": {"tobacco_shop", "smoke_and_vape_store", "cannabis_dispensary", "hookah_bar", "cigar_bar",
                         "cannabis_clinic"},
    "legal": {"attorney_or_law_firm", "legal_service", "bail_bonds_service", "courthouse"},
    "lending": {"installment_loans", "check_cashing_payday_loans", "debt_relief_service",
                "credit_and_debt_counseling", "pawn_shop"},
    "political": {"political_party_office", "political_organization"},
    "guns": {"gun_and_ammo_store", "shooting_range"},
    "baby": {"baby_gear_and_nursery_store", "maternity_wear"},
    "associations": {"charity_organization", "non_governmental_association", "veterans_organization"},
}
OVERTURE_LOOKUP = {name: cls for cls, names in OVERTURE_NAMES.items() for name in names}


def classify_overture_place(basic: str | None, primary: str | None, top: str | None) -> str | None:
    for name in (primary, basic):
        if name in OVERTURE_LOOKUP:
            return OVERTURE_LOOKUP[name]
    if top == "health_care":
        return "medical"
    for name in (primary or "", basic or ""):
        if name.endswith("place_of_worship"):
            return "religious"
        if name.endswith("_law"):
            return "legal"
        if name.startswith("funeral"):
            return "death"
    return None


def overture_place_filter() -> str:
    names = ", ".join(f"'{n}'" for n in sorted(OVERTURE_LOOKUP))
    return (f"(taxonomy.primary IN ({names}) OR basic_category IN ({names})"
            " OR taxonomy.hierarchy[1] = 'health_care'"
            " OR taxonomy.primary LIKE '%place_of_worship' OR basic_category LIKE '%place_of_worship'"
            " OR taxonomy.primary LIKE '%\\_law' ESCAPE '\\' OR taxonomy.primary LIKE 'funeral%')")


# --- Overture land use and buildings (from OpenStreetMap) ---

def classify_land_use(subtype: str | None, cls: str | None) -> str | None:
    if subtype == "medical":
        return "medical"
    if subtype == "military":
        return "military_police"
    if subtype == "religious":
        return "religious"
    if subtype == "cemetery":
        return "death"
    if subtype == "education" and cls in ("school", "kindergarten"):
        return "minors"
    return None


RELIGIOUS_BUILDINGS = {"church", "chapel", "cathedral", "mosque", "synagogue", "temple", "shrine", "religious",
                       "monastery", "presbytery", "kingdom_hall"}


def classify_building(subtype: str | None, cls: str | None) -> str | None:
    if cls in RELIGIOUS_BUILDINGS or subtype == "religious":
        return "religious"
    if cls == "hospital" or subtype == "medical":
        return "medical"
    if cls in ("school", "kindergarten"):
        return "minors"
    if cls in ("military", "barracks", "bunker") or subtype == "military":
        return "military_police"
    if cls == "prison":
        return "correctional"
    return None


# --- OpenStreetMap points of interest (Layercake) ---

OSM_AMENITY = {
    "hospital": "medical", "clinic": "medical", "doctors": "medical", "dentist": "medical",
    "nursing_home": "medical", "hospice": "medical", "baby_hatch": "medical",
    "place_of_worship": "religious", "monastery": "religious",
    "prison": "correctional",
    "school": "minors", "kindergarten": "minors", "childcare": "minors", "prep_school": "minors",
    "social_facility": "shelter_social", "refugee_site": "shelter_social", "food_bank": "shelter_social",
    "social_centre": "shelter_social", "community_centre": "shelter_social",
    "police": "military_police",
    "pharmacy": "pharmacy",
    "funeral_hall": "death", "crematorium": "death", "grave_yard": "death", "mortuary": "death",
    "casino": "gambling", "gambling": "gambling",
    "stripclub": "adult", "brothel": "adult", "swingerclub": "adult", "love_hotel": "adult",
    "hookah_lounge": "cannabis_tobacco",
    "courthouse": "legal",
}
OSM_SHOP = {
    "chemist": "pharmacy", "medical_supply": "medical",
    "funeral_directors": "death",
    "alcohol": "alcohol", "wine": "alcohol",
    "bookmaker": "gambling", "lottery": "gambling",
    "erotic": "adult",
    "tobacco": "cannabis_tobacco", "e-cigarette": "cannabis_tobacco", "cannabis": "cannabis_tobacco",
    "pawnbroker": "lending", "money_lender": "lending",
    "weapons": "guns",
    "baby_goods": "baby",
    "religion": "religious",
}
OSM_OFFICE = {
    "religion": "religious",
    "union": "union", "trade_union": "union",
    "lawyer": "legal", "bail_bond_agent": "legal",
    "political_party": "political",
    "ngo": "associations", "charity": "associations",
}
OSM_LANDUSE = {"religious": "religious", "military": "military_police", "cemetery": "death"}
OSM_EDUCATION = {"school": "minors", "kindergarten": "minors", "preschool": "minors", "childcare": "minors"}
OSM_HISTORIC = {"church": "religious", "monastery": "religious", "mosque": "religious", "temple": "religious"}


def classify_osm(row: dict) -> str | None:
    if row.get("healthcare"):
        return "pharmacy" if row["healthcare"] == "pharmacy" else "medical"
    for column, table in (("amenity", OSM_AMENITY), ("shop", OSM_SHOP), ("office", OSM_OFFICE),
                          ("education", OSM_EDUCATION), ("landuse", OSM_LANDUSE), ("historic", OSM_HISTORIC)):
        cls = table.get(row.get(column) or "")
        if cls:
            return cls
    if row.get("social_facility"):
        return "shelter_social"
    if row.get("leisure") == "shooting_ground":
        return "guns"
    if (row.get("club") or "") in ("lgbt", "lgbtq", "gay"):
        return "lgbtq"
    if (row.get("building") or "") in RELIGIOUS_BUILDINGS:
        return "religious"
    if (row.get("building") or "") in ("hospital",):
        return "medical"
    if (row.get("building") or "") in ("school", "kindergarten"):
        return "minors"
    return None


def osm_filter() -> str:
    def among(column: str, values) -> str:
        return f'"{column}" IN ({", ".join(repr(v) for v in sorted(values))})'
    return ("(" + " OR ".join([
        "healthcare IS NOT NULL", "social_facility IS NOT NULL",
        among("amenity", OSM_AMENITY), among("shop", OSM_SHOP), among("office", OSM_OFFICE),
        among("education", OSM_EDUCATION), among("landuse", OSM_LANDUSE), among("historic", OSM_HISTORIC),
        among("leisure", {"shooting_ground"}), among("club", {"lgbt", "lgbtq", "gay"}),
        among("building", RELIGIOUS_BUILDINGS | {"hospital", "school", "kindergarten"}),
    ]) + ")")


OSM_COLUMNS = ("amenity", "healthcare", "office", "shop", "leisure", "landuse", "building", "social_facility",
               "historic", "education", "club")


# --- geometry ---

class Projection:
    """Local metres around a city's point. Accurate to a fraction of a percent over the 8 km used here."""

    def __init__(self, lat0: float, lng0: float):
        phi = math.radians(lat0)
        self.lat0, self.lng0 = lat0, lng0
        self.m_lat = 111132.92 - 559.82 * math.cos(2 * phi) + 1.175 * math.cos(4 * phi)
        self.m_lng = 111412.84 * math.cos(phi) - 93.5 * math.cos(3 * phi)

    def xy(self, lng, lat):
        return (np.asarray(lng) - self.lng0) * self.m_lng, (np.asarray(lat) - self.lat0) * self.m_lat

    def lnglat(self, x, y):
        return self.lng0 + np.asarray(x) / self.m_lng, self.lat0 + np.asarray(y) / self.m_lat

    def box(self, metres: float) -> tuple[float, float, float, float]:
        return (self.lng0 - metres / self.m_lng, self.lat0 - metres / self.m_lat,
                self.lng0 + metres / self.m_lng, self.lat0 + metres / self.m_lat)


def bbox_where(box, column: str = "bbox") -> str:
    x0, y0, x1, y1 = box
    return f"{column}.xmax >= {x0} AND {column}.xmin <= {x1} AND {column}.ymax >= {y0} AND {column}.ymin <= {y1}"


# --- fetching one city ---

_local = threading.local()
_db_lock = threading.Lock()
_db: duckdb.DuckDBPyConnection | None = None

# One database for all workers: its file-metadata caches, several GB for the Overture themes, are
# then shared rather than held once per worker. The buffer limit is shared too.
MEMORY_LIMIT = "3GB"


def connection() -> duckdb.DuckDBPyConnection:
    """This worker thread's cursor on the shared database."""
    global _db
    con = getattr(_local, "con", None)
    if con is None:
        with _db_lock:
            if _db is None:
                _db = duckdb.connect()
                for ext in ("httpfs", "spatial"):
                    _db.execute(f"INSTALL {ext}; LOAD {ext};")
                _db.execute(f"SET GLOBAL memory_limit='{MEMORY_LIMIT}'; SET GLOBAL threads=8; "
                            "SET GLOBAL s3_region='us-west-2'; SET GLOBAL enable_http_metadata_cache=true; "
                            "SET GLOBAL parquet_metadata_cache=true;")
            con = _db.cursor()
        _local.con = con
    return con


def fetch_city(city: dict) -> dict:
    """Reads one city's sensitive places and road samples. Returns them in local metres."""
    con = connection()
    proj = Projection(city["lat"], city["lng"])
    reach = proj.box(RADIUS_M + MAX_CLEARANCE_M)
    core = proj.box(CORE_M)
    features: list[tuple[int, float, float, float, float]] = []

    def add_box(cls, x0, y0, x1, y1):
        (a, b), (c, d) = proj.xy(x0, y0), proj.xy(x1, y1)
        features.append((CLASS_IDS[cls], round(float(a), 1), round(float(b), 1), round(float(c), 1), round(float(d), 1)))

    places = f"read_parquet('{OVERTURE}/theme=places/type=place/*', hive_partitioning=1)"
    for lng, lat, basic, primary, top in con.execute(
            f"SELECT ST_X(geometry), ST_Y(geometry), basic_category, taxonomy.primary, taxonomy.hierarchy[1] "
            f"FROM {places} WHERE {bbox_where(reach)} AND {overture_place_filter()}").fetchall():
        cls = classify_overture_place(basic, primary, top)
        if cls:
            add_box(cls, lng, lat, lng, lat)
    core_overture = con.execute(f"SELECT count(*) FROM {places} WHERE {bbox_where(core)}").fetchone()[0]

    land_use = f"read_parquet('{OVERTURE}/theme=base/type=land_use/*', hive_partitioning=1)"
    for subtype, cls_name, bb in con.execute(
            f"SELECT subtype, class, bbox FROM {land_use} WHERE {bbox_where(reach)} "
            f"AND subtype IN ('medical', 'military', 'religious', 'cemetery', 'education')").fetchall():
        cls = classify_land_use(subtype, cls_name)
        if cls:
            add_box(cls, bb["xmin"], bb["ymin"], bb["xmax"], bb["ymax"])

    buildings = f"read_parquet('{OVERTURE}/theme=buildings/type=building/*', hive_partitioning=1)"
    building_classes = ", ".join(repr(c) for c in sorted(RELIGIOUS_BUILDINGS | {
        "hospital", "school", "kindergarten", "military", "barracks", "bunker", "prison"}))
    for subtype, cls_name, bb in con.execute(
            f"SELECT subtype, class, bbox FROM {buildings} WHERE {bbox_where(reach)} "
            f"AND (class IN ({building_classes}) OR subtype IN ('religious', 'medical', 'military'))").fetchall():
        cls = classify_building(subtype, cls_name)
        if cls:
            add_box(cls, bb["xmin"], bb["ymin"], bb["xmax"], bb["ymax"])

    columns = ", ".join(f'"{c}"' for c in OSM_COLUMNS)
    for row in con.execute(
            f"SELECT {columns}, bbox FROM '{LAYERCAKE_POIS}' WHERE {bbox_where(reach)} AND {osm_filter()}").fetchall():
        tags = dict(zip(OSM_COLUMNS, row[:-1]))
        cls = classify_osm(tags)
        if cls:
            bb = row[-1]
            add_box(cls, bb["xmin"], bb["ymin"], bb["xmax"], bb["ymax"])
    core_osm = con.execute(f"SELECT count(*) FROM '{LAYERCAKE_POIS}' WHERE {bbox_where(core)}").fetchone()[0]

    segments = f"read_parquet('{OVERTURE}/theme=transportation/type=segment/*', hive_partitioning=1)"
    classes = ", ".join(repr(c) for c in ROAD_CLASSES)
    samples = []
    for flags, wkb in con.execute(
            f"SELECT road_flags, ST_AsWKB(geometry) FROM {segments} WHERE {bbox_where(proj.box(RADIUS_M))} "
            f"AND subtype = 'road' AND class IN ({classes})").fetchall():
        if flags and any(v in ("is_bridge", "is_tunnel") for f in flags for v in (f["values"] or [])):
            continue
        line = shapely.from_wkb(bytes(wkb))
        coords = np.asarray(line.coords)
        x, y = proj.xy(coords[:, 0], coords[:, 1])
        local = shapely.linestrings(np.column_stack([x, y]))
        steps = np.arange(0.0, local.length, SAMPLE_STEP_M)
        for p in shapely.line_interpolate_point(local, steps):
            if math.hypot(p.x, p.y) <= RADIUS_M:
                samples.append((round(p.x, 1), round(p.y, 1)))

    return {"name": city["name"], "lat": city["lat"], "lng": city["lng"], "core_overture": core_overture,
            "core_osm": core_osm, "features": features, "samples": samples}


def cache_path(cache: str, name: str) -> str:
    slug = re.sub(r"[^A-Za-z0-9]+", "_", name).strip("_")
    digest = hashlib.sha256(name.encode()).hexdigest()[:8]
    return os.path.join(cache, f"{slug}-{digest}.json.gz")


def fetch_cached(cache: str, city: dict) -> dict:
    path = cache_path(cache, city["name"])
    if os.path.exists(path):
        with gzip.open(path, "rt") as f:
            return json.load(f)
    data = fetch_city(city)
    with gzip.open(path + ".tmp", "wt") as f:
        json.dump(data, f, separators=(",", ":"))
    os.replace(path + ".tmp", path)
    return data


# --- choosing anchors ---

def clearances(data: dict) -> np.ndarray:
    samples = np.asarray(data["samples"], dtype=float).reshape(-1, 2)
    if not len(samples):
        return np.zeros(0)
    out = np.full(len(samples), float(MAX_CLEARANCE_M))
    if data["features"]:
        f = np.asarray(data["features"], dtype=float)
        # A point (or a line-thin area) becomes a box a centimetre across, so every feature is a polygon.
        tree = shapely.STRtree(shapely.box(f[:, 1], f[:, 2], np.maximum(f[:, 3], f[:, 1] + 0.01),
                                           np.maximum(f[:, 4], f[:, 2] + 0.01)))
        (sample_idx, _), dist = tree.query_nearest(shapely.points(samples), max_distance=MAX_CLEARANCE_M,
                                                    return_distance=True, all_matches=False)
        out[sample_idx] = np.minimum(dist, MAX_CLEARANCE_M)
    return out


def core_sensitive(data: dict) -> int:
    count = 0
    for _, x0, y0, x1, y1 in data["features"]:
        dx = max(x0, 0.0, -x1)
        dy = max(y0, 0.0, -y1)
        if math.hypot(dx, dy) <= CORE_M:
            count += 1
    return count


def resident_gb() -> float:
    """The process's resident memory, from /proc on Linux; 0 where that is not available."""
    try:
        with open("/proc/self/status") as f:
            for line in f:
                if line.startswith("VmRSS:"):
                    return int(line.split()[1]) / 2 ** 20
    except OSError:
        pass
    return 0.0


def unit_hash(*parts) -> float:
    digest = hashlib.sha256("|".join(str(p) for p in parts).encode()).digest()
    return (int.from_bytes(digest[:8], "big") + 0.5) / 2.0 ** 64


def choose_anchors(data: dict, clear: np.ndarray) -> list[list[float]]:
    proj = Projection(data["lat"], data["lng"])
    samples = np.asarray(data["samples"], dtype=float).reshape(-1, 2)
    ranked = []
    for (x, y), c in zip(samples, clear):
        if c < MIN_CLEARANCE_M:
            continue
        weight = 1.0 / (1.0 + math.hypot(x, y) / CENTRE_LEAN_M)
        key = unit_hash(data["name"], round(x), round(y)) ** (1.0 / weight)
        ranked.append((key, x, y, c))
    ranked.sort(reverse=True)
    chosen: list[tuple[float, float, float]] = []
    for _, x, y, c in ranked:
        if all(math.hypot(x - a, y - b) >= SPACING_M for a, b, _ in chosen):
            chosen.append((x, y, c))
            if len(chosen) == ANCHORS_PER_CITY:
                break
    anchors = []
    for x, y, c in chosen:
        lng, lat = proj.lnglat(x, y)
        anchors.append([round(float(lat), 5), round(float(lng), 5), int(c // 10 * 10)])
    return anchors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--cities", default="app/src/main/assets/city_coords.json")
    parser.add_argument("--out", default="app/src/main/assets/location_anchors.json")
    parser.add_argument("--cache", required=True, help="directory for per-city downloads (resumable)")
    parser.add_argument("--workers", type=int, default=6)
    parser.add_argument("--only", action="append", help="fetch and report these cities only; writes nothing")
    parser.add_argument("--stats", help="also write per-city figures to this JSON file")
    parser.add_argument("--max-memory-gb", type=float, default=12.0,
                        help="stop (resumably) if the process grows past this; the default suits a 32 GB machine")
    args = parser.parse_args()

    with open(args.cities, encoding="utf-8") as f:
        cities = json.load(f)
    names = [c["name"] for c in cities]
    if len(set(names)) != len(names):
        sys.exit("city_coords.json has duplicate city names")
    if args.only:
        cities = [c for c in cities if c["name"] in set(args.only)]
    os.makedirs(args.cache, exist_ok=True)

    results: dict[str, dict] = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(fetch_cached, args.cache, c): c["name"] for c in cities}
        for done, future in enumerate(concurrent.futures.as_completed(futures), 1):
            name = futures[future]
            try:
                results[name] = future.result()
            except Exception as e:  # report and carry on; a rerun retries the city
                print(f"[{done}/{len(cities)}] {name}: FAILED {type(e).__name__}: {e}", file=sys.stderr, flush=True)
                continue
            rss = resident_gb()
            print(f"[{done}/{len(cities)}] {name} ({rss:.1f} GB)", file=sys.stderr, flush=True)
            if rss > args.max_memory_gb:
                pool.shutdown(wait=False, cancel_futures=True)
                print(f"stopping: using {rss:.1f} GB, over --max-memory-gb {args.max_memory_gb}. The cities fetched "
                      f"so far are cached; run again to resume.", file=sys.stderr, flush=True)
                os._exit(2)  # the running workers' queries would otherwise finish first
    missing = [c["name"] for c in cities if c["name"] not in results]
    if missing:
        print(f"{len(missing)} cities failed; run again to retry them: {missing[:10]}", file=sys.stderr)
        return 1

    anchors: dict[str, list] = {}
    left_out: dict[str, str] = {}
    stats = {}
    for city in cities:
        data = results[city["name"]]
        clear = clearances(data)
        sensitive = core_sensitive(data)
        chosen = choose_anchors(data, clear)
        stats[city["name"]] = {
            "core_overture": data["core_overture"], "core_osm": data["core_osm"], "core_sensitive": sensitive,
            "samples": int(len(clear)), "clear": int((clear >= MIN_CLEARANCE_M).sum()), "anchors": len(chosen),
        }
        thin = min(data["core_overture"], data["core_osm"]) < LEFT_OUT_MIN_PLACES or sensitive < LEFT_OUT_MIN_SENSITIVE
        if thin:
            left_out[city["name"]] = (f"thin map data: {data['core_overture']} Overture places, {data['core_osm']} "
                                      f"OpenStreetMap places and {sensitive} sensitive places within {CORE_M // 1000} km")
        elif not chosen:
            left_out[city["name"]] = f"no main road {MIN_CLEARANCE_M} m clear of sensitive places within {RADIUS_M / 1000:g} km"
        else:
            anchors[city["name"]] = chosen
    if args.stats:
        with open(args.stats, "w", encoding="utf-8") as f:
            json.dump(stats, f, indent=1, ensure_ascii=False)
    if args.only:
        for name, s in stats.items():
            print(name, s, anchors.get(name) or left_out.get(name))
        return 0

    lines = [
        "{",
        f'  "about": "Where Location Spoofing may put the decoy: points on public main roads and pedestrian streets, '
        f'each at least {MIN_CLEARANCE_M} m from every sensitive place, as [lat, lng, clearance in metres]. '
        f'Built by scripts/build_location_anchors.py (#322 item 12).",',
        '  "sources": ["Overture Maps Foundation, release ' + OVERTURE_RELEASE + ' (places CDLA Permissive 2.0; '
        'land use, buildings and roads from OpenStreetMap, ODbL)", "OpenStreetMap contributors, via OpenStreetMap US '
        'Layercake (ODbL)"],',
        f'  "buffer_m": {BUFFER_M},',
        f'  "radius_m": {RADIUS_M},',
        '  "categories": ' + json.dumps(CATEGORIES, ensure_ascii=False) + ",",
        '  "left_out": ' + json.dumps(dict(sorted(left_out.items())), ensure_ascii=False, indent=4).replace("\n", "\n  ") + ",",
        '  "cities": {',
    ]
    entries = [f"    {json.dumps(name, ensure_ascii=False)}: {json.dumps(points)}" for name, points in anchors.items()]
    lines.append(",\n".join(entries))
    lines += ["  }", "}", ""]
    with open(args.out, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"{len(anchors)} cities with {sum(len(a) for a in anchors.values())} anchors; {len(left_out)} left out; "
          f"wrote {args.out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""
E8 (#174) offline preprocessing — backfill the `region` field of city_coords.json.

CityDatabase.randomCity(regionHint) filters cities by SUBSTRING match on the region
field, but the 806 bundled cities ship with no region at all, so any hint filters to
empty and silently falls back to the full list (M3 spike, trap 1). This developer tool
(committed, not shipped) tags every city with space-separated Region tokens:

- US cities ("City, United States"): the state is resolved against the Census Gazetteer
  national places file (name-matched candidates preferred, else nearest place by
  coordinates), then mapped to fauxx's 5-way US region scheme -> one token, e.g.
  "US_WEST". Same partition as scripts/build_persona_distribution.py.
- Non-US cities: the country suffix maps to the coarse Region taxonomy. Countries with
  their own Region enum value get BOTH tokens so specific and macro persona hints match
  the same city: "SPAIN WESTERN_EUROPE", "MEXICO LATIN_AMERICA", "QUEBEC CANADA".
- "RUSSIA EASTERN_EUROPE": RUSSIA is not a Region enum value, but ru-locale persona
  templates use it as their region string, and the hint match is plain substring.

Taxonomy judgment calls (fauxx's Region enum is deliberately coarse): Caucasus ->
EASTERN_EUROPE; Central/South Asia and Oceania -> ASIA_PACIFIC; all of Africa and the
Middle East -> MIDDLE_EAST_AFRICA; Caribbean -> LATIN_AMERICA; UK crown dependencies ->
UK; Nordic/Mediterranean EU -> WESTERN_EUROPE.

The script fails loudly on any unmapped country or unmatched US city rather than
writing a partial asset.

Run:  python3 scripts/backfill_city_regions.py
Out:  app/src/main/assets/city_coords.json  (region field populated in place)
"""
import csv
import io
import json
import math
import os
import sys
import urllib.request
import zipfile

GAZETTEER_URL = ("https://www2.census.gov/geo/docs/maps-data/data/gazetteer/"
                 "2023_Gazetteer/2023_Gaz_place_national.zip")

# USPS state code -> fauxx Region (same 5-way partition as build_persona_distribution.py).
USPS_REGION = {
    "CT": "US_NORTHEAST", "ME": "US_NORTHEAST", "MA": "US_NORTHEAST", "NH": "US_NORTHEAST",
    "NJ": "US_NORTHEAST", "NY": "US_NORTHEAST", "PA": "US_NORTHEAST", "RI": "US_NORTHEAST",
    "VT": "US_NORTHEAST",
    "IL": "US_MIDWEST", "IN": "US_MIDWEST", "IA": "US_MIDWEST", "KS": "US_MIDWEST",
    "MI": "US_MIDWEST", "MN": "US_MIDWEST", "MO": "US_MIDWEST", "NE": "US_MIDWEST",
    "ND": "US_MIDWEST", "OH": "US_MIDWEST", "SD": "US_MIDWEST", "WI": "US_MIDWEST",
    "AL": "US_SOUTHEAST", "AR": "US_SOUTHEAST", "DE": "US_SOUTHEAST", "DC": "US_SOUTHEAST",
    "FL": "US_SOUTHEAST", "GA": "US_SOUTHEAST", "KY": "US_SOUTHEAST", "LA": "US_SOUTHEAST",
    "MD": "US_SOUTHEAST", "MS": "US_SOUTHEAST", "NC": "US_SOUTHEAST", "SC": "US_SOUTHEAST",
    "TN": "US_SOUTHEAST", "VA": "US_SOUTHEAST", "WV": "US_SOUTHEAST",
    "AZ": "US_SOUTHWEST", "NM": "US_SOUTHWEST", "OK": "US_SOUTHWEST", "TX": "US_SOUTHWEST",
    "AK": "US_WEST", "CA": "US_WEST", "CO": "US_WEST", "HI": "US_WEST", "ID": "US_WEST",
    "MT": "US_WEST", "NV": "US_WEST", "OR": "US_WEST", "UT": "US_WEST", "WA": "US_WEST",
    "WY": "US_WEST",
}

# Countries with their own Region enum value (or, for RUSSIA, a ru-locale persona
# region string): tagged "<SPECIFIC> <MACRO>" so both hint styles match.
SPECIFIC = {
    "Spain": "SPAIN WESTERN_EUROPE",
    # Spanish autonomous community; es-locale SPAIN personas must be able to match it.
    "Canary Islands": "SPAIN WESTERN_EUROPE",
    "France": "FRANCE WESTERN_EUROPE",
    "Belgium": "BELGIUM WESTERN_EUROPE",
    "Switzerland": "SWITZERLAND WESTERN_EUROPE",
    "Mexico": "MEXICO LATIN_AMERICA",
    "Argentina": "ARGENTINA LATIN_AMERICA",
    "Colombia": "COLOMBIA LATIN_AMERICA",
    "Chile": "CHILE LATIN_AMERICA",
    "Peru": "PERU LATIN_AMERICA",
    "Russia": "RUSSIA EASTERN_EUROPE",
}

# Cities in the Canadian province of Quebec present in the asset (FR-locale personas
# use the QUEBEC region); the rest of Canada is plain "CANADA".
QUEBEC_CITIES = {"Montreal, Canada", "Quebec City, Canada"}

# US-suffixed places excluded from persona location stories. UNBOUND is a sentinel
# token no persona region hint ever matches: the city stays in the unfiltered world
# pool but never anchors a coherent persona (Midway is an uninhabited wildlife refuge —
# region-binding would concentrate a US_WEST persona's fixes there ~25x).
US_NAME_OVERRIDES = {"Midway Atoll, United States": "UNBOUND"}

MACRO = {
    "CANADA": ["Canada"],
    "UK": ["United Kingdom", "Guernsey", "Jersey", "Isle of Man", "Gibraltar"],
    "WESTERN_EUROPE": [
        "Germany", "Italy", "Netherlands", "Portugal", "Norway", "Sweden", "Finland",
        "Denmark", "Austria", "Ireland", "Greece", "Iceland", "Luxembourg", "Malta",
        "Cyprus", "Andorra", "Monaco", "San Marino", "Liechtenstein", "Faroe Islands",
        "Greenland", "Azores", "Madeira", "Svalbard",
    ],
    "EASTERN_EUROPE": [
        "Poland", "Ukraine", "Romania", "Belarus", "Czech Republic", "Bulgaria",
        "Croatia", "Hungary", "Serbia", "Slovakia", "Slovenia", "Lithuania", "Latvia",
        "Estonia", "Moldova", "Bosnia and Herzegovina", "Albania", "Kosovo",
        "Montenegro", "North Macedonia", "Armenia", "Georgia", "Azerbaijan",
    ],
    "LATIN_AMERICA": [
        "Brazil", "Venezuela", "Cuba", "Ecuador", "Bolivia", "Guatemala", "Costa Rica",
        "Panama", "Honduras", "Nicaragua", "El Salvador", "Paraguay", "Uruguay",
        "Dominican Republic", "Haiti", "Jamaica", "Trinidad and Tobago", "Barbados",
        "Bahamas", "Belize", "Guyana", "Suriname", "French Guiana", "Puerto Rico",
        "Aruba", "Curaçao", "Martinique", "Guadeloupe", "Antigua",
        "Antigua and Barbuda", "Dominica", "Grenada", "Saint Lucia",
        "Saint Kitts and Nevis", "Saint Vincent", "Saint Vincent and the Grenadines",
        "Saint Martin", "Sint Maarten", "Saint Barthélemy", "British Virgin Islands",
        "US Virgin Islands",
    ],
    "ASIA_PACIFIC": [
        "China", "India", "Japan", "Australia", "South Korea", "North Korea",
        "Indonesia", "Philippines", "Thailand", "Malaysia", "Vietnam", "Myanmar",
        "Sri Lanka", "Pakistan", "Bangladesh", "Nepal", "Bhutan", "Cambodia", "Laos",
        "Mongolia", "Taiwan", "Singapore", "Brunei", "East Timor", "New Zealand",
        "Papua New Guinea", "Fiji", "Samoa", "American Samoa", "Tonga", "Vanuatu",
        "Solomon Islands", "Kiribati", "Tuvalu", "Nauru", "Palau", "Micronesia",
        "Marshall Islands", "Guam", "Northern Mariana Islands", "New Caledonia",
        "French Polynesia", "Wallis and Futuna", "Cook Islands", "Maldives",
        "Kazakhstan", "Kyrgyzstan", "Tajikistan", "Turkmenistan", "Uzbekistan",
        "Afghanistan",
    ],
    "MIDDLE_EAST_AFRICA": [
        "Turkey", "Iran", "Saudi Arabia", "Nigeria", "South Africa", "Egypt",
        "Morocco", "United Arab Emirates", "Iraq", "Israel", "Kenya", "Jordan",
        "Lebanon", "Syria", "Yemen", "Oman", "Qatar", "Kuwait", "Bahrain", "Algeria",
        "Tunisia", "Libya", "Sudan", "South Sudan", "Ethiopia", "Eritrea", "Djibouti",
        "Somalia", "Uganda", "Tanzania", "Rwanda", "Burundi", "DR Congo",
        "Democratic Republic of the Congo", "Republic of the Congo", "Angola",
        "Zambia", "Zimbabwe", "Malawi", "Mozambique", "Madagascar", "Botswana",
        "Namibia", "Lesotho", "Eswatini", "Ghana", "Côte d'Ivoire", "Senegal", "Mali",
        "Burkina Faso", "Niger", "Chad", "Cameroon", "Gabon", "Benin", "Togo",
        "Guinea", "Guinea-Bissau", "Sierra Leone", "Liberia", "Gambia", "Mauritania",
        "Cape Verde", "São Tomé and Príncipe", "Equatorial Guinea",
        "Central African Republic", "Comoros", "Mauritius", "Seychelles", "Réunion",
        "Mayotte", "Saint Helena", "Ascension Island", "Tristan da Cunha",
    ],
}

COUNTRY_REGION = dict(SPECIFIC)
for region, countries in MACRO.items():
    for country in countries:
        if country in COUNTRY_REGION:
            print(f"Duplicate country mapping: {country}", file=sys.stderr)
            sys.exit(1)
        COUNTRY_REGION[country] = region


def download(url):
    for attempt in range(1, 4):
        try:
            with urllib.request.urlopen(url, timeout=300) as resp:
                return resp.read()
        except Exception as e:  # noqa: BLE001
            print(f"  ! attempt {attempt}/3 failed: {e}", file=sys.stderr)
    print(f"Could not download {url}; aborting.", file=sys.stderr)
    sys.exit(1)


def load_gazetteer():
    """[(usps, normalized_name, lat, lng)] for every US place."""
    blob = download(GAZETTEER_URL)
    places = []
    with zipfile.ZipFile(io.BytesIO(blob)) as z:
        txt = next(n for n in z.namelist() if n.lower().endswith(".txt"))
        with z.open(txt) as fh:
            reader = csv.DictReader(io.TextIOWrapper(fh, encoding="utf-8", errors="replace"),
                                    delimiter="\t")
            for r in reader:
                # Column headers carry trailing whitespace in this file; normalize keys.
                row = {k.strip(): (v.strip() if v else "") for k, v in r.items() if k}
                usps = row.get("USPS")
                name = row.get("NAME", "").lower()
                try:
                    lat = float(row.get("INTPTLAT", ""))
                    lng = float(row.get("INTPTLONG", ""))
                except ValueError:
                    continue
                if usps in USPS_REGION and name:
                    places.append((usps, name, lat, lng))
    if len(places) < 10_000:
        print(f"Gazetteer parse suspicious: only {len(places)} places; aborting.",
              file=sys.stderr)
        sys.exit(1)
    return places


def us_region(city_name, lat, lng, places):
    """Resolve a US city to its region: name-matched candidates first, else nearest."""
    plain = city_name.removesuffix(", United States").lower()

    def dist2(p):
        # Scale longitude by cos(latitude): unscaled degrees overweight east-west
        # distance at high latitudes (Alaska), risking wrong-state nearest matches.
        dlat = p[2] - lat
        dlng = (p[3] - lng) * math.cos(math.radians(lat))
        return dlat * dlat + dlng * dlng

    named = [p for p in places if p[1].startswith(plain)]
    pool = named if named else places
    best = min(pool, key=dist2)
    if dist2(best) > 1.5 ** 2:
        # Name-matched pool can be a same-named place in another state; retry nearest-only.
        best = min(places, key=dist2)
        if dist2(best) > 1.5 ** 2:
            print(f"No gazetteer place near {city_name} ({lat},{lng}); aborting.",
                  file=sys.stderr)
            sys.exit(1)
    return USPS_REGION[best[0]], best


def main():
    src = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "app", "src",
                                        "main", "assets", "city_coords.json"))
    with open(src, encoding="utf-8") as f:
        cities = json.load(f)

    places = load_gazetteer()
    unmapped = sorted({
        c["name"].rsplit(", ", 1)[-1] for c in cities
        if c["name"].rsplit(", ", 1)[-1] not in COUNTRY_REGION
        and c["name"].rsplit(", ", 1)[-1] != "United States"
    })
    if unmapped:
        print(f"Unmapped countries: {unmapped}; aborting.", file=sys.stderr)
        sys.exit(1)

    out = []
    for c in cities:
        country = c["name"].rsplit(", ", 1)[-1]
        if c["name"] in US_NAME_OVERRIDES:
            region = US_NAME_OVERRIDES[c["name"]]
        elif country == "United States":
            region, matched = us_region(c["name"], c["lat"], c["lng"], places)
            print(f"  {c['name']:<40} -> {region:<14} (via {matched[1]}, {matched[0]})")
        elif c["name"] in QUEBEC_CITIES:
            region = "QUEBEC CANADA"
        else:
            region = COUNTRY_REGION[country]
        out.append({"name": c["name"], "lat": c["lat"], "lng": c["lng"], "region": region})

    with open(src, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
        f.write("\n")
    from collections import Counter
    print(f"\nWROTE {src}: {len(out)} cities, regions populated")
    print("region counts:", dict(Counter(o["region"] for o in out).most_common()))


if __name__ == "__main__":
    main()

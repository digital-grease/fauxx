#!/usr/bin/env python3
"""
E7 (#173) offline preprocessing — build the joint persona distribution from ACS PUMS.

This is a DEVELOPER tool, not shipped in the app. It downloads curated US Census ACS PUMS
1-Year person files, derives each person's (AgeRange, Profession, Region) in fauxx's own
taxonomy, PWGTP-weights them, and writes the bundled joint distribution asset that
PersonaGenerator multinomial-samples from so age/profession/region CO-OCCUR per real data
(replacing independently-picked hand-authored archetypes for US personas).

Hybrid scope (ratified 2026-06-09): US personas come from this distribution; the hand-authored
es/fr/ru locale templates are kept as-is (PUMS is US-only). Non-EN microdata regeneration
(Eurostat/INE/INEGI) is a tracked follow-up.

Privacy / G3: only the AGEP, ST/STATE, SOCP, ESR, SCH, PWGTP columns are ever consulted
(csv.DictReader materializes whole rows in memory, but no other field is read), and the
artifact contains only derived (age, profession, region, weight) aggregates. Race,
ancestry, sex, etc. are never read or persisted.

Region representativeness: pooling raw PWGTP across a 2-states-per-region sample would
make each region's mass equal its sampled states' share of the pool (TX+AZ would inflate
US_SOUTHWEST by ~65% relative). main() therefore POST-STRATIFIES: each region's cells are
rescaled so the region marginal matches its true national population share, computed from
the Census state population estimates for PUMS_YEAR. Within-region (age, profession)
composition still reflects only the sampled states — a documented limitation; add states
to DOWNLOAD_STATES to narrow it.

Run:  python3 scripts/build_persona_distribution.py
Out:  app/src/main/assets/persona_distribution.json   (~36KB; 6x12x5 = 360 cells max)

Regeneration is a ~2-year chore when a new PUMS vintage lands; bump PUMS_YEAR and re-run.
(The 2023+ PUMS vintages rename the ST column to STATE; both are handled.)
"""
import csv
import io
import json
import os
import sys
import urllib.request
import zipfile

PUMS_YEAR = "2022"
BASE = f"https://www2.census.gov/programs-surveys/acs/data/pums/{PUMS_YEAR}/1-Year"

# Curated download set: two larger states per region keeps the joint table well-populated
# without the 591MB national file. Extend with more states (any FIPS in STATE_REGION) for a
# more representative artifact.
DOWNLOAD_STATES = ["ny", "ma", "fl", "ga", "il", "oh", "tx", "az", "ca", "wa"]

# State FIPS -> fauxx Region enum (custom 5-way US scheme, not official Census regions).
STATE_REGION = {
    9: "US_NORTHEAST", 23: "US_NORTHEAST", 25: "US_NORTHEAST", 33: "US_NORTHEAST",
    34: "US_NORTHEAST", 36: "US_NORTHEAST", 42: "US_NORTHEAST", 44: "US_NORTHEAST", 50: "US_NORTHEAST",
    17: "US_MIDWEST", 18: "US_MIDWEST", 19: "US_MIDWEST", 20: "US_MIDWEST", 26: "US_MIDWEST",
    27: "US_MIDWEST", 29: "US_MIDWEST", 31: "US_MIDWEST", 38: "US_MIDWEST", 39: "US_MIDWEST",
    46: "US_MIDWEST", 55: "US_MIDWEST",
    1: "US_SOUTHEAST", 5: "US_SOUTHEAST", 10: "US_SOUTHEAST", 11: "US_SOUTHEAST", 12: "US_SOUTHEAST",
    13: "US_SOUTHEAST", 21: "US_SOUTHEAST", 22: "US_SOUTHEAST", 24: "US_SOUTHEAST", 28: "US_SOUTHEAST",
    37: "US_SOUTHEAST", 45: "US_SOUTHEAST", 47: "US_SOUTHEAST", 51: "US_SOUTHEAST", 54: "US_SOUTHEAST",
    4: "US_SOUTHWEST", 35: "US_SOUTHWEST", 40: "US_SOUTHWEST", 48: "US_SOUTHWEST",
    2: "US_WEST", 6: "US_WEST", 8: "US_WEST", 15: "US_WEST", 16: "US_WEST", 30: "US_WEST",
    32: "US_WEST", 41: "US_WEST", 49: "US_WEST", 53: "US_WEST", 56: "US_WEST",
}

# SOC major group (first 2 digits of SOCP) -> Profession enum.
# 11 (Management) joins 13 (Business/Financial Ops) under FINANCE_PROF, whose
# user-facing label is "Business Professional".
SOC_MAJOR = {
    "11": "FINANCE_PROF", "13": "FINANCE_PROF", "15": "ENGINEER", "17": "ENGINEER",
    "25": "TEACHER", "29": "HEALTHCARE", "31": "HEALTHCARE", "23": "LEGAL",
    "41": "RETAIL", "47": "TRADES", "49": "TRADES", "51": "TRADES", "27": "CREATIVE",
}


# Census state population estimates (SUMLEV 040 rows, POPESTIMATE<year> column) — used
# to post-stratify region marginals to true national shares.
POPEST_URL = ("https://www2.census.gov/programs-surveys/popest/datasets/"
              "2020-2023/state/totals/NST-EST2023-ALLDATA.csv")


def region_population_targets():
    """True share of national population per fauxx Region, from Census state estimates."""
    blob = download(POPEST_URL)
    if blob is None:
        print("Could not download state population estimates; aborting — without "
              "post-stratification the artifact would carry the sampled-states region "
              "skew.", file=sys.stderr)
        sys.exit(1)
    totals = {}
    reader = csv.DictReader(io.StringIO(blob.decode("utf-8", errors="replace")))
    for r in reader:
        if r.get("SUMLEV") != "040":
            continue
        region = STATE_REGION.get(parse_int(r.get("STATE")))
        pop = parse_int(r.get(f"POPESTIMATE{PUMS_YEAR}"))
        if region and pop:
            totals[region] = totals.get(region, 0) + pop
    expected = set(STATE_REGION.values())
    if set(totals) != expected:
        print(f"Population estimates incomplete: got {sorted(totals)}, "
              f"expected {sorted(expected)}; aborting.", file=sys.stderr)
        sys.exit(1)
    national = sum(totals.values())
    return {region: pop / national for region, pop in totals.items()}


def age_bin(agep):
    if agep < 18:
        return None
    if agep <= 24:
        return "AGE_18_24"
    if agep <= 34:
        return "AGE_25_34"
    if agep <= 44:
        return "AGE_35_44"
    if agep <= 54:
        return "AGE_45_54"
    if agep <= 64:
        return "AGE_55_64"
    return "AGE_65_PLUS"


def profession(agep, esr, sch, socp):
    # Bucket semantics are deliberately coarse to fit fauxx's 12-value Profession enum:
    # - STUDENT: enrolled (SCH 2/3) and <=34, even if also employed.
    # - RETIRED: not in labor force and >=62 (early Social Security age); NILF under 62
    #   maps to HOMEMAKER, which therefore also absorbs disabled adults, discouraged
    #   workers, and early retirees — it overstates literal homemakers by design.
    # - Armed forces (ESR 4/5) carry SOC major group 55, absent from SOC_MAJOR, so they
    #   land in OTHER alongside unemployed and unmapped civilian occupations.
    enrolled = sch in (2, 3)
    if enrolled and agep <= 34:
        return "STUDENT"
    if esr == 6:  # not in labor force
        return "RETIRED" if agep >= 62 else "HOMEMAKER"
    if esr in (1, 2, 4, 5):  # employed (civilian or armed forces)
        mg = socp[:2] if socp and socp[:2].isdigit() else None
        return SOC_MAJOR.get(mg, "OTHER")
    return "OTHER"  # unemployed / not determinable


def parse_int(s):
    try:
        return int(float(s))
    except (ValueError, TypeError):
        return None


def download(url, attempts=3):
    for attempt in range(1, attempts + 1):
        try:
            with urllib.request.urlopen(url, timeout=300) as resp:
                return resp.read()
        except Exception as e:  # noqa: BLE001
            print(f"  ! attempt {attempt}/{attempts} failed: {e}", file=sys.stderr)
    return None


def process_state(abbr, counts):
    url = f"{BASE}/csv_p{abbr}.zip"
    print(f"  downloading {url} ...", flush=True)
    blob = download(url)
    if blob is None:
        print(f"  ! skip {abbr}: all download attempts failed", file=sys.stderr)
        return 0
    rows = 0
    try:
        z = zipfile.ZipFile(io.BytesIO(blob))
    except zipfile.BadZipFile as e:
        # census.gov can answer 200 with an HTML maintenance page; skip, don't abort.
        print(f"  ! skip {abbr}: not a zip ({e})", file=sys.stderr)
        return 0
    with z:
        csv_name = max((n for n in z.namelist() if n.lower().endswith(".csv")),
                       key=lambda n: z.getinfo(n).file_size, default=None)
        if not csv_name:
            print(f"  ! no csv in {abbr}", file=sys.stderr)
            return 0
        with z.open(csv_name) as fh:
            reader = csv.DictReader(io.TextIOWrapper(fh, encoding="utf-8", errors="replace"))
            for r in reader:
                agep = parse_int(r.get("AGEP"))
                st = parse_int(r.get("ST") or r.get("STATE"))  # renamed in 2023+ vintages
                w = parse_int(r.get("PWGTP"))
                if agep is None or st is None or not w:
                    continue
                age = age_bin(agep)
                region = STATE_REGION.get(st)
                if age is None or region is None:
                    continue
                esr = parse_int(r.get("ESR"))
                sch = parse_int(r.get("SCH"))
                prof = profession(agep, esr if esr is not None else -1,
                                  sch if sch is not None else -1, (r.get("SOCP") or "").strip())
                counts[(age, prof, region)] = counts.get((age, prof, region), 0) + w
                rows += 1
    print(f"  {abbr}: {rows} persons", flush=True)
    return rows


def main():
    counts = {}
    included = []
    for abbr in DOWNLOAD_STATES:
        if process_state(abbr, counts) > 0:
            included.append(abbr)
    total = sum(counts.values())
    if total == 0:
        print("No data collected; aborting.", file=sys.stderr)
        sys.exit(1)
    skipped = [s for s in DOWNLOAD_STATES if s not in included]
    if skipped:
        # A partial artifact silently reshapes within-region composition even after
        # post-stratification; refuse to write one.
        print(f"FATAL: states skipped (download/parse failure): {skipped} — "
              f"re-run rather than committing a skewed artifact.", file=sys.stderr)
        sys.exit(1)

    # Post-stratify region marginals to true national shares (see module docstring).
    targets = region_population_targets()
    observed = {}
    for (_, _, region), w in counts.items():
        observed[region] = observed.get(region, 0) + w
    factors = {region: targets[region] / (observed[region] / total) for region in observed}
    print("post-stratification factors:",
          {region: round(f, 4) for region, f in sorted(factors.items())}, flush=True)
    counts = {key: w * factors[key[2]] for key, w in counts.items()}
    total = sum(counts.values())

    # Full-precision weights: rounding to 8 decimals would silently zero (and the app
    # loader would then drop) any cell with true probability below 5e-9.
    cells = [
        {"age": a, "profession": p, "region": r, "weight": w / total}
        for (a, p, r), w in sorted(counts.items(), key=lambda kv: -kv[1])
    ]
    out = {
        "version": 1,
        "source": f"US Census ACS PUMS {PUMS_YEAR} 1-Year, states={included}",
        "note": "Joint P(AgeRange, Profession, Region) over US adults, PWGTP-weighted, "
                "region marginals post-stratified to Census state population estimates. "
                "Within-region composition reflects only the sampled states. US-only by "
                "design (hybrid: es/fr/ru keep hand-authored templates).",
        "dimensions": {
            "age": ["AGE_18_24", "AGE_25_34", "AGE_35_44", "AGE_45_54", "AGE_55_64", "AGE_65_PLUS"],
            "profession": ["STUDENT", "TEACHER", "ENGINEER", "HEALTHCARE", "LEGAL", "FINANCE_PROF",
                           "RETAIL", "TRADES", "CREATIVE", "RETIRED", "HOMEMAKER", "OTHER"],
            "region": ["US_NORTHEAST", "US_SOUTHEAST", "US_MIDWEST", "US_SOUTHWEST", "US_WEST"],
        },
        "cells": cells,
    }
    dest = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets",
                        "persona_distribution.json")
    dest = os.path.normpath(dest)
    with open(dest, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=1)
    print(f"\nWROTE {dest}: {len(cells)} non-zero cells, {os.path.getsize(dest)} bytes, "
          f"total weighted persons={total}")


if __name__ == "__main__":
    main()

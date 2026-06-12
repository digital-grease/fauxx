#!/usr/bin/env python3
"""
E4 (#180) offline preprocessing — build the ad-category co-occurrence prior used by the
on-device broker-inference surrogate (BrokerSurrogate / AdversarialAllocator).

This is a DEVELOPER tool, not shipped in the app. It writes the bundled asset
app/src/main/assets/ad_category_cooccurrence.json that the on-device surrogate reads.

HONESTY (the load-bearing point — see the M0 AD_CLICK retirement and the E2 "honest caveat"):
there is NO authoritative public dataset of ad-category co-occurrence aligned to fauxx's own
31-category CategoryPool taxonomy. Real broker taxonomies (Google affinity/in-market segments,
IAB Content Taxonomy, Meta detailed targeting) are proprietary, differently grained, and publish
no co-occurrence statistics. So this file ships a DESIGNED PRIOR, not "measured broker stats":
hand-curated semantic affinity clusters describing how a broker plausibly groups interests
(financial-life, home/family, health, outdoor, tech, culture, civic, lifestyle, young/social).
The surrogate that consumes it is explicitly a research stand-in for broker inference, used only
to decide WHERE to place adversarial noise — never a claim that it mirrors any real broker model.

Method:
- Each category belongs to one or more affinity CLUSTERS (a category can bridge several).
- The affinity of an unordered pair is the max intra-cluster weight over the clusters they share,
  plus a handful of explicit cross-cluster bridges, clipped to [0, 1].
- Logistic coefficients (bias, selfCoef, neighborCoef) parameterize the surrogate:
  score(c) = sigmoid(bias + selfCoef * own_mass + neighborCoef * neighbor_mass), where masses are
  category-count-scaled so a uniform distribution maps each feature near 1.0.

Run:  python3 scripts/build_cooccurrence.py
Out:  app/src/main/assets/ad_category_cooccurrence.json
"""

import json
import os

# Must match com.fauxx.data.querybank.CategoryPool exactly (32 values).
CATEGORIES = [
    "MEDICAL", "LEGAL", "AUTOMOTIVE", "PARENTING", "RETIREMENT", "GAMING", "AGRICULTURE",
    "FASHION", "ACADEMIC", "REAL_ESTATE", "COOKING", "SPORTS", "FINANCE", "TRAVEL",
    "TECHNOLOGY", "PETS", "HOME_IMPROVEMENT", "BEAUTY", "MUSIC", "FITNESS", "ENTERTAINMENT",
    "FOOD", "POLITICS", "SCIENCE", "BUSINESS", "OUTDOOR_RECREATION", "CRAFTS", "HISTORY",
    "ENVIRONMENT", "MILITARY_DEFENSE", "WELLNESS_ALTERNATIVE", "RELATIONSHIPS_DATING",
]

# (cluster weight, members). A category may appear in several clusters; bridge categories
# (FOOD, BEAUTY, MUSIC, FITNESS, ENTERTAINMENT) deliberately span multiple life-domains.
CLUSTERS = [
    (0.85, ["FINANCE", "REAL_ESTATE", "BUSINESS", "RETIREMENT", "LEGAL"]),                  # financial life
    (0.80, ["PARENTING", "HOME_IMPROVEMENT", "COOKING", "FOOD", "PETS", "CRAFTS"]),         # home / family
    (0.80, ["MEDICAL", "FITNESS", "WELLNESS_ALTERNATIVE", "FOOD", "BEAUTY"]),               # health / wellness
    (0.80, ["OUTDOOR_RECREATION", "SPORTS", "FITNESS", "AGRICULTURE", "AUTOMOTIVE", "PETS"]),  # outdoor / active
    (0.85, ["TECHNOLOGY", "GAMING", "SCIENCE", "ENTERTAINMENT"]),                           # tech / digital
    (0.75, ["ACADEMIC", "HISTORY", "SCIENCE", "ENVIRONMENT", "MUSIC"]),                     # culture / learning
    (0.75, ["POLITICS", "ENVIRONMENT", "MILITARY_DEFENSE", "HISTORY"]),                     # civic
    (0.80, ["TRAVEL", "FASHION", "BEAUTY", "ENTERTAINMENT", "MUSIC", "FOOD"]),              # lifestyle / leisure
    (0.70, ["RELATIONSHIPS_DATING", "FASHION", "BEAUTY", "MUSIC", "ENTERTAINMENT", "GAMING", "FITNESS"]),  # young / social
]

# Explicit cross-cluster bridges a broker would plausibly infer but the clusters above miss.
CROSS_LINKS = [
    ("AUTOMOTIVE", "TRAVEL", 0.45),
    ("MEDICAL", "RETIREMENT", 0.55),
    ("PARENTING", "ACADEMIC", 0.45),
    ("REAL_ESTATE", "HOME_IMPROVEMENT", 0.6),
    ("FINANCE", "TECHNOLOGY", 0.4),
    ("AGRICULTURE", "ENVIRONMENT", 0.5),
    ("MILITARY_DEFENSE", "AUTOMOTIVE", 0.35),
    ("PETS", "MEDICAL", 0.35),
    ("TRAVEL", "OUTDOOR_RECREATION", 0.5),
]

# Logistic surrogate coefficients. Kept here so the asset and the on-device default
# (CooccurrenceTable.DEFAULT_*) stay in sync; tune deliberately, they are heuristic.
MODEL = {"bias": -2.0, "selfCoef": 6.0, "neighborCoef": 3.0}

PROVENANCE = (
    "Designed semantic-affinity prior (hand-curated clusters; scripts/build_cooccurrence.py). "
    "NOT measured broker statistics: no public source publishes co-occurrence over fauxx's "
    "32-category taxonomy. Research surrogate for broker inference only."
)

OUT_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "assets", "ad_category_cooccurrence.json",
)


def build_affinities():
    pair = {}

    def put(a, b, w):
        if a == b:
            return
        key = (a, b) if a < b else (b, a)
        pair[key] = max(pair.get(key, 0.0), round(min(max(w, 0.0), 1.0), 3))

    for weight, members in CLUSTERS:
        for i in range(len(members)):
            for j in range(i + 1, len(members)):
                put(members[i], members[j], weight)

    for a, b, w in CROSS_LINKS:
        put(a, b, w)

    return [
        {"a": a, "b": b, "w": w}
        for (a, b), w in sorted(pair.items())
    ]


def main():
    known = set(CATEGORIES)
    for _, members in CLUSTERS:
        for m in members:
            assert m in known, f"cluster member not a CategoryPool value: {m}"
    for a, b, _ in CROSS_LINKS:
        assert a in known and b in known, f"cross-link not a CategoryPool value: {a},{b}"

    affinities = build_affinities()

    # Every category must have at least one neighbor, else the surrogate sees it in isolation.
    covered = set()
    for e in affinities:
        covered.add(e["a"])
        covered.add(e["b"])
    missing = known - covered
    assert not missing, f"categories with no affinity neighbor: {sorted(missing)}"

    doc = {
        "version": 1,
        "provenance": PROVENANCE,
        "model": MODEL,
        "affinities": affinities,
    }

    with open(OUT_PATH, "w") as f:
        json.dump(doc, f, indent=2)
        f.write("\n")

    print(f"wrote {OUT_PATH}: {len(affinities)} affinity pairs over {len(CATEGORIES)} categories")


if __name__ == "__main__":
    main()

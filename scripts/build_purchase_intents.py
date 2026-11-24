#!/usr/bin/env python3
"""Build app/src/main/assets/purchase_intents.json and check the landing pages' tags (#322 item 7).

Advertisers' landing pages in the crawl lists carry an `intent`: the IAB Audience Taxonomy 1.1
purchase-intent node a visit there signals. Segments are bought and sold by these nodes. This
script names every node the crawl lists use, so the app's log can show it, and fails if a page is
tagged with a node that is not a purchase-intent node or that falls under an excluded one.

The exclusions are the owner's ruling: no landing page whose purchase intent is sensitive, since a
decoy segment attaches to the real user's device. The taxonomy has nodes for health and medical
services, pharmaceuticals, pregnancy and baby products, debt and payday loans, legal services,
religious items and funerals, listed in EXCLUDED. Alcohol, tobacco, gambling, firearms, adult
products and dating have no nodes of their own, and are kept out by curation and by the app's
SensitivePurchases word check, which a unit test also runs over every landing URL.

IAB Tech Lab's taxonomies are licensed CC BY 3.0:
https://github.com/InteractiveAdvertisingBureau/Taxonomies (Audience Taxonomies/Audience Taxonomy 1.1.tsv)

Only the standard library is used.

Usage:
  python3 scripts/build_purchase_intents.py --iab "Audience Taxonomy 1.1.tsv"
      [--assets app/src/main/assets] [--out app/src/main/assets/purchase_intents.json]
"""

from __future__ import annotations

import argparse
import csv
import glob
import json
import os
import sys

PURCHASE_INTENT_ROOT = "752"

# Excluded subtrees (the owner's ruling, #322 item 7): a page may not be tagged with one of these
# nodes or anything under it.
EXCLUDED = {
    "762": "Apps > Health and Fitness Apps",
    "765": "Apps > Medical Apps",
    "894": "Business and Industrial > Medical and Biotechnology",
    "925": "Clothing and Accessories > Clothing > Maternity Clothing",
    "1034": "Consumer Packaged Goods > Edible > Frozen > Other Frozen > Baby Food",
    "1037": "Consumer Packaged Goods > Edible > General Food > Baby Food",
    "1344": "Consumer Packaged Goods > Non-edible > Religious Items",
    "1346": "Consumer Packaged Goods > Non-edible > Baby and Toddler Products",
    "1387": "Finance and Insurance > Credit and Debt Repair/Credit Reporting",
    "1393": "Finance and Insurance > Insurance > Medical Insurance",
    "1395": "Finance and Insurance > Payday and Emergency Loans",
    "1408": "Furniture > Baby and Toddler Furniture",
    "1448": "Health and Medical Services",
    "1493": "Legal Services",
    "1498": "Life Events > Baby Showers",
    "1501": "Life Events > Births",
    "1502": "Life Events > Funeral Supplies and Services",
    "1555": "Pharmaceuticals, Conditions, and Symptoms",
}

SOURCE = (
    "IAB Tech Lab Audience Taxonomy 1.1, purchase-intent nodes, CC BY 3.0, "
    "https://github.com/InteractiveAdvertisingBureau/Taxonomies"
)


def load_taxonomy(path: str) -> dict[str, tuple[str, str]]:
    """Purchase-intent node id -> (parent id, name below the purchase-intent root)."""
    nodes: dict[str, tuple[str, str]] = {}
    with open(path, encoding="utf-8", newline="") as f:
        rows = csv.reader(f, delimiter="\t")
        next(rows)
        for row in rows:
            if len(row) < 6 or not row[4].startswith("Purchase Intent"):
                continue
            name = " > ".join(x for x in row[5:11] if x and not x.startswith("See *"))
            nodes[row[1]] = (row[2], name)
    return nodes


def ancestors(node: str, nodes: dict[str, tuple[str, str]]) -> list[str]:
    """[node]'s ancestors below the purchase-intent root, nearest first."""
    out = []
    parent = nodes[node][0]
    while parent in nodes and parent != PURCHASE_INTENT_ROOT:
        out.append(parent)
        parent = nodes[parent][0]
    return out


def crawl_intents(assets: str) -> dict[str, list[str]]:
    """Each intent the crawl lists use -> the URLs tagged with it."""
    paths = [os.path.join(assets, "crawl_urls.json")] + sorted(glob.glob(os.path.join(assets, "crawl_urls", "*.json")))
    used: dict[str, list[str]] = {}
    for path in paths:
        with open(path, encoding="utf-8") as f:
            for entry in json.load(f):
                if "intent" in entry:
                    used.setdefault(str(entry["intent"]), []).append(entry["url"])
    return used


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--iab", required=True, help="Audience Taxonomy 1.1.tsv")
    parser.add_argument("--assets", default="app/src/main/assets")
    parser.add_argument("--out", default="app/src/main/assets/purchase_intents.json")
    args = parser.parse_args()

    nodes = load_taxonomy(args.iab)
    for node, name in EXCLUDED.items():
        if node not in nodes or nodes[node][1] != name:
            sys.exit(f"excluded node {node} is not {name!r} in this taxonomy: {nodes.get(node)}")

    used = crawl_intents(args.assets)
    problems = []
    for node, urls in sorted(used.items(), key=lambda kv: int(kv[0])):
        if node not in nodes or node == PURCHASE_INTENT_ROOT:
            problems.append(f"{node}: not a purchase-intent node ({urls[0]})")
        elif {node, *ancestors(node, nodes)} & EXCLUDED.keys():
            problems.append(f"{node} {nodes[node][1]}: excluded ({', '.join(urls)})")
    if problems:
        sys.exit("landing pages with a disallowed intent:\n  " + "\n  ".join(problems))

    asset = {
        "version": 1,
        "source": SOURCE,
        "excluded": {node: name for node, name in sorted(EXCLUDED.items(), key=lambda kv: int(kv[0]))},
        "nodes": {
            node: {"name": nodes[node][1], "ancestors": [int(a) for a in ancestors(node, nodes)]}
            for node in sorted(used, key=int)
        },
    }
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(asset, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print(f"{sum(len(u) for u in used.values())} landing pages, {len(used)} purchase-intent nodes -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

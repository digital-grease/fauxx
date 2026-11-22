#!/usr/bin/env python3
"""Build app/src/main/assets/site_popularity.json from a Tranco list (#322 item 8).

The crawl lists say which sites Fauxx may visit; this asset says how popular each one is on
the real web, so the engine can draw sites the way people visit them, the big ones often and
the small ones rarely, instead of evenly.

Tranco (https://tranco-list.eu, Le Pochat et al., NDSS 2019) ranks the top million registrable
domains by combining several traffic sources, and is free to use with that citation. The
asset keeps only the domains the crawl lists use, keyed by the Tranco domain that matched:
the longest suffix of a crawl host that is on the list (www.bbc.co.uk matches bbc.co.uk). A
host that matches nothing is left out, and the app treats it as less popular than anything on
the list.

Only the standard library is used.

Usage:
  python3 scripts/build_site_popularity.py --tranco top-1m.csv --list-id 46YLX
      [--assets app/src/main/assets] [--out app/src/main/assets/site_popularity.json]

The CSV is Tranco's download: one `rank,domain` line per domain. Get the latest from
https://tranco-list.eu/api/lists/date/latest (its "download" URL) and pass its list id.
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import sys
from urllib.parse import urlsplit


def corpus_hosts(assets: str) -> set[str]:
    paths = [os.path.join(assets, "crawl_urls.json")] + sorted(glob.glob(os.path.join(assets, "crawl_urls", "*.json")))
    hosts: set[str] = set()
    for path in paths:
        with open(path, encoding="utf-8") as f:
            for entry in json.load(f):
                host = (urlsplit(entry.get("url", "")).hostname or "").lower()
                if host:
                    hosts.add(host)
    return hosts


def load_tranco(path: str) -> dict[str, int]:
    ranks: dict[str, int] = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            rank, _, domain = line.strip().partition(",")
            if domain and rank.isdigit():
                ranks.setdefault(domain.lower(), int(rank))
    return ranks


def match(host: str, ranks: dict[str, int]) -> str | None:
    """The longest suffix of [host] on the list, dropping leading labels one at a time."""
    labels = host.split(".")
    for i in range(len(labels) - 1):
        candidate = ".".join(labels[i:])
        if candidate in ranks:
            return candidate
    return None


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tranco", required=True, help="Tranco CSV (rank,domain)")
    ap.add_argument("--list-id", required=True, help="Tranco list id, recorded in the asset")
    ap.add_argument("--assets", default="app/src/main/assets")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    ranks = load_tranco(args.tranco)
    hosts = corpus_hosts(args.assets)
    matched: dict[str, int] = {}
    unmatched = []
    for host in sorted(hosts):
        domain = match(host, ranks)
        if domain is None:
            unmatched.append(host)
        else:
            matched[domain] = ranks[domain]

    out = args.out or os.path.join(args.assets, "site_popularity.json")
    asset = {
        "source": f"Tranco list {args.list_id} (https://tranco-list.eu/list/{args.list_id}/1000000), "
                  "Le Pochat et al., NDSS 2019",
        "ranks": dict(sorted(matched.items())),
    }
    with open(out, "w", encoding="utf-8") as f:
        json.dump(asset, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"{len(hosts)} crawl hosts: {len(hosts) - len(unmatched)} ranked as {len(matched)} domains, "
          f"{len(unmatched)} not on the list", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())

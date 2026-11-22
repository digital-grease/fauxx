#!/usr/bin/env python3
"""Build app/src/main/assets/category_popularity.json from SimilarWeb industry data (#322 item 8).

Layer 0 of the targeting engine weights each category by how much of the real web's browsing it
gets, so the decoy mix leans the way real browsing does instead of spreading evenly over every
topic. This script measures that: each Fauxx category's share of monthly visits, summed over the
SimilarWeb industries that map onto it.

Data: SimilarWeb's estimates of monthly visits per domain (August to October 2023, 1.3 million
domain rows, up to 10,000 per industry), published as open data with H. S. Xavier, "The Web
unpacked: a quantitative analysis of global Web usage" (arXiv:2404.17095, 2024),
https://github.com/cewebbr/web-unpacked (file data/cleaned/similar-web_full-rank_2023-ago-out.zip).
The asset keeps only the per-category totals.

Industries with no Fauxx category are left out, so the shares are of topical browsing only:
search engines, social networks, news, adult sites, e-mail, gambling, marketplaces and generic
technology services. SimilarWeb files sites by industry rather than by audience, so categories
that are audiences rather than industries (retirement, parenting, alternative wellness) come
out low. The app takes the square root of these shares, which damps that error as well as the
skew (see CategoryPopularityLayer).

Only the standard library is used.

Usage:
  python3 scripts/build_category_popularity.py --similarweb similar-web_full-rank_2023-ago-out.zip
      [--out app/src/main/assets/category_popularity.json]

--similarweb takes the zip or the CSV inside it.
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import sys
import zipfile

# The SimilarWeb industries counted toward each Fauxx category (CategoryPool). An industry
# belongs to at most one category; one not listed counts toward none.
INDUSTRIES: dict[str, list[str]] = {
    "MEDICAL": [
        "Health - Other", "Medicine", "Pharmacy", "Health Conditions and Concerns", "Women's Health",
        "Dentist and Dental Services", "Mental Health", "Public Health and Safety",
        "Developmental and Physical Disabilities", "Men's Health", "Biotechnology and Pharmaceuticals",
        "Addictions",
    ],
    "LEGAL": ["Legal", "Immigration and Visas"],
    "AUTOMOTIVE": ["Vehicles - Other", "Automotive Industry", "Makes and Models", "Motorcycles", "Motorsports"],
    "PARENTING": ["Childcare", "Children's Health"],
    "RETIREMENT": ["Geriatric and Aging Care"],
    "GAMING": [
        "Video Games Consoles and Accessories", "Games - Other", "Roleplaying Games", "Board and Card Games",
        "Puzzles and Brainteasers",
    ],
    "AGRICULTURE": ["Agriculture"],
    "FASHION": ["Fashion and Apparel", "Jewelry and Luxury Products"],
    "ACADEMIC": [
        "Education", "Universities and Colleges", "Math", "Social Sciences",
        "Grants Scholarships and Financial Aid", "Business Training", "Literature", "Philosophy",
    ],
    "REAL_ESTATE": ["Real Estate", "Relocation and Household Moving"],
    "COOKING": ["Cooking and Recipes", "Vegetarian and Vegan"],
    "SPORTS": [
        "Sports - Other", "Soccer", "Baseball", "American Football", "Basketball", "Golf", "Martial Arts",
        "Tennis", "Fantasy Sports", "Rugby", "Volleyball", "Boxing", "Extreme Sports",
    ],
    "FINANCE": [
        "Banking Credit and Lending", "Finance - Other", "Investing", "Financial Planning and Management",
        "Insurance", "Accounting and Auditing",
    ],
    "TRAVEL": [
        "Travel and Tourism - Other", "Accommodation and Hotels", "Air Travel", "Ground Transportation",
        "Tourist Attractions", "Car Rentals", "Transportation and Excursions",
    ],
    "TECHNOLOGY": [
        "Consumer Electronics", "Computer Security", "Computer Hardware", "Programming and Developer Software",
        "Graphics Multimedia and Web Design", "Web Hosting and Domain Names",
    ],
    "PETS": ["Pet Food and Supplies", "Pets and Animals - Other", "Pets", "Fish and Aquaria", "Birds", "Animals", "Horses"],
    "HOME_IMPROVEMENT": [
        "Home Improvement and Maintenance", "Home and Garden - Other", "Furniture", "Gardening", "Interior Design",
        "Construction and Maintenance",
    ],
    "BEAUTY": ["Beauty and Cosmetics"],
    "MUSIC": ["Music"],
    "FITNESS": ["Nutrition Diets and Fitness", "Running", "Cycling and Biking"],
    "ENTERTAINMENT": [
        "TV Movies and Streaming", "Animation and Comics", "Arts and Entertainment - Other", "Humor",
        "Books and Literature", "Performing Arts", "Visual Arts and Design",
    ],
    "FOOD": ["Food and Drink - Other", "Restaurants and Delivery", "Groceries", "Beverages"],
    "POLITICS": ["Government", "Law and Government - Other"],
    "SCIENCE": ["Biology", "Chemistry", "Physics", "Astronomy", "Earth Sciences", "Science and Education - Other"],
    "BUSINESS": [
        "Business Services", "Business and Consumer Services - Other", "Marketing and Advertising",
        "Online Marketing", "Human Resources", "Publishing and Printing", "Jobs and Employment",
    ],
    "OUTDOOR_RECREATION": [
        "Camping Scouting and Outdoors", "Hunting and Shooting", "Fishing", "Climbing", "Water Sports",
        "Winter Sports",
    ],
    "CRAFTS": ["Crafts", "Models", "Photography"],
    "HISTORY": ["History", "Libraries and Museums", "Ancestry and Genealogy", "Antiques and Collectibles"],
    "ENVIRONMENT": ["Environmental Science", "Waste Water and Environmental", "Energy Industry"],
    "MILITARY_DEFENSE": ["National Security", "Aerospace and Defense"],
    "WELLNESS_ALTERNATIVE": ["Alternative and Natural Medicine"],
    "RELATIONSHIPS_DATING": ["Romance and Relationships", "Weddings"],
}

SOURCE = (
    "SimilarWeb monthly visits per domain, August to October 2023, as published with H. S. Xavier, "
    "\"The Web unpacked: a quantitative analysis of global Web usage\" (arXiv:2404.17095), "
    "https://github.com/cewebbr/web-unpacked"
)
MEASURE = (
    "Each category's share of the monthly visits to the SimilarWeb industries listed for it, among all "
    "listed industries. Industries with no Fauxx category (search engines, social networks, news, adult, "
    "e-mail, gambling, marketplaces, generic technology services) are left out."
)


def open_rows(path: str):
    if path.endswith(".zip"):
        archive = zipfile.ZipFile(path)
        name = next(n for n in archive.namelist() if n.endswith(".csv") and "metadata" not in n)
        return csv.DictReader(io.TextIOWrapper(archive.open(name), encoding="utf-8", newline=""))
    return csv.DictReader(open(path, encoding="utf-8", newline=""))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--similarweb", required=True, help="the web-unpacked full-rank zip, or its CSV")
    parser.add_argument("--out", default="app/src/main/assets/category_popularity.json")
    args = parser.parse_args()

    category_of: dict[str, str] = {}
    for category, industries in INDUSTRIES.items():
        for industry in industries:
            if industry in category_of:
                sys.exit(f"{industry!r} is listed under both {category_of[industry]} and {category}")
            category_of[industry] = category

    visits = {category: 0.0 for category in INDUSTRIES}
    seen: set[str] = set()
    rows = 0
    for row in open_rows(args.similarweb):
        rows += 1
        industry = row.get("industry", "")
        seen.add(industry)
        category = category_of.get(industry)
        if category is None:
            continue
        try:
            visits[category] += float(row.get("monthly_visits") or 0.0)
        except ValueError:
            pass

    missing = sorted(set(category_of) - seen)
    if missing:
        sys.exit(f"industries not in the data (renamed?): {missing}")
    total = sum(visits.values())
    if total <= 0:
        sys.exit("no visits counted")

    asset = {
        "version": 1,
        "source": SOURCE,
        "measure": MEASURE,
        "shares": {category: float(f"{visits[category] / total:.6g}") for category in INDUSTRIES},
        "industries": INDUSTRIES,
    }
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(asset, f, indent=1, ensure_ascii=False)
        f.write("\n")

    print(f"{rows} rows, {total:.4g} monthly visits in mapped industries -> {args.out}")
    for category in sorted(visits, key=visits.get, reverse=True):
        print(f"  {category:22s} {visits[category] / total * 100:6.2f}%")
    return 0


if __name__ == "__main__":
    sys.exit(main())

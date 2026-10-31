#!/usr/bin/env python3
"""Dependency-free sanity checks for ImperialShop's inline shops.yml format."""
from pathlib import Path
import math
import re
import sys

path = Path("paper/src/main/resources/shops.yml")
text = path.read_text(encoding="utf-8")
category_items = []
in_prices = False
price_entries = []

for line_number, line in enumerate(text.splitlines(), start=1):
    if line.strip() == "prices:":
        in_prices = True
        continue
    if not in_prices:
        match = re.match(r"^\s+items:\s*\[([^\]]*)\]\s*$", line)
        if match:
            category_items.extend(
                item.strip() for item in match.group(1).split(",") if item.strip()
            )
        continue

    match = re.match(
        r"^\s{2}([A-Z][A-Z0-9_]*):\s*\{buy:\s*([0-9]+(?:\.[0-9]+)?),\s*sell:\s*([0-9]+(?:\.[0-9]+)?)\}\s*$",
        line,
    )
    if match:
        price_entries.append(
            (match.group(1), float(match.group(2)), float(match.group(3)), line_number)
        )

errors = []
if not category_items:
    errors.append("No category items were found.")
if not price_entries:
    errors.append("No price entries were found.")

price_names = [entry[0] for entry in price_entries]
duplicates = sorted({name for name in price_names if price_names.count(name) > 1})
if duplicates:
    errors.append("Duplicate price keys: " + ", ".join(duplicates))

category_names = set(category_items)
price_name_set = set(price_names)
missing = sorted(category_names - price_name_set)
orphaned = sorted(price_name_set - category_names)
if missing:
    errors.append("Category items missing prices: " + ", ".join(missing))
if orphaned:
    errors.append("Prices not referenced by any category: " + ", ".join(orphaned))

for name, buy, sell, line_number in price_entries:
    if not math.isfinite(buy) or not math.isfinite(sell) or buy < 0 or sell < 0:
        errors.append(f"Invalid negative/non-finite price for {name} at line {line_number}.")

if errors:
    print("ImperialShop shop validation FAILED:")
    for error in errors:
        print(" - " + error)
    sys.exit(1)

print(
    f"ImperialShop shop validation passed: {len(category_names)} unique category items, "
    f"{len(price_entries)} unique price entries, no missing/orphaned prices."
)

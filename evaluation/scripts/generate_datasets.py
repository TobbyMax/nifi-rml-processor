#!/usr/bin/env python3
"""
Generate synthetic benchmark datasets for the NiFi RML processor evaluation.

Produces deterministic JSON / CSV / XML files of pre-defined sizes. Seed is fixed
so the suite is reproducible across runs and machines.

Usage:
    python generate_datasets.py [--out evaluation/datasets] [--sizes small,medium,large]
"""
from __future__ import annotations

import argparse
import csv
import json
import random
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

SIZE_PRESETS = {
    "small_100":     100,
    "medium_10k":    10_000,
    "large_50k":     50_000,
    "large_100k":    100_000,
    "large_500k":    500_000,
    "xlarge_2m":     2_000_000,
    "invoices_100":  100,
    "invoices_1k":   1_000,
    "invoices_5k":   5_000,
    "invoices_10k":  10_000,
}

FIRST_NAMES = ["Ivanov", "Petrov", "Sidorov", "Kuznetsov", "Smirnov",
               "Volkov", "Lebedev", "Pavlov", "Egorov", "Romanov"]
CITIES = ["Moscow", "Saint Petersburg", "Kazan", "Novosibirsk", "Yekaterinburg",
          "Nizhny Novgorod", "Krasnodar", "Samara", "Ufa", "Chelyabinsk"]


def make_record(rng: random.Random, idx: int) -> dict:
    name = rng.choice(FIRST_NAMES) + f" {chr(rng.randint(65, 90))}.{chr(rng.randint(65, 90))}."
    return {
        "id": idx,
        "name": name,
        "email": f"user{idx}@example.com",
        "city": rng.choice(CITIES),
        "loyalty": rng.randint(0, 4500),
    }


def write_json(path: Path, count: int, rng: random.Random) -> None:
    with path.open("w", encoding="utf-8") as f:
        f.write("[\n")
        for i in range(count):
            rec = make_record(rng, i + 1)
            sep = "," if i < count - 1 else ""
            f.write("  " + json.dumps(rec, ensure_ascii=False) + sep + "\n")
        f.write("]\n")


def write_csv(path: Path, count: int, rng: random.Random) -> None:
    with path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=["id", "name", "email", "city", "loyalty"])
        writer.writeheader()
        for i in range(count):
            writer.writerow(make_record(rng, i + 1))


def write_xml_invoices(path: Path, count: int, rng: random.Random) -> None:
    # Streaming write to avoid building a giant ElementTree in memory.
    with path.open("w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n<invoices>\n')
        for i in range(count):
            customer_id = rng.randint(1, max(count // 5, 1))
            amount = round(rng.uniform(1000, 250000), 2)
            f.write(
                f'  <invoice id="INV-{i+1:08d}">\n'
                f'    <customer><id>{customer_id}</id></customer>\n'
                f'    <amount currency="RUB">{amount}</amount>\n'
                f'    <issued>2026-{(i % 12) + 1:02d}-{(i % 28) + 1:02d}</issued>\n'
                f'  </invoice>\n'
            )
        f.write('</invoices>\n')


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", default="evaluation/datasets")
    parser.add_argument("--sizes", default=",".join(SIZE_PRESETS.keys()))
    parser.add_argument("--seed", type=int, default=2026)
    args = parser.parse_args(argv[1:])

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    rng = random.Random(args.seed)

    requested = [s.strip() for s in args.sizes.split(",") if s.strip()]
    for preset in requested:
        if preset not in SIZE_PRESETS:
            print(f"unknown preset: {preset}", file=sys.stderr)
            return 2
        count = SIZE_PRESETS[preset]
        if preset.startswith("invoices_"):
            target = out / f"{preset}.xml"
            print(f"writing {target} ({count} invoices)")
            write_xml_invoices(target, count, random.Random(args.seed))
        else:
            target_json = out / f"{preset}.json"
            target_csv = out / f"{preset}.csv"
            print(f"writing {target_json} ({count} records)")
            write_json(target_json, count, random.Random(args.seed))
            print(f"writing {target_csv} ({count} records)")
            write_csv(target_csv, count, random.Random(args.seed))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

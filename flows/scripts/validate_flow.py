#!/usr/bin/env python3
"""
Validate a flow blueprint against the agreed schema:

  {
    "name": str,
    "description": str,
    "processGroupName": str,
    "processors": [ { "id": str, "type": str, "name": str, "properties": {} }, ... ],
    "connections": [ { "from": str, "fromRel": str, "to": str }, ... ]
  }

Each connection's `from`/`to` must reference an existing processor `id`.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

REQUIRED_TOP_KEYS = {"name", "processGroupName", "processors", "connections"}
REQUIRED_PROCESSOR_KEYS = {"id", "type", "name", "properties"}
REQUIRED_CONNECTION_KEYS = {"from", "fromRel", "to"}


def validate(path: Path) -> list[str]:
    errors: list[str] = []
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        return [f"{path}: invalid JSON ({exc})"]

    missing = REQUIRED_TOP_KEYS - data.keys()
    if missing:
        errors.append(f"{path}: missing keys {sorted(missing)}")

    processor_ids: set[str] = set()
    for idx, proc in enumerate(data.get("processors", [])):
        missing = REQUIRED_PROCESSOR_KEYS - proc.keys()
        if missing:
            errors.append(f"{path}: processors[{idx}] missing {sorted(missing)}")
            continue
        if proc["id"] in processor_ids:
            errors.append(f"{path}: duplicate processor id '{proc['id']}'")
        processor_ids.add(proc["id"])
        if not isinstance(proc["properties"], dict):
            errors.append(f"{path}: processors[{idx}].properties must be an object")

    for idx, conn in enumerate(data.get("connections", [])):
        missing = REQUIRED_CONNECTION_KEYS - conn.keys()
        if missing:
            errors.append(f"{path}: connections[{idx}] missing {sorted(missing)}")
            continue
        for endpoint in ("from", "to"):
            if conn[endpoint] not in processor_ids:
                errors.append(
                    f"{path}: connections[{idx}].{endpoint} '{conn[endpoint]}' "
                    f"is not a known processor id"
                )
    return errors


def main(argv: list[str]) -> int:
    paths = [Path(p) for p in argv[1:]] if len(argv) > 1 else sorted(
        Path(__file__).resolve().parent.parent.glob("flow_*.json")
    )
    if not paths:
        print("no flow files found", file=sys.stderr)
        return 2

    failures = 0
    for path in paths:
        errors = validate(path)
        if errors:
            failures += 1
            for err in errors:
                print(err, file=sys.stderr)
        else:
            print(f"{path}: ok")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

#!/usr/bin/env python3
"""
Import a flow blueprint into a running Apache NiFi 2.x instance via REST API.

Usage:
    python import_blueprint.py --base-url http://localhost:8080/nifi-api \\
        --token "$NIFI_TOKEN" flows/flow_json_to_rdf.json

The script creates:
  1. A new Process Group at the root canvas with the given processGroupName.
  2. One processor per blueprint entry, with all properties applied.
  3. Connections between processors as declared.

Requires Python 3.10+ and the `requests` package.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

try:
    import requests
except ImportError:
    print("Please `pip install requests` first", file=sys.stderr)
    sys.exit(2)


class NiFiClient:
    def __init__(self, base_url: str, token: str | None) -> None:
        self.base_url = base_url.rstrip("/")
        self.session = requests.Session()
        if token:
            self.session.headers["Authorization"] = f"Bearer {token}"

    def _post(self, path: str, payload: dict[str, Any]) -> dict[str, Any]:
        response = self.session.post(f"{self.base_url}{path}", json=payload, timeout=30)
        response.raise_for_status()
        return response.json()

    def root_id(self) -> str:
        response = self.session.get(f"{self.base_url}/process-groups/root", timeout=10)
        response.raise_for_status()
        return response.json()["id"]

    def create_process_group(self, parent_id: str, name: str) -> str:
        payload = {
            "revision": {"version": 0},
            "component": {"name": name, "position": {"x": 0, "y": 0}},
        }
        return self._post(f"/process-groups/{parent_id}/process-groups", payload)["id"]

    def create_processor(self, group_id: str, blueprint: dict[str, Any], pos: dict) -> str:
        payload = {
            "revision": {"version": 0},
            "component": {
                "name": blueprint["name"],
                "type": blueprint["type"],
                "position": pos,
                "config": {"properties": blueprint["properties"]},
            },
        }
        return self._post(f"/process-groups/{group_id}/processors", payload)["id"]

    def create_connection(self, group_id: str, src_id: str, dst_id: str, rel: str) -> str:
        payload = {
            "revision": {"version": 0},
            "component": {
                "source": {"id": src_id, "groupId": group_id, "type": "PROCESSOR"},
                "destination": {"id": dst_id, "groupId": group_id, "type": "PROCESSOR"},
                "selectedRelationships": [rel],
            },
        }
        return self._post(f"/process-groups/{group_id}/connections", payload)["id"]


def import_blueprint(blueprint_path: Path, client: NiFiClient) -> str:
    blueprint = json.loads(blueprint_path.read_text(encoding="utf-8"))
    root_id = client.root_id()
    pg_id = client.create_process_group(root_id, blueprint["processGroupName"])
    print(f"created process group {pg_id} ({blueprint['processGroupName']})")

    proc_ids: dict[str, str] = {}
    for idx, proc in enumerate(blueprint["processors"]):
        pos = {"x": 200.0 * idx, "y": 200.0}
        nifi_id = client.create_processor(pg_id, proc, pos)
        proc_ids[proc["id"]] = nifi_id
        print(f"  processor {proc['id']} -> {nifi_id} ({proc['type']})")

    for conn in blueprint["connections"]:
        client.create_connection(
            pg_id, proc_ids[conn["from"]], proc_ids[conn["to"]], conn["fromRel"]
        )
        print(f"  conn {conn['from']} --{conn['fromRel']}--> {conn['to']}")

    return pg_id


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("blueprints", nargs="+", help="Blueprint JSON file(s)")
    parser.add_argument("--base-url", default="http://localhost:8080/nifi-api")
    parser.add_argument("--token", default=None, help="Bearer token (NiFi access token)")
    args = parser.parse_args(argv[1:])

    client = NiFiClient(args.base_url, args.token)
    for path in args.blueprints:
        import_blueprint(Path(path), client)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

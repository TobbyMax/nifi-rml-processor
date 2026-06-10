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
import time
from pathlib import Path
from typing import Any

try:
    import requests
    from urllib3.exceptions import InsecureRequestWarning
except ImportError:
    print("Please `pip install requests` first", file=sys.stderr)
    sys.exit(2)

requests.packages.urllib3.disable_warnings(InsecureRequestWarning)


class NiFiClient:
    def __init__(self, base_url: str, token: str | None, verify_ssl: bool = False) -> None:
        self.base_url = base_url.rstrip("/")
        self.session = requests.Session()
        self.session.verify = verify_ssl
        if token:
            self.session.headers["Authorization"] = f"Bearer {token}"

    def _request_with_retry(
        self, method: str, path: str, max_retries: int = 3, **kwargs
    ) -> requests.Response:
        url = f"{self.base_url}{path}"
        last_error = None

        for attempt in range(max_retries):
            try:
                if method == "GET":
                    response = self.session.get(url, **kwargs)
                elif method == "POST":
                    response = self.session.post(url, **kwargs)
                else:
                    raise ValueError(f"Unsupported method: {method}")

                if response.status_code == 409:
                    print(f"WARNING: Conflict (409) - {url}", file=sys.stderr)
                    return response

                response.raise_for_status()
                return response
            except (requests.exceptions.ConnectionError, requests.exceptions.Timeout) as e:
                last_error = e
                if attempt < max_retries - 1:
                    wait_time = 2 ** attempt
                    print(f"Connection failed (attempt {attempt + 1}/{max_retries}), retrying in {wait_time}s...", file=sys.stderr)
                    time.sleep(wait_time)

        raise ConnectionError(
            f"Failed to connect to NiFi at {self.base_url}\n"
            f"Make sure NiFi is running and accessible.\n"
            f"Error: {last_error}"
        )

    def check_health(self) -> bool:
        try:
            self._request_with_retry("GET", "/system-diagnostics", timeout=5, max_retries=1)
            return True
        except (ConnectionError, requests.exceptions.RequestException):
            print("Connection to NiFi failed. Please check if NiFi is running.", file=sys.stderr)
            return False

    def _post(self, path: str, payload: dict[str, Any]) -> dict[str, Any] | None:
        response = self._request_with_retry(
            "POST", path, json=payload, timeout=30, max_retries=2
        )
        if response.status_code == 409:
            print(f"  409 Response body:", file=sys.stderr)
            try:
                error_data = response.json()
                print(f"  {json.dumps(error_data, indent=2)}", file=sys.stderr)
            except Exception as e:
                print(f"  {response.text}", file=sys.stderr)
            return None
        try:
            result = response.json()
            print(f"  DEBUG: POST response keys: {list(result.keys())}", file=sys.stderr)
            return result
        except Exception as e:
            print(f"  ERROR parsing response: {e}", file=sys.stderr)
            print(f"  Response: {response.text}", file=sys.stderr)
            raise

    def _get_processors(self, group_id: str) -> list[dict[str, Any]]:
        response = self._request_with_retry(
            "GET", f"/process-groups/{group_id}/processors", timeout=10, max_retries=2
        )
        return response.json().get("processors", [])

    def _get_processor_by_name(self, group_id: str, name: str) -> str | None:
        processors = self._get_processors(group_id)
        found_names = [proc.get("component", {}).get("name") for proc in processors]
        for proc in processors:
            if proc.get("component", {}).get("name") == name:
                return proc.get("id")
        print(f"  Could not find processor '{name}' in group {group_id}", file=sys.stderr)
        print(f"  Available processors: {found_names}", file=sys.stderr)
        return None

    def _get_process_groups(self, parent_id: str) -> list[dict[str, Any]]:
        response = self._request_with_retry(
            "GET", f"/process-groups/{parent_id}/process-groups", timeout=10, max_retries=2
        )
        return response.json().get("processGroups", [])

    def _get_process_group_by_name(self, parent_id: str, name: str) -> str | None:
        groups = self._get_process_groups(parent_id)
        for group in groups:
            if group.get("component", {}).get("name") == name:
                return group.get("id")
        return None

    def root_id(self) -> str:
        response = self._request_with_retry(
            "GET", "/process-groups/root", timeout=10, max_retries=3
        )
        return response.json()["id"]

    def create_process_group(self, parent_id: str, name: str) -> str:
        payload = {
            "revision": {"version": 0},
            "component": {"name": name, "position": {"x": 0, "y": 0}},
        }
        result = self._post(f"/process-groups/{parent_id}/process-groups", payload)
        if result is None:
            existing_id = self._get_process_group_by_name(parent_id, name)
            if existing_id:
                print(f"Process group '{name}' already exists (id: {existing_id})", file=sys.stderr)
                return existing_id
            raise RuntimeError(f"Failed to create process group '{name}'")
        pg_id = result["id"]
        print(f"Created process group with ID: {pg_id}", file=sys.stderr)
        return pg_id

    def create_processor(self, group_id: str, blueprint: dict[str, Any], pos: dict) -> str:
        config: dict[str, Any] = {"properties": blueprint["properties"]}
        auto_term = blueprint.get("autoTerminatedRelationships")
        if auto_term:
            config["autoTerminatedRelationships"] = list(auto_term)
        payload = {
            "revision": {"version": 0},
            "component": {
                "name": blueprint["name"],
                "type": blueprint["type"],
                "position": pos,
                "config": config,
            },
        }
        print(f"  Creating processor '{blueprint['name']}' in group {group_id}...", file=sys.stderr)
        result = self._post(f"/process-groups/{group_id}/processors", payload)
        if result is None:
            print(f"  Searching for existing processor '{blueprint['name']}' in group {group_id}...", file=sys.stderr)
            existing_id = self._get_processor_by_name(group_id, blueprint["name"])
            if existing_id:
                print(f"  processor {blueprint['name']} already exists (id: {existing_id})", file=sys.stderr)
                return existing_id
            raise RuntimeError(f"Failed to create processor {blueprint['name']}")
        return result["id"]

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
    parser.add_argument("--verify-ssl", action="store_true", help="Verify SSL certificates (default: disabled)")
    args = parser.parse_args(argv[1:])

    client = NiFiClient(args.base_url, args.token, verify_ssl=args.verify_ssl)

    print(f"Connecting to NiFi at {args.base_url}...", file=sys.stderr)
    if not client.check_health():
        print(
            f"ERROR: NiFi is not accessible at {args.base_url}\n"
            f"Check if NiFi is running: docker-compose -f docker-compose.demo.yml up -d",
            file=sys.stderr
        )
        return 1

    print("NiFi is healthy, proceeding with blueprint import.", file=sys.stderr)
    for path in args.blueprints:
        import_blueprint(Path(path), client)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

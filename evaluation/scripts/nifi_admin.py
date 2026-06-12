#!/usr/bin/env python3
"""
NiFi REST helpers used by the benchmark harness.

Subcommands:

  solo-pg <name>
      Stop every Process Group under the root canvas except the one named
      `<name>`. Used before benchmarking to make sure other demo flows don't
      consume files dropped into the benchmark watch directory.

  set-properties <pg_name> <processor_name> --prop key=value [--prop ...]
      Locate a processor by name inside the named Process Group, stop it,
      merge the given properties into its config (preserving existing
      properties), then start it again. Waits for state transitions to
      complete so the next benchmark iteration sees the updated config.

Both subcommands accept --base-url and --token. SSL verification is disabled
to match the rest of the harness (NiFi 2.x defaults to a self-signed cert).
"""
from __future__ import annotations

import argparse
import sys
import time
from typing import Any

try:
    import requests
    from urllib3.exceptions import InsecureRequestWarning
    requests.packages.urllib3.disable_warnings(InsecureRequestWarning)
except ImportError:
    print("Please `pip install requests` first", file=sys.stderr)
    sys.exit(2)


class NiFiClient:
    def __init__(self, base_url: str, token: str | None) -> None:
        self.base_url = base_url.rstrip("/")
        self.session = requests.Session()
        self.session.verify = False
        if token:
            self.session.headers["Authorization"] = f"Bearer {token}"

    def _get(self, path: str) -> dict[str, Any]:
        resp = self.session.get(f"{self.base_url}{path}", timeout=15)
        resp.raise_for_status()
        return resp.json()

    def _put(self, path: str, payload: dict[str, Any]) -> dict[str, Any]:
        resp = self.session.put(f"{self.base_url}{path}", json=payload, timeout=30)
        resp.raise_for_status()
        return resp.json()

    def root_id(self) -> str:
        return self._get("/process-groups/root")["id"]

    def process_groups(self, parent_id: str) -> list[dict[str, Any]]:
        return self._get(f"/process-groups/{parent_id}/process-groups").get("processGroups", [])

    def processors(self, group_id: str) -> list[dict[str, Any]]:
        return self._get(f"/process-groups/{group_id}/processors").get("processors", [])

    def set_pg_state(self, pg_id: str, state: str) -> None:
        # NiFi 2.x uses /flow/process-groups/{id} for state changes
        self._put(f"/flow/process-groups/{pg_id}", {
            "id": pg_id,
            "state": state,
        })

    def set_processor_state(self, proc_id: str, state: str) -> None:
        cur = self._get(f"/processors/{proc_id}")
        revision = cur["revision"]
        self._put(f"/processors/{proc_id}/run-status", {
            "revision": revision,
            "state": state,
            "disconnectedNodeAcknowledged": False,
        })

    def wait_for_processor_state(self, proc_id: str, target: str,
                                  timeout: float = 30.0) -> None:
        deadline = time.time() + timeout
        while time.time() < deadline:
            ent = self._get(f"/processors/{proc_id}")
            run_status = ent.get("component", {}).get("state", "")
            agg = ent.get("status", {}).get("aggregateSnapshot", {})
            active = agg.get("activeThreadCount", 0)
            if run_status == target and (target != "STOPPED" or active == 0):
                return
            time.sleep(0.5)
        raise TimeoutError(f"processor {proc_id} did not reach {target} in {timeout}s")

    def patch_processor_properties(self, proc_id: str, props: dict[str, str]) -> None:
        cur = self._get(f"/processors/{proc_id}")
        revision = cur["revision"]
        component = cur["component"]
        config = component.get("config", {})
        existing_props = config.get("properties") or {}
        merged = {**existing_props, **props}
        payload = {
            "revision": revision,
            "component": {
                "id": proc_id,
                "config": {"properties": merged},
            },
        }
        self._put(f"/processors/{proc_id}", payload)


def find_pg_by_name(client: NiFiClient, name: str) -> dict[str, Any] | None:
    for pg in client.process_groups(client.root_id()):
        if pg.get("component", {}).get("name") == name:
            return pg
    return None


def find_processor_by_name(client: NiFiClient, pg_id: str,
                            name: str) -> dict[str, Any] | None:
    for proc in client.processors(pg_id):
        if proc.get("component", {}).get("name") == name:
            return proc
    return None


def cmd_pg_exists(client: NiFiClient, args: argparse.Namespace) -> int:
    pg = find_pg_by_name(client, args.pg_name)
    if pg is None:
        return 1
    print(pg["id"])
    return 0


def cmd_solo_pg(client: NiFiClient, args: argparse.Namespace) -> int:
    root = client.root_id()
    target_found = False
    for pg in client.process_groups(root):
        name = pg.get("component", {}).get("name", "")
        pg_id = pg["id"]
        if name == args.pg_name:
            print(f"[solo-pg] RUNNING    {name} ({pg_id})", file=sys.stderr)
            client.set_pg_state(pg_id, "RUNNING")
            target_found = True
        else:
            print(f"[solo-pg] STOPPED    {name} ({pg_id})", file=sys.stderr)
            client.set_pg_state(pg_id, "STOPPED")
    if not target_found:
        print(f"[solo-pg] ERROR: process group {args.pg_name!r} not found", file=sys.stderr)
        return 1
    return 0


def cmd_set_properties(client: NiFiClient, args: argparse.Namespace) -> int:
    pg = find_pg_by_name(client, args.pg_name)
    if pg is None:
        print(f"[set-properties] ERROR: PG {args.pg_name!r} not found", file=sys.stderr)
        return 1
    pg_id = pg["id"]

    proc = find_processor_by_name(client, pg_id, args.processor_name)
    if proc is None:
        print(f"[set-properties] ERROR: processor {args.processor_name!r} not found in {args.pg_name!r}",
              file=sys.stderr)
        return 1
    proc_id = proc["id"]

    props: dict[str, str] = {}
    for entry in args.prop or []:
        if "=" not in entry:
            print(f"[set-properties] ERROR: bad --prop {entry!r}, expected key=value",
                  file=sys.stderr)
            return 2
        key, value = entry.split("=", 1)
        props[key] = value

    print(f"[set-properties] stopping processor {args.processor_name} ({proc_id})",
          file=sys.stderr)
    client.set_processor_state(proc_id, "STOPPED")
    client.wait_for_processor_state(proc_id, "STOPPED", timeout=30.0)

    print(f"[set-properties] patching {len(props)} properties", file=sys.stderr)
    client.patch_processor_properties(proc_id, props)

    print(f"[set-properties] starting processor {args.processor_name}", file=sys.stderr)
    client.set_processor_state(proc_id, "RUNNING")
    client.wait_for_processor_state(proc_id, "RUNNING", timeout=30.0)
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default="https://localhost:8443/nifi-api")
    parser.add_argument("--token", default="")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_exists = sub.add_parser("pg-exists",
                               help="Exit 0 with id on stdout if PG exists, else exit 1")
    p_exists.add_argument("pg_name")

    p_solo = sub.add_parser("solo-pg", help="Stop all PGs except the named one")
    p_solo.add_argument("pg_name")

    p_set = sub.add_parser("set-properties",
                            help="Patch processor properties (stop → patch → start)")
    p_set.add_argument("pg_name")
    p_set.add_argument("processor_name")
    p_set.add_argument("--prop", action="append",
                       help="key=value, repeatable")

    args = parser.parse_args(argv[1:])
    client = NiFiClient(args.base_url, args.token or None)

    if args.cmd == "pg-exists":
        return cmd_pg_exists(client, args)
    if args.cmd == "solo-pg":
        return cmd_solo_pg(client, args)
    if args.cmd == "set-properties":
        return cmd_set_properties(client, args)
    parser.error(f"unknown command {args.cmd}")
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))

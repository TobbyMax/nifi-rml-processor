#!/usr/bin/env python3
"""
Look up the `rml.triples.count` and `rml.engine.selected` attributes that
ExecuteRMLMappingProcessor wrote for a given input filename after a known
start time, by polling the NiFi provenance REST API.

Designed to be invoked from run_benchmarks.sh after the bash-side end-to-end
timer has detected the output file. Prints a single CSV fragment to stdout:

    <triples>,<engine_selected>

Both values may be the literal `NA` if no matching event is found before the
timeout. Exit code 0 = found, 1 = not found.

Usage:
    parse_results.py --base-url https://localhost:8443/nifi-api \\
        --token "$TOKEN" --filename small_100.json \\
        --start-epoch-ms 1718200000000 --timeout 30
"""
from __future__ import annotations

import argparse
import sys
import time
from datetime import datetime, timezone
from typing import Any

try:
    import requests
    from urllib3.exceptions import InsecureRequestWarning
    requests.packages.urllib3.disable_warnings(InsecureRequestWarning)
except ImportError:
    print("Please `pip install requests` first", file=sys.stderr)
    sys.exit(2)


def _format_start_date(start_epoch_ms: int) -> str:
    # NiFi provenance startDate uses "MM/dd/yyyy HH:mm:ss UTC".
    # Subtract 2 seconds to compensate for clock skew between this script and NiFi.
    safe_epoch_s = (start_epoch_ms / 1000.0) - 2.0
    dt = datetime.fromtimestamp(safe_epoch_s, tz=timezone.utc)
    return dt.strftime("%m/%d/%Y %H:%M:%S UTC")


def _submit_query(base_url: str, headers: dict[str, str], filename: str,
                  start_epoch_ms: int) -> str | None:
    payload = {
        "provenance": {
            "request": {
                "maxResults": 50,
                "summarize": False,
                "incrementalResults": False,
                "searchTerms": {
                    "Filename": {"value": filename}
                },
                "startDate": _format_start_date(start_epoch_ms)
            }
        }
    }
    deadline = time.time() + 30
    while time.time() < deadline:
        resp = requests.post(f"{base_url}/provenance", json=payload,
                             headers=headers, timeout=10, verify=False)
        if resp.status_code == 409:
            time.sleep(1.0)
            continue
        resp.raise_for_status()
        return resp.json()["provenance"]["id"]
    return None


def _poll_query(base_url: str, headers: dict[str, str], query_id: str,
                timeout: float) -> list[dict[str, Any]]:
    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(0.5)
        q = requests.get(f"{base_url}/provenance/{query_id}",
                         headers=headers, timeout=10, verify=False).json()
        if q["provenance"]["finished"]:
            return q["provenance"]["results"].get("provenanceEvents") or []
    return []


def _delete_query(base_url: str, headers: dict[str, str], query_id: str) -> None:
    try:
        requests.delete(f"{base_url}/provenance/{query_id}",
                        headers=headers, timeout=5, verify=False)
    except requests.RequestException:
        pass


def find_rml_event(base_url: str, token: str | None, filename: str,
                   start_epoch_ms: int, timeout: float) -> dict[str, Any] | None:
    """Return the first provenance event for `filename` whose attributes
    include `rml.triples.count` (meaning it was written by our processor)."""
    headers = {"Authorization": f"Bearer {token}"} if token else {}

    deadline = time.time() + timeout
    while time.time() < deadline:
        query_id = _submit_query(base_url, headers, filename, start_epoch_ms)
        if query_id is None:
            time.sleep(1.0)
            continue
        events = _poll_query(base_url, headers, query_id,
                             timeout=min(15.0, deadline - time.time()))
        _delete_query(base_url, headers, query_id)
        for ev in events:
            attrs = {a["name"]: a.get("value") for a in ev.get("attributes", [])}
            if "rml.triples.count" in attrs:
                return ev
        # No matching event yet, NiFi may still be processing — retry.
        time.sleep(1.0)
    return None


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="https://localhost:8443/nifi-api")
    parser.add_argument("--token", default="")
    parser.add_argument("--filename", required=True,
                        help="Basename of the input file to filter provenance by")
    parser.add_argument("--start-epoch-ms", type=int, required=True,
                        help="Earliest provenance event time, milliseconds since epoch")
    parser.add_argument("--timeout", type=float, default=30.0)
    args = parser.parse_args(argv[1:])

    event = find_rml_event(args.base_url, args.token or None,
                           args.filename, args.start_epoch_ms, args.timeout)
    if event is None:
        print("NA,NA")
        return 1

    triples = "NA"
    engine = "NA"
    for attr in event.get("attributes", []):
        if attr.get("name") == "rml.triples.count":
            triples = attr.get("value", "NA")
        elif attr.get("name") == "rml.engine.selected":
            engine = attr.get("value", "NA")

    print(f"{triples},{engine}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

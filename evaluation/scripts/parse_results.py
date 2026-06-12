#!/usr/bin/env python3
"""
Poll the NiFi REST API for a recently completed FlowFile and emit a
single CSV line: config, dataset, iteration, duration_ms, triples, engine_selected.

Designed to be invoked from run_benchmarks.sh between dataset uploads.

Usage:
    parse_results.py --base-url http://localhost:8080/nifi-api \\
        --token "$TOKEN" --config Baseline --dataset small_100.json --iteration 1
"""
from __future__ import annotations

import argparse
import sys
import time
from typing import Any

try:
    import requests
except ImportError:
    print("Please `pip install requests` first", file=sys.stderr)
    sys.exit(2)


def latest_provenance_event(base_url: str, token: str | None,
                             timeout: float = 60.0) -> dict[str, Any] | None:
    """Submit a provenance query asking for the most recent SUCCESS / DROP event."""
    headers = {"Authorization": f"Bearer {token}"} if token else {}

    payload = {
        "provenance": {
            "request": {
                "maxResults": 1,
                "summarize": False,
                "incrementalResults": False,
                "searchTerms": {
                    "EventType": {"value": "DROP"}
                }
            }
        }
    }
    
    # Retry on 409 Conflict (previous query still running)
    query_id = None
    deadline = time.time() + 30
    while time.time() < deadline and query_id is None:
        try:
            resp = requests.post(f"{base_url}/provenance", json=payload,
                                 headers=headers, timeout=10, verify=False)
            if resp.status_code == 409:
                time.sleep(2)
                continue
            resp.raise_for_status()
            query_id = resp.json()["provenance"]["id"]
        except requests.exceptions.HTTPError as e:
            if e.response.status_code == 409:
                time.sleep(2)
                continue
            raise
    
    if query_id is None:
        return None

    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(1.0)
        q = requests.get(f"{base_url}/provenance/{query_id}",
                         headers=headers, timeout=10, verify=False).json()
        if q["provenance"]["finished"]:
            results = q["provenance"]["results"].get("provenanceEvents") or []
            return results[0] if results else None
    return None


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080/nifi-api")
    parser.add_argument("--token", default="")
    parser.add_argument("--config", required=True)
    parser.add_argument("--dataset", required=True)
    parser.add_argument("--iteration", type=int, required=True)
    parser.add_argument("--timeout", type=float, default=120.0)
    args = parser.parse_args(argv[1:])

    event = latest_provenance_event(args.base_url, args.token, args.timeout)
    if not event:
        print(f"{args.config},{args.dataset},{args.iteration},NA,NA,NA")
        return 1

    duration = event.get("eventDuration", "NA")
    triples = "NA"
    engine = "NA"
    for attr in event.get("attributes", []):
        if attr.get("name") == "rml.triples.count":
            triples = attr.get("value", "NA")
        if attr.get("name") == "rml.engine.selected":
            engine = attr.get("value", "NA")

    print(f"{args.config},{args.dataset},{args.iteration},{duration},{triples},{engine}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

#!/usr/bin/env python3
"""Deterministic Hot smoke probe for FabricDBug player UUID over SpacetimeDB.

Drop-in Python alternative to probe-hot.ps1. Exercises Hot health, SQL, and
command_intent_execute(intent_type = 11).

Exit code:
  0 = passed (Hot healthy, SQL working, reducer dispatched)
  2 = blocked (service unreachable or error)
"""
from __future__ import annotations

import argparse
import datetime
import json
import sys
from pathlib import Path

# Add harness directory to path for spacetime client
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "harness"))
from spacetime import SpacetimeClient, SpacetimeError  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--uri", default="http://127.0.0.1:3000", help="SpacetimeDB HTTP base URI")
    parser.add_argument("--database", default="soupkitzhot", help="SpacetimeDB database name")
    parser.add_argument("--target-uuid", required=True, help="FabricDBug Minecraft player UUID")
    parser.add_argument("--server-id", default="soupkitz-01", help="Server identifier")
    parser.add_argument("--world-id", default="soupkitz", help="World identifier")
    args = parser.parse_args()

    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    client = SpacetimeClient(uri=args.uri, database=args.database)

    try:
        # 1. Health check
        if not client.check_health():
            raise SpacetimeError(f"Hot health endpoint unreachable at {args.uri}/v1/health")

        # 2. SQL query check
        client.sql("SELECT id, target_uuid, intent_type, payload FROM command_intent ORDER BY id DESC LIMIT 1")

        # 3. Custom intent 11 dispatch (FabricDBug control lane)
        payload = {
            "action": "run_script",
            "steps": [
                {"ticks": 1, "keys": ["ATTACK"]},
                {"ticks": 1, "keys": ["USE"]},
            ],
        }
        res = client.send_command_intent(
            target_uuid=args.target_uuid,
            payload=payload,
            intent_type=11,
            server_id=args.server_id,
            world_id=args.world_id,
            sender_id="fabricdbug-probe",
        )

        out = {
            "status": "passed",
            "database": args.database,
            "target_uuid": args.target_uuid,
            "reducer": "command_intent_execute",
            "actions": "ATTACK,USE",
            "key": res["key"],
            "utc": stamp,
        }
        print(json.dumps(out, separators=(",", ":")))
        return 0

    except Exception as e:
        out = {
            "status": "blocked",
            "database": args.database,
            "target_uuid": args.target_uuid,
            "reason": str(e),
            "utc": stamp,
        }
        print(json.dumps(out, separators=(",", ":")), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

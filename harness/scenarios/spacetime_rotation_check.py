"""spacetime_rotation_check: SpacetimeDB-driven stationary look replication scenario.

Dispatches a custom intent (11) with explicit yaw/pitch to FabricDBug via
SpacetimeDB, requests a client snapshot event, and verifies that the reported
angles match the commanded angles within network head yaw quantization
tolerance (1/256 of a turn ≈ 1.41°). Exit code 0 = pass.

    python harness/scenarios/spacetime_rotation_check.py --target-uuid <uuid> --yaw 180.0 --pitch 15.0
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from spacetime import SpacetimeClient, SpacetimeError  # noqa: E402

# Network head yaw is quantized to 1/256 of a turn (~1.406 degrees)
QUANTIZATION_TOLERANCE_DEG = 360.0 / 256.0 + 0.1


def angle_diff(a: float, b: float) -> float:
    """Shortest angular difference modulo 360 degrees."""
    diff = (a - b + 180.0) % 360.0 - 180.0
    return abs(diff)


def run(
    target_uuid: str,
    yaw: float,
    pitch: float,
    uri: str = "http://127.0.0.1:3000",
    database: str = "soupkitzhot",
    timeout_sec: float = 10.0,
) -> int:
    client = SpacetimeClient(uri=uri, database=database)

    print(f"Connecting to SpacetimeDB at {uri} (database: {database}) ...")
    if not client.check_health():
        print(f"FAIL: SpacetimeDB health check failed at {uri}/v1/health")
        return 2

    # Step 1: Send stationary look script
    print(f"Dispatching look intent to {target_uuid}: yaw={yaw:.2f}°, pitch={pitch:.2f}° ...")
    script_payload = {
        "action": "run_script",
        "steps": [
            {"ticks": 10, "keys": [], "yaw": yaw, "pitch": pitch},
        ],
    }
    client.send_command_intent(target_uuid=target_uuid, payload=script_payload)

    # Step 2: Request client snapshot
    time.sleep(1.0)
    print("Requesting client snapshot ...")
    snapshot_payload = {
        "action": "snapshot",
        "request": "rotation-check",
    }
    client.send_command_intent(target_uuid=target_uuid, payload=snapshot_payload)

    # Step 3: Poll for reported fabricdbug_event
    print("Polling fabricdbug_event for snapshot ...")
    start = time.time()
    snapshot_event = None
    while time.time() - start < timeout_sec:
        time.sleep(0.5)
        events = client.query_latest_events(target_uuid=target_uuid, limit=5)
        if not events:
            continue
        for row in events:
            # Events report (target_uuid, event_type, payload)
            if isinstance(row, dict) and row.get("event_type") == "snapshot":
                try:
                    payload = json.loads(row.get("payload", "{}"))
                    if payload.get("request") == "rotation-check":
                        snapshot_event = payload
                        break
                except Exception:
                    continue
        if snapshot_event:
            break

    if not snapshot_event:
        print(f"FAIL: Timed out waiting for snapshot event from client {target_uuid}")
        return 1

    # Step 4: Validate angle replication
    rep_yaw = float(snapshot_event.get("yaw", 0.0))
    rep_pitch = float(snapshot_event.get("pitch", 0.0))
    yaw_err = angle_diff(rep_yaw, yaw)
    pitch_err = abs(rep_pitch - pitch)

    print(f"Commanded: yaw={yaw:.2f}°, pitch={pitch:.2f}°")
    print(f"Reported:  yaw={rep_yaw:.2f}°, pitch={rep_pitch:.2f}°")
    print(f"Error:     yaw_err={yaw_err:.3f}°, pitch_err={pitch_err:.3f}° (tolerance={QUANTIZATION_TOLERANCE_DEG:.3f}°)")

    if yaw_err <= QUANTIZATION_TOLERANCE_DEG and pitch_err <= QUANTIZATION_TOLERANCE_DEG:
        print("PASS: Rotation replicated within network head yaw quantization budget.")
        return 0

    print("FAIL: Angle error exceeds network quantization budget.")
    return 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target-uuid", required=True, help="FabricDBug Minecraft player UUID")
    parser.add_argument("--yaw", type=float, default=180.0, help="Target yaw angle in degrees")
    parser.add_argument("--pitch", type=float, default=0.0, help="Target pitch angle in degrees")
    parser.add_argument("--uri", default="http://127.0.0.1:3000", help="SpacetimeDB HTTP base URI")
    parser.add_argument("--database", default="soupkitzhot", help="SpacetimeDB database name")
    parser.add_argument("--timeout", type=float, default=10.0, help="Polling timeout in seconds")
    args = parser.parse_args()
    raise SystemExit(run(args.target_uuid, args.yaw, args.pitch, uri=args.uri, database=args.database, timeout_sec=args.timeout))

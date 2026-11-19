# FabricDBug

FabricDBug is the successor to MCPClient: a developer-only Fabric client mod
whose culling overlay and scripted input are controlled through SpacetimeDB.
It contains no MCP server, JSON-RPC tool registry, or local agent endpoint.

## Data and control

- Player positions are read from Hot `player_physics_state`.
- The overlay uses those authoritative positions rather than client entity
  packet positions.
- Agents enqueue custom commands through Hot's existing
  `command_intent_execute` reducer with `intent_type = 11`, `target_uuid` set
  to the debug client's UUID, and a JSON `payload`.

FabricDBug now reports `client_ready` when the profile is available and polls
commands from the title/menu screen. That allows the harness to connect the
client without keyboard or mouse automation:

```json
{"action":"connect","address":"127.0.0.1:25565"}
```
For unattended testing while another client is in use, launch a **separate**
profile and test identity with the JVM option `-Dfabricdbug.background=true`.
The test window is never shown, and its initial focus state is cleared so it
cannot capture the player's mouse. This is a real rendering/network client,
not Java's headless renderer. Normal launches remain unchanged.

Use a distinct UUID and `instanceId` for the background profile; address its
commands to that UUID, never the playing user's UUID. On startup the command
feed establishes a watermark and does not replay old commands. Wait for
`client_ready` before issuing a fresh `connect`.


Supported payloads:

```json
{"action":"stop"}
{"action":"run_script","steps":[{"ticks":40,"keys":["FORWARD"]},{"ticks":1,"keys":[],"yaw":180}]}
{"action":"run_script","steps":[{"ticks":1,"keys":["ATTACK"]},{"ticks":10,"keys":[]}]}
{"action":"run_script","steps":[{"ticks":1,"keys":["USE"]},{"ticks":10,"keys":[]}]}
{"action":"freelook_start","perspective":"THIRD_PERSON_BACK"}
{"action":"freelook_stop"}
{"action":"zoom_start"}
{"action":"zoom_stop"}
{"action":"screenshot"}
{"action":"snapshot","request":"rotation-check"}
{"action":"inventory_snapshot"}
{"action":"creative_set_slot","slot":36,"item":"minecraft:diamond_sword"}
{"action":"inventory_click","containerId":0,"slotId":36,"button":0,"clickType":"PICKUP"}
{"action":"catalog_test"}
{"action":"disconnect"}
{"action":"codec_probe"}
{"action":"component_probe","cases":[{"id":6,"case":"literal-name","payload":"CAABeA=="}]}
```

Snapshots report local and visible-player `yaw`, `pitch`, and `headYaw`
alongside positions through `fabricdbug_event`. Use a `run_script` step with
empty `keys` and explicit `yaw`/`pitch` to test stationary look replication,
then snapshot both clients. Compare angles modulo 360; network head yaw is
quantized to 1/256 of a turn (about 1.41 degrees).

Inventory and Catalog actions:
- `inventory_snapshot`: captures container ID, state ID, cursor stack with components, slot details, and screen connection state.
- `creative_set_slot`: sets creative slot via vanilla packet encoding.
- `inventory_click`: invokes vanilla `handleContainerInput` using `ContainerInput` names such as `PICKUP`, `QUICK_MOVE`, `SWAP`, `QUICK_CRAFT`, and `PICKUP_ALL`.
- `catalog_test`: requires a connected creative player; rebuilds creative tabs and sends one stack at a time, checking the server's new state ID and exact item/components/count before continuing. Reports mismatches, acknowledgement timeouts, progress, completion, and disconnects.
- `disconnect`: triggers client disconnect and captures disconnected screen snapshot (reason/title) for reconnect verification.
- `codec_probe`: exports trusted/untrusted ItemStack bytes and effective prototype components to JSONL; reports distinct registry IDs, successful rows, and serialization failures.
- `component_probe`: decodes base64 component payloads with Mojang's component codecs and reports acceptance only when the entire payload is consumed.

Freelook is discovered by Fabric entrypoint at runtime, so FabricDBug still
builds without a hard dependency on its jar. Install Freelook 1.4.3 for
Minecraft 26.2/Fabric in the developer profile to enable those commands.

The screenshot command uses Minecraft's native render-target capture and saves
the PNG under the profile's `screenshots/` directory. FabricDBug reports the
scheduled/completed path through the `report_fabricdbug_event` reducer so an
agent can query the result from SpacetimeDB and open the local image.

## Probing and validation

Run the deterministic Hot smoke probe with a FabricDBug player UUID using either
PowerShell or Python (cross-platform, stdlib-only):

```bash
# Python (cross-platform)
python ./scripts/probe-hot.py --target-uuid <player-uuid>

# PowerShell
pwsh ./scripts/probe-hot.ps1 -TargetUuid <player-uuid>
```

The probe exits `0` only after Hot health, SQL, and
`command_intent_execute(intent_type = 11)` succeed. It exits `2` with a JSON
blocker when no Hot endpoint is available.

## Verification & telemetry harness

The verification and telemetry tools ported from MCPClient are maintained under [`harness/`](./harness/README.md):
- **Stream Alignment (`harness/align.py`)**: Joins client tick records (`ticks.jsonl`), outbound packets (`outbound.jsonl`), and inbound server corrections (`inbound.jsonl`) into tick-indexed structures without clock-offset drift.
- **Movement Diff (`harness/reports/movement_diff.py`)**: Flags any server position correction exceeding a threshold and outputs a formatted $\pm N$-tick context window detailing velocity, ground state, input keys, and packet flows.
- **SpacetimeDB Verification (`harness/scenarios/spacetime_rotation_check.py`)**: End-to-end look replication scenario verifying angle convergence against network head yaw quantization tolerance ($1/256$ turn $\approx 1.41^\circ$).
- **Unit Tests (`harness/tests/test_harness.py`)**: Regression tests for telemetry alignment and diff calculations.

Run tests:
```bash
python -m unittest harness/tests/test_harness.py
```

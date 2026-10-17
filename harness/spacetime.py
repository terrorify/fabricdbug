"""SpacetimeDB HTTP wire client for FabricDBug control and observability.

Zero dependencies (stdlib-only). Interacts with SpacetimeDB's HTTP/SQL endpoints:
- GET  /v1/health
- POST /v1/database/{db}/sql
- POST /v1/database/{db}/call/{reducer}
"""
from __future__ import annotations

import datetime
import json
import urllib.error
import urllib.request
import uuid
from typing import Any, Mapping, Sequence


class SpacetimeError(RuntimeError):
    """Raised for SpacetimeDB HTTP or execution errors."""


class SpacetimeClient:
    def __init__(
        self,
        uri: str = "http://127.0.0.1:3000",
        database: str = "soupkitzhot",
        timeout: float = 10.0,
    ):
        self.uri = uri.rstrip("/")
        self.database = database
        self.timeout = timeout
        self.base_url = f"{self.uri}/v1/database/{self.database}"

    def check_health(self) -> bool:
        """Verify SpacetimeDB node health."""
        req = urllib.request.Request(f"{self.uri}/v1/health", method="GET")
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                return resp.status < 300
        except Exception:
            return False

    def sql(self, query: str) -> Any:
        """Execute a SQL query against the database."""
        url = f"{self.base_url}/sql"
        body = query.encode("utf-8")
        req = urllib.request.Request(
            url,
            data=body,
            method="POST",
            headers={"Content-Type": "text/plain", "Accept": "application/json"},
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                raw = resp.read().decode("utf-8")
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8")
            raise SpacetimeError(f"SQL failed ({e.code}): {raw}") from e

    def call_reducer(self, reducer: str, args: Sequence[Any]) -> Any:
        """Invoke a SpacetimeDB reducer with positional arguments."""
        url = f"{self.base_url}/call/{reducer}"
        body = json.dumps(list(args)).encode("utf-8")
        req = urllib.request.Request(
            url,
            data=body,
            method="POST",
            headers={"Content-Type": "application/json", "Accept": "application/json"},
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                raw = resp.read().decode("utf-8")
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8")
            raise SpacetimeError(f"Reducer {reducer} failed ({e.code}): {raw}") from e

    def send_command_intent(
        self,
        target_uuid: str,
        payload: Mapping[str, Any] | str,
        intent_type: int = 11,
        server_id: str = "fabricdbug-probe",
        world_id: str = "stomzydev",
        sender_id: str = "fabricdbug-harness",
    ) -> dict[str, Any]:
        """Dispatch a custom command intent (intent 11) to FabricDBug.

        FabricDBug consumes this through SQL and executes the actions locally
        on the client tick thread.
        """
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
        unique_key = f"fabricdbug-{stamp}-{uuid.uuid4().hex}"
        payload_str = payload if isinstance(payload, str) else json.dumps(payload)

        # Hot's command_intent_execute reducer arguments
        args = [
            unique_key,
            1,
            0,
            "",
            1,
            server_id,
            world_id,
            sender_id,
            intent_type,
            0,
            target_uuid,
            0,
            0,
            payload_str,
            1,
        ]
        self.call_reducer("command_intent_execute", args)
        return {
            "status": "dispatched",
            "key": unique_key,
            "target_uuid": target_uuid,
            "intent_type": intent_type,
            "payload": payload_str,
            "utc": stamp,
        }

    def query_latest_intent(self) -> Any:
        """Fetch the most recent command intent recorded in SpacetimeDB."""
        return self.sql("SELECT id, target_uuid, intent_type, payload FROM command_intent ORDER BY id DESC LIMIT 1")

    def query_latest_events(self, target_uuid: str | None = None, limit: int = 10) -> Any:
        """Fetch events emitted by FabricDBug (snapshots, screenshots, etc.)."""
        if target_uuid:
            q = f"SELECT * FROM fabricdbug_event WHERE target_uuid = '{target_uuid}' ORDER BY id DESC LIMIT {limit}"
        else:
            q = f"SELECT * FROM fabricdbug_event ORDER BY id DESC LIMIT {limit}"
        return self.sql(q)

"""Own Java governance integration; credentials never enter output artifacts."""

import asyncio
import json
import os
import time
import uuid
from pathlib import Path

import httpx

from jobs_init import PROJECT

ROOT = Path(__file__).resolve().parents[2]
DOCKER = Path(os.environ["LOCALAPPDATA"]) / "Programs/DockerDesktop/resources/bin/docker.exe"


async def docker(*args):
    p = await asyncio.create_subprocess_exec(
        str(DOCKER), *args, stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL
    )
    if await p.wait():
        raise RuntimeError("Own container operation failed")


async def main():
    settings = dict(
        line.split("=", 1)
        for line in (ROOT / "infra/.env").read_text().splitlines()
        if "=" in line and not line.startswith("#")
    )
    async with httpx.AsyncClient(timeout=12) as c:
        origin = "http://127.0.0.1:8080"
        auth = await c.post(
            origin + "/api/auth/login",
            json={"username": "organizer", "password": settings["EVENT_DEMO_PASSWORD"]},
        )
        auth.raise_for_status()
        headers = {"Authorization": "Bearer " + auth.json()["accessToken"]}

        async def instances(count=16):
            seen = set()
            for _ in range(count):
                r = await c.get(origin + "/api/sessions", headers=headers)
                r.raise_for_status()
                seen.add(r.headers["x-service-instance"])
            return seen

        seen = await instances()
        assert len(seen) == 2, len(seen)
        key = str(uuid.uuid4())
        draft = {
            "title": "Gateway idempotent publication",
            "description": "two instances, same DB",
            "sessionTitle": "one slot",
            "capacity": 1,
            "priceCents": 0,
            "holdSeconds": 60,
        }
        ids = []
        for _ in range(6):
            r = await c.post(
                origin + "/api/activities/publish", headers=headers | {"Idempotency-Key": key}, json=draft
            )
            r.raise_for_status()
            ids.append(r.json())
        assert all(row == ids[0] for row in ids)
        conflict = await c.post(
            origin + "/api/activities/publish",
            headers=headers | {"Idempotency-Key": key},
            json=draft | {"capacity": 2},
        )
        assert conflict.status_code == 409
        login = await c.post(
            "http://127.0.0.1:18848/nacos/v3/auth/user/login",
            data={"username": "nacos", "password": settings["NACOS_PASSWORD"]},
        )
        payload = login.json()
        payload = payload.get("data", payload)
        nh = {"accessToken": payload["accessToken"]}
        url = "http://127.0.0.1:18848/nacos/v3/admin/cs/config"
        query = {"dataId": "gateway-policy.properties", "groupName": "PROJECT"}
        initial = await c.get("http://127.0.0.1:18848/nacos/v3/client/cs/config", params=query, headers=nh)
        initial.raise_for_status()
        original = initial.json()["data"]

        async def publish(text):
            r = await c.post(url, headers=nh, data=query | {"content": text, "type": "properties"})
            r.raise_for_status()
            assert r.json()["code"] in (0, 200)

        try:
            await publish("booking_admission_enabled=false\n")
            for _ in range(40):
                status = await c.get(origin + "/gateway/status")
                if status.json()["bookingAdmissionEnabled"] is False:
                    break
                await asyncio.sleep(0.5)
            else:
                raise AssertionError("Configuration did not propagate")
            blocked = await c.post(origin + "/api/booking-requests", headers=headers, json={})
            assert blocked.status_code == 503
            existing = await c.get(origin + "/api/booking-requests", headers=headers)
            assert existing.status_code == 200
        finally:
            await publish(original)
        stopped = time.monotonic()
        try:
            await docker("stop", "-t", "20", f"{PROJECT}-event-app-2-1")
            for _ in range(40):
                try:
                    remaining = await instances(4)
                except httpx.HTTPStatusError:
                    remaining = set()
                if len(remaining) == 1:
                    break
                await asyncio.sleep(0.5)
            else:
                raise AssertionError("Stopped backend not removed")
            assert remaining.issubset(seen)
            recovery_seconds = round(time.monotonic() - stopped, 3)
        finally:
            await docker("start", f"{PROJECT}-event-app-2-1")
        for _ in range(80):
            try:
                restored = await instances(4)
            except (httpx.HTTPError, KeyError):
                restored = set()
            if len(restored) == 2:
                break
            await asyncio.sleep(0.5)
        else:
            raise AssertionError("Restarted backend did not return")
        artifact = {
            "status": "PASS",
            "instances_before": 2,
            "instances_after_stop": 1,
            "instances_after_restart": 2,
            "config_disable_create": 503,
            "config_retains_query": 200,
            "publication_replays": len(ids),
            "publication_same_result": True,
            "changed_payload": 409,
            "stop_and_recovery_seconds": recovery_seconds,
            "limits": "Single host development lab; stop includes graceful 20s budget. No throughput or production HA claim. AI worker intentionally stopped.",
        }
        out = ROOT / ".local/governance_probe.json"
        out.write_text(json.dumps(artifact, ensure_ascii=False, indent=2))
        print(json.dumps(artifact, ensure_ascii=False))


if __name__ == "__main__":
    asyncio.run(main())

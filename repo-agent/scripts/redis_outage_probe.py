"""Stops/restarts only this compose project's Redis; restores it in finally."""

import asyncio
import json
import os
import subprocess
import uuid
from pathlib import Path

import httpx


async def main():
    docker = str(Path(os.environ["LOCALAPPDATA"]) / "Programs/DockerDesktop/resources/bin/docker.exe")
    container = "internship-projects-redis-1"

    def command(*args):
        result = subprocess.run([docker, *args, container], capture_output=True, timeout=30)
        if result.returncode:
            raise RuntimeError("Own Redis container lifecycle operation failed")

    async with httpx.AsyncClient(timeout=20) as client:

        async def login(user):
            response = await client.post(
                "http://127.0.0.1:8090/api/auth/login",
                json={"username": user, "password": os.environ["EVENT_DEMO_PASSWORD"]},
            )
            response.raise_for_status()
            return {"Authorization": "Bearer " + response.json()["accessToken"]}

        owner, attendee = await login("organizer"), await login("alice")
        activity = await client.post(
            "http://127.0.0.1:8090/api/activities",
            headers=owner,
            json={"title": "Redis故障联调", "description": "controlled own-project outage"},
        )
        activity.raise_for_status()
        session = await client.post(
            "http://127.0.0.1:8090/api/sessions",
            headers=owner,
            json={
                "activityId": activity.json()["id"],
                "title": "准入边界验证",
                "capacity": 1,
                "priceCents": 0,
                "holdSeconds": 300,
            },
        )
        session.raise_for_status()
        identifier = session.json()["id"]
        published = await client.post(
            f"http://127.0.0.1:8090/api/sessions/{identifier}/publish", headers=owner
        )
        published.raise_for_status()
        normal = await client.get(f"http://127.0.0.1:8090/api/sessions/{identifier}", headers=attendee)
        normal.raise_for_status()
        try:
            await asyncio.to_thread(command, "stop", "-t", "5")
            read = await client.get(f"http://127.0.0.1:8090/api/sessions/{identifier}", headers=attendee)
            assert read.status_code == 200 and read.json()["id"] == identifier
            write = await client.post(
                "http://127.0.0.1:8090/api/booking-requests",
                headers=attendee | {"Idempotency-Key": str(uuid.uuid4())},
                json={"sessionId": identifier},
            )
            assert write.status_code == 503 and write.json()["code"] == "ADMISSION_UNAVAILABLE"
            mine = await client.get("http://127.0.0.1:8090/api/booking-requests", headers=attendee)
            mine.raise_for_status()
            assert not any(b["sessionId"] == identifier for b in mine.json())
        finally:
            await asyncio.to_thread(command, "start")
        for _ in range(20):
            try:
                probe = await client.post(
                    "http://127.0.0.1:8090/api/auth/login",
                    json={"username": "alice", "password": os.environ["EVENT_DEMO_PASSWORD"]},
                )
                if probe.status_code == 200:
                    break
            except httpx.HTTPError:
                pass
            await asyncio.sleep(1)
        assert probe.status_code == 200
        report = {
            "status": "PASS",
            "cache_read_when_redis_down": 200,
            "admission_when_redis_down": 503,
            "no_request_written": True,
            "redis_restored": True,
            "scope": "only internship-projects-redis-1; native old MySQL untouched",
        }
        Path(".local/redis_outage_probe.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        print(json.dumps(report))


if __name__ == "__main__":
    asyncio.run(main())

"""Real API lifecycle/ownership/summary/OpenAPI probe, no model requests."""

import asyncio
import json
import os
import uuid
from pathlib import Path

import httpx


async def main():
    origin = "http://127.0.0.1:8090"
    async with httpx.AsyncClient(timeout=15) as client:

        async def login(user):
            r = await client.post(
                origin + "/api/auth/login",
                json={"username": user, "password": os.environ["EVENT_DEMO_PASSWORD"]},
            )
            r.raise_for_status()
            return {"Authorization": "Bearer " + r.json()["accessToken"]}

        owner, alice, bob, checker, operator = (
            await login("organizer"),
            await login("alice"),
            await login("bob"),
            await login("checker"),
            await login("operator"),
        )

        async def call(method, path, who=owner, body=None, headers=None):
            r = await client.request(method, origin + "/api" + path, headers=who | (headers or {}), json=body)
            r.raise_for_status()
            return r.json()

        activity = await call(
            "POST",
            "/activities",
            body={"title": "容器履约链路验收", "description": "real API fixture; payment is simulated"},
        )
        session = await call(
            "POST",
            "/sessions",
            body={
                "activityId": activity["id"],
                "title": "单名额候补",
                "capacity": 1,
                "priceCents": 0,
                "holdSeconds": 300,
            },
        )
        await call("POST", f"/sessions/{session['id']}/publish")

        async def reserve(who):
            return await call(
                "POST",
                "/booking-requests",
                who,
                {"sessionId": session["id"]},
                {"Idempotency-Key": str(uuid.uuid4())},
            )

        a, b = await reserve(alice), await reserve(bob)

        async def until(identifier, status, who):
            for _ in range(30):
                row = await call("GET", "/booking-requests/" + identifier, who)
                if row["status"] == status:
                    return row
                await asyncio.sleep(0.5)
            raise TimeoutError("Own booking did not reach expected state")

        a = await until(a["id"], "HELD", alice)
        b = await until(b["id"], "WAITING", bob)
        await call("POST", f"/booking-requests/{a['id']}/cancel", alice)
        b = await until(b["id"], "HELD", bob)
        await call("POST", f"/orders/{b['orderId']}/confirm", bob)
        ticket = await call("GET", f"/orders/{b['orderId']}/ticket", bob)
        forbidden = await client.get(origin + f"/api/orders/{b['orderId']}/ticket", headers=alice)
        assert forbidden.status_code == 403
        before = await client.post(origin + "/api/check-in", headers=checker, json={"token": ticket["token"]})
        assert before.status_code == 403
        await call("POST", f"/sessions/{session['id']}/checkers", body={"userId": "checker", "grant": True})
        assert (await call("POST", "/check-in", checker, {"token": ticket["token"]}))[
            "status"
        ] == "CHECKED_IN"
        duplicate = await client.post(
            origin + "/api/check-in", headers=checker, json={"token": ticket["token"]}
        )
        assert duplicate.status_code == 409
        stats = await call(
            "POST", "/organizer/session-summaries", body={"sessionIds": [session["id"], session["id"]]}
        )
        assert (
            len(stats) == 1
            and stats[0]["status"] == "OK"
            and stats[0]["counts"]["checkedIn"] == 1
            and stats[0]["counts"]["requests"] == 2
        )
        forbidden = await client.get(origin + "/api/ops/outbox", headers=owner)
        assert forbidden.status_code == 403
        await call("GET", "/ops/outbox", operator)
        docs = await client.get(origin + "/v3/api-docs", headers=owner)
        docs.raise_for_status()
        assert "/api/organizer/session-summaries" in docs.json()["paths"]
        result = {
            "status": "PASS",
            "lifecycle": "accept/wait/cancel/promote/confirm/ticket/explicit checker grant/checkin/duplicate409",
            "other_owner": 403,
            "summary": "real MySQL counts PASS",
            "operator_boundary": 403,
            "openapi": "PASS",
            "request_id_present": bool(docs.headers.get("x-request-id")),
            "model_calls": 0,
        }
        Path(".local/event_lifecycle_probe.json").write_text(
            json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        print(json.dumps(result))


if __name__ == "__main__":
    asyncio.run(main())

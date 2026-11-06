"""Real XXL scheduler, isolated DB and bounded recovery faults; never output credentials."""

import asyncio
import json
import time
import uuid

import httpx
from jobs_init import DOCKER, ROOT, sql


async def docker(*args):
    p = await asyncio.create_subprocess_exec(
        str(DOCKER), *args, stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL
    )
    if await p.wait():
        raise RuntimeError("Own container operation failed")


async def main():
    settings = dict(
        line.split("=", 1)
        for line in (ROOT / "infra/.env").read_text(encoding="utf-8").splitlines()
        if "=" in line and not line.startswith("#")
    )
    admin = "http://127.0.0.1:18080/xxl-job-admin"
    origin = "http://127.0.0.1:8090"
    result = {}
    async with httpx.AsyncClient(timeout=12) as c:

        async def login_admin():
            r = await c.post(
                admin + "/login", data={"userName": "admin", "password": settings["XXL_ADMIN_PASSWORD"]}
            )
            r.raise_for_status()
            assert r.json()["code"] == 200, "Admin login failed"

        await login_admin()

        async def operation(action, **data):
            r = await c.post(admin + "/jobinfo/" + action, data={"id": "1", **data})
            r.raise_for_status()
            assert r.json()["code"] == 200, (action, r.json().get("code"))

        async def maxlog():
            return int(
                await sql("SELECT COALESCE(MAX(id),0) FROM xxl_job_project.xxl_job_log WHERE job_id=1;")
            )

        async def wait_log(after, code=200, timeout=65):
            until = time.monotonic() + timeout
            while time.monotonic() < until:
                rows = await sql(
                    f"SELECT id,trigger_code,handle_code,executor_fail_retry_count FROM xxl_job_project.xxl_job_log WHERE job_id=1 AND id>{after} AND handle_code={code} ORDER BY id;"
                )
                if rows:
                    return [list(map(int, line.split("\t"))) for line in rows.splitlines()]
                await asyncio.sleep(0.5)
            raise TimeoutError(f"Scheduler callback not observed, expected {code}")

        for _ in range(100):
            address = await sql(
                "SELECT COALESCE(address_list,'') FROM xxl_job_project.xxl_job_group WHERE id=1;"
            )
            if address:
                break
            await asyncio.sleep(0.5)
        else:
            raise TimeoutError("Executor not registered")
        before = await sql("SELECT trigger_status FROM xxl_job_project.xxl_job_info WHERE id=1;")
        assert before == "0", (
            "Probe expects its own initialized stopped task, will not disturb an active schedule"
        )
        r = await c.post(
            origin + "/api/auth/login",
            json={"username": "organizer", "password": settings["EVENT_DEMO_PASSWORD"]},
        )
        r.raise_for_status()
        owner = {"Authorization": "Bearer " + r.json()["accessToken"]}
        r = await c.post(
            origin + "/api/auth/login",
            json={"username": "alice", "password": settings["EVENT_DEMO_PASSWORD"]},
        )
        r.raise_for_status()
        alice = {"Authorization": "Bearer " + r.json()["accessToken"]}

        async def call(method, path, who=owner, body=None, headers=None):
            r = await c.request(method, origin + "/api" + path, headers=who | (headers or {}), json=body)
            r.raise_for_status()
            return r.json()

        async def expired_fixture():
            a = await call(
                "POST",
                "/activities/publish",
                body={
                    "title": "XXL bounded reconciliation fixture",
                    "description": "synthetic deadline loss",
                    "sessionTitle": "one place",
                    "capacity": 1,
                    "priceCents": 0,
                    "holdSeconds": 3600,
                },
                headers={"Idempotency-Key": str(uuid.uuid4())},
            )
            # Atomic publication returns both activity and session IDs.
            sid = a["sessionId"]
            b = await call(
                "POST", "/booking-requests", alice, {"sessionId": sid}, {"Idempotency-Key": str(uuid.uuid4())}
            )
            for _ in range(40):
                b = await call("GET", "/booking-requests/" + b["id"], alice)
                if b["status"] == "HELD":
                    break
                await asyncio.sleep(0.5)
            assert b["status"] == "HELD"
            oid = b["orderId"]
            await sql(
                f"USE eventfulfill_docker; UPDATE booking_order SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id='{oid}'; UPDATE outbox_event SET available_at=UTC_TIMESTAMP(6)+INTERVAL 1 DAY WHERE aggregate_id='{oid}' AND topic='ORDER_DEADLINE';"
            )
            return sid, oid

        try:
            sid, oid = await expired_fixture()
            await asyncio.sleep(3)
            assert (
                await sql(f"SELECT status FROM eventfulfill_docker.booking_order WHERE id='{oid}';") == "HELD"
            ), "Internal scanner unexpectedly active"
            first = await maxlog()
            await operation("trigger", executorParam="", addressList="")
            await wait_log(first)
            assert (
                await sql(f"SELECT status FROM eventfulfill_docker.booking_order WHERE id='{oid}';")
                == "EXPIRED"
            )
            second = await maxlog()
            await operation("trigger", executorParam="", addressList="")
            await wait_log(second)
            exact = await sql(
                f"USE eventfulfill_docker; SELECT (SELECT used FROM event_session WHERE id='{sid}'),(SELECT COUNT(*) FROM audit_event WHERE aggregate_id='{oid}' AND action='ORDER_EXPIRED');"
            )
            assert exact == "0\t1", exact
            result["manual_and_duplicate"] = {"expired": True, "used": 0, "expiry_audits": 1}
            # Redis lock fails closed; admin records failure and retries once after recovery.
            base = await maxlog()
            try:
                await docker("stop", "--timeout", "10", "internship-projects-redis-1")
                await operation("trigger", executorParam="", addressList="")
                failed = await wait_log(base, 500)
            finally:
                await docker("start", "internship-projects-redis-1")
            retried = await wait_log(failed[0][0], 200, 90)
            assert any(row[3] == 0 for row in retried), retried
            result["automatic_retry"] = {
                "observed_failed_callback": 500,
                "recovery_callback": 200,
                "retry_count_exhausted_to": 0,
            }
            # Leave a due order while the managed scheduler is unavailable, then recover a missed tick.
            await operation("start")
            try:
                await docker("stop", "--timeout", "15", "internship-projects-xxl-job-1")
                sid2, oid2 = await expired_fixture()
                await asyncio.sleep(12)
                assert (
                    await sql(f"SELECT status FROM eventfulfill_docker.booking_order WHERE id='{oid2}';")
                    == "HELD"
                )
                resume = await maxlog()
            finally:
                await docker("start", "internship-projects-xxl-job-1")
            for _ in range(100):
                try:
                    await login_admin()
                    break
                except (httpx.HTTPError, AssertionError, ValueError):
                    await asyncio.sleep(0.5)
            else:
                raise TimeoutError("Admin not ready after restart")
            await wait_log(resume, 200, 90)
            assert (
                await sql(f"SELECT status FROM eventfulfill_docker.booking_order WHERE id='{oid2}';")
                == "EXPIRED"
            )
            result["scheduler_restart_misfire"] = {
                "due_order_stayed_held_while_down": True,
                "expired_after_restart": True,
                "strategy": "FIRE_ONCE_NOW",
            }
        finally:
            try:
                await operation("stop")
            except (httpx.HTTPError, AssertionError):
                await sql("UPDATE xxl_job_project.xxl_job_info SET trigger_status=0 WHERE id=1;")
        result |= {
            "status": "PASS",
            "admin_version": "3.1.1",
            "scope": "Own synthetic fixtures, real admin/executor callback and DB invariants; admin is trusted, executor port internal only. No production HA claim.",
        }
        target = ROOT / "repo-agent/.local/jobs_probe.json"
        target.write_text(json.dumps(result, indent=2), encoding="utf-8")
        print(json.dumps(result))


if __name__ == "__main__":
    asyncio.run(main())

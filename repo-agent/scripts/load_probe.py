"""Bounded k6/MySQL/Redis/MQ experiment; all fixtures labelled and isolated to own schema."""

import asyncio
import base64
import hashlib
import hmac
import json
import os
import time
import uuid
from pathlib import Path

import httpx
from jobs_init import sql

ROOT = Path(__file__).resolve().parents[2]


def encode(value):
    return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).rstrip(b"=").decode()


def token(user, secret):
    text = (
        encode({"alg": "HS256", "typ": "JWT"})
        + "."
        + encode(
            {
                "iss": "event-fulfill",
                "sub": user,
                "iat": int(time.time()),
                "exp": int(time.time()) + 3600,
                "roles": ["USER"],
            }
        )
    )
    return (
        text
        + "."
        + base64.urlsafe_b64encode(hmac.new(secret.encode(), text.encode(), hashlib.sha256).digest())
        .rstrip(b"=")
        .decode()
    )


async def main():
    settings = dict(
        line.split("=", 1)
        for line in (ROOT / "infra/.env").read_text().splitlines()
        if "=" in line and not line.startswith("#")
    )
    run = "load-" + uuid.uuid4().hex[:12]
    aid = str(uuid.uuid4())
    sessions = [str(uuid.uuid4()) for _ in range(202)]
    users = [run + "-" + str(i) for i in range(100)]
    statement = (
        "USE eventfulfill_docker; INSERT INTO activity VALUES('%s','organizer','Synthetic k6 run','%s',UTC_TIMESTAMP(6));\n"
        % (aid, run)
    )
    statement += (
        "INSERT INTO app_user VALUES "
        + ",".join("('%s','disabled-load-login','USER')" % u for u in users)
        + ";\n"
    )
    statement += (
        "INSERT INTO event_session(id,activity_id,owner_id,title,capacity,used,price_cents,hold_seconds,published,version) VALUES "
        + ",".join(
            "('%s','%s','organizer','Synthetic load',20,0,0,3600,1,0)" % (sid, aid) for sid in sessions
        )
        + ";"
    )
    await sql(statement)
    directory = ROOT / ".local/load" / run
    directory.mkdir(parents=True)
    inputs = {
        "run": run,
        "origin": "http://127.0.0.1:8090",
        "tokens": [token(u, settings["EVENT_JWT_SECRET"]) for u in users],
        "warmSession": sessions[0],
        "coldSessions": sessions[1:201],
        "bookingSession": sessions[201],
    }
    private = directory / "private-input.json"
    private.write_text(json.dumps(inputs))
    k6 = next((ROOT / ".local/tools/k6").rglob("k6.exe"))
    async with httpx.AsyncClient(timeout=10) as client:
        warm = await client.get(
            inputs["origin"] + "/api/sessions/" + sessions[0],
            headers={"Authorization": "Bearer " + inputs["tokens"][0]},
        )
        warm.raise_for_status()
    results = {}
    for mode in ("warm", "cold", "booking"):
        output = directory / (mode + ".json")
        env = os.environ | {
            "LOAD_INPUT": str(private),
            "LOAD_OUTPUT": str(output),
            "LOAD_MODE": mode,
            "K6_NO_USAGE_REPORT": "true",
            "K6_NO_COLOR": "true",
        }
        with (directory / (mode + ".log")).open("wb") as log:
            proc = await asyncio.create_subprocess_exec(
                str(k6),
                "run",
                "--no-usage-report",
                str(ROOT / "event-platform/load/scenario.js"),
                env=env,
                stdout=log,
                stderr=log,
            )
            code = await proc.wait()
        data = json.loads(output.read_text())
        metrics = data["metrics"]
        results[mode] = {
            "exit_code": code,
            "checks": metrics["checks"]["values"],
            "http": metrics["http_req_duration"]["values"],
            "requests": metrics["http_reqs"]["values"],
            "http_failed": metrics["http_req_failed"]["values"],
        }
        if code:
            raise AssertionError(
                "k6 workload failed; inspect private load logs without exposing input tokens"
            )
    for _ in range(60):
        counts = await sql(
            "USE eventfulfill_docker; SELECT status,COUNT(*) FROM booking_request WHERE session_id='%s' GROUP BY status;"
            % sessions[201]
        )
        if "ACCEPTED" not in counts:
            break
        await asyncio.sleep(0.5)
    parsed = dict(line.split("\t") for line in counts.splitlines())
    assert parsed == {"HELD": "20", "WAITING": "80"}, parsed
    invariant = await sql(
        "USE eventfulfill_docker; SELECT used,capacity,(SELECT COUNT(*) FROM booking_order WHERE session_id='%s') FROM event_session WHERE id='%s';"
        % (sessions[201], sessions[201])
    )
    assert invariant == "20\t20\t20", invariant
    result = {
        "status": "PASS",
        "tool": "k6 v2.3.0 official SHA256-verified Windows binary",
        "run": run,
        "vus": 20,
        "read_iterations_each": 200,
        "booking_distinct_users": 100,
        "booking_http_attempts": 200,
        "request_states": parsed,
        "capacity_used_orders": [20, 20, 20],
        "measurements": results,
        "limits": "Single localhost development sample, 2 CPUs WSL and shared host. Cold means distinct uncached records, warm means one primed key. Not a controlled speedup or capacity ceiling; acceptance latency excludes asynchronous allocation.",
    }
    target = ROOT / "repo-agent/.local/load_probe.json"
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    asyncio.run(main())

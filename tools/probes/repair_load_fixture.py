"""Explicit one-time repair of this harness's failed synthetic fixture, with before image."""

import asyncio
import hashlib
import json

from jobs_init import ROOT, sql


async def main():
    data = json.loads((ROOT / ".local/load/load-96d336a52af1/private-input.json").read_text(encoding="utf-8"))
    sid = data["bookingSession"]
    assert (
        await sql(
            f"SELECT a.description FROM eventfulfill_docker.activity a JOIN eventfulfill_docker.event_session s ON s.activity_id=a.id WHERE s.id='{sid}';"
        )
        == "load-96d336a52af1"
    )
    snapshot = {}
    for table, fields in [
        ("booking_request", ["id", "status", "order_id"]),
        ("booking_order", ["id", "request_id", "status"]),
        ("waitlist_entry", ["id", "request_id", "status"]),
        ("event_session", ["id", "used", "capacity"]),
    ]:
        condition = f"id='{sid}'" if table == "event_session" else f"session_id='{sid}'"
        members = ",".join(f"'{f}',{f}" for f in fields)
        raw = await sql(
            f"SELECT JSON_ARRAYAGG(JSON_OBJECT({members})) FROM eventfulfill_docker.{table} WHERE {condition};"
        )
        snapshot[table] = json.loads(raw)
    target = ROOT / "repo-agent/.local/load-fixture-repair-before.json"
    if target.exists():
        raise RuntimeError("Before image already exists; do not rerun repair blindly")
    target.write_text(json.dumps(snapshot, indent=2), encoding="utf-8")
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    count = int(
        await sql(
            f"SELECT COUNT(*) FROM eventfulfill_docker.booking_request b JOIN eventfulfill_docker.booking_order o ON o.request_id=b.id WHERE b.session_id='{sid}' AND b.status='WAITING' AND b.order_id IS NULL AND o.status='HELD';"
        )
    )
    assert count == 20, count
    await sql(
        f"USE eventfulfill_docker; START TRANSACTION; UPDATE booking_request b JOIN booking_order o ON o.request_id=b.id SET b.status=o.status,b.order_id=o.id WHERE b.session_id='{sid}' AND b.status='WAITING' AND b.order_id IS NULL AND o.status='HELD'; UPDATE waitlist_entry w JOIN booking_order o ON o.request_id=w.request_id SET w.status='OFFERED' WHERE w.session_id='{sid}' AND w.status='WAITING' AND o.status='HELD'; INSERT INTO audit_event VALUES(UUID(),'test-harness','SYNTHETIC_FIXTURE_REPAIRED','{sid}','Archived original before image SHA256={digest}',UTC_TIMESTAMP(6)); COMMIT;"
    )
    result = {
        "status": "PASS",
        "run": "load-96d336a52af1",
        "scope": "Only the previously failed harness-owned session; no Windows learning DB or real users. Repair is explicit, audited and preserves before image; original failure report not changed.",
        "before_image_sha256": digest,
        "repaired_request_links": count,
        "before_image": "load-fixture-repair-before.json",
    }
    (ROOT / "repo-agent/.local/load_fixture_repair.json").write_text(
        json.dumps(result, indent=2), encoding="utf-8"
    )
    print(json.dumps(result))


if __name__ == "__main__":
    asyncio.run(main())

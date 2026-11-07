"""Repeatable real MySQL EXPLAIN ANALYZE on synthetic project-owned records."""

import asyncio
import json
import uuid

from jobs_init import ROOT, sql


async def main():
    table = "booking_" + uuid.uuid4().hex[:12]
    await sql("CREATE DATABASE IF NOT EXISTS eventfulfill_diagnostics CHARACTER SET utf8mb4;")
    await sql(
        f"CREATE TABLE eventfulfill_diagnostics.{table}(id BIGINT PRIMARY KEY,user_id VARCHAR(40) NOT NULL,status VARCHAR(20) NOT NULL,created_at DATETIME NOT NULL,detail VARCHAR(200) NOT NULL) ENGINE=InnoDB;"
    )
    digit = "(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)"
    sequence = f"(SELECT a.n+10*b.n+100*c.n+1000*d.n+10000*e.n n FROM {digit} a CROSS JOIN {digit} b CROSS JOIN {digit} c CROSS JOIN {digit} d CROSS JOIN {digit} e) x"
    await sql(
        f"INSERT INTO eventfulfill_diagnostics.{table} SELECT n,CONCAT('u',MOD(n,500)),IF(MOD(n,5)=0,'CLOSED','OPEN'),DATE_ADD('2026-01-01',INTERVAL n SECOND),REPEAT('x',100) FROM {sequence} WHERE n<20000;"
    )
    query = f"SELECT * FROM eventfulfill_diagnostics.{table} WHERE user_id='u37' AND status='OPEN' ORDER BY created_at DESC LIMIT 20"
    before = await sql("EXPLAIN ANALYZE " + query)
    await sql(
        f"CREATE INDEX ix_user_status_time ON eventfulfill_diagnostics.{table}(user_id,status,created_at DESC); ANALYZE TABLE eventfulfill_diagnostics.{table};"
    )
    after = await sql("EXPLAIN ANALYZE " + query)
    assert "Table scan" in before and "20000" in before, before
    assert "Index lookup" in after and "ix_user_status_time" in after, after
    rows = await sql(query)
    assert len(rows.splitlines()) == 20
    # Demonstrate repeatable-read snapshot vs current read in the dedicated Java/MySQL regression, not inferred from EXPLAIN.
    result = {
        "status": "PASS",
        "mysql_version": await sql("SELECT VERSION();"),
        "rows": 20000,
        "query": query,
        "before": before,
        "after": after,
        "returned_rows": 20,
        "table": table,
        "scope": "Single synthetic owner/status history query, warm shared local MySQL. Plan demonstrates access path and sort change, not universal speedup or production latency. MVCC current-read regression recorded separately.",
    }
    out = ROOT / ".local/mysql_diagnostics.json"
    out.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result))


if __name__ == "__main__":
    asyncio.run(main())

-- Synthetic SQL lab only. The runner places this in a separate project-owned schema.
CREATE TABLE IF NOT EXISTS diagnostic_booking (
 id BIGINT PRIMARY KEY, user_id VARCHAR(40) NOT NULL, status VARCHAR(20) NOT NULL,
 created_at DATETIME NOT NULL, detail VARCHAR(200) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Compare a user history query with (user_id,status,created_at DESC).
-- EXPLAIN ANALYZE executes the SELECT; it is not just an estimated plan.

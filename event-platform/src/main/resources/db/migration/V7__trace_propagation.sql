ALTER TABLE outbox_event ADD COLUMN trace_parent VARCHAR(55);
CREATE TABLE agent_trace(job_id VARCHAR(36) PRIMARY KEY,trace_parent VARCHAR(55));

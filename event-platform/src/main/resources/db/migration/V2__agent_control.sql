CREATE TABLE agent_workspace (
  id VARCHAR(36) PRIMARY KEY, owner_id VARCHAR(80) NOT NULL,
  name VARCHAR(120) NOT NULL, created_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE agent_job (
  id VARCHAR(36) PRIMARY KEY, workspace_id VARCHAR(36) NOT NULL,
  owner_id VARCHAR(80) NOT NULL, idem_key VARCHAR(100) NOT NULL,
  repo_url VARCHAR(300) NOT NULL, commit_sha VARCHAR(40) NOT NULL,
  question VARCHAR(4000) NOT NULL, status VARCHAR(20) NOT NULL,
  attempt BIGINT NOT NULL DEFAULT 0, lease_token VARCHAR(36), lease_until TIMESTAMP(6),
  result_ref VARCHAR(500), error_code VARCHAR(80), created_at TIMESTAMP(6) NOT NULL,
  UNIQUE(owner_id,idem_key), FOREIGN KEY(workspace_id) REFERENCES agent_workspace(id)
);
CREATE INDEX ix_agent_recovery ON agent_job(status,lease_until);
CREATE TABLE agent_approval (
  id VARCHAR(36) PRIMARY KEY, job_id VARCHAR(36) NOT NULL,
  plan_hash VARCHAR(64) NOT NULL, status VARCHAR(20) NOT NULL,
  expires_at TIMESTAMP(6) NOT NULL, approved_by VARCHAR(80),
  UNIQUE(job_id,plan_hash), FOREIGN KEY(job_id) REFERENCES agent_job(id)
);

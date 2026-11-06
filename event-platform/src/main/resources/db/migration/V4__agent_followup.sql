CREATE TABLE agent_followup (
  job_id VARCHAR(36) PRIMARY KEY, parent_job_id VARCHAR(36) NOT NULL,
  FOREIGN KEY(job_id) REFERENCES agent_job(id),
  FOREIGN KEY(parent_job_id) REFERENCES agent_job(id)
);

CREATE TABLE publication_request (
 owner_id VARCHAR(80) NOT NULL,idem_key VARCHAR(100) NOT NULL,payload_hash VARCHAR(64) NOT NULL,
 activity_id VARCHAR(36),session_id VARCHAR(36),PRIMARY KEY(owner_id,idem_key)
);

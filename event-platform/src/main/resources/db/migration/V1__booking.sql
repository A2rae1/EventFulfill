CREATE TABLE app_user (
  id VARCHAR(80) PRIMARY KEY, password_hash VARCHAR(100) NOT NULL,
  role VARCHAR(20) NOT NULL
);
CREATE TABLE activity (
  id VARCHAR(36) PRIMARY KEY, owner_id VARCHAR(80) NOT NULL,
  title VARCHAR(120) NOT NULL, description VARCHAR(2000) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE event_session (
  id VARCHAR(36) PRIMARY KEY, activity_id VARCHAR(36) NOT NULL, owner_id VARCHAR(80) NOT NULL,
  title VARCHAR(120) NOT NULL, capacity INT NOT NULL, used INT NOT NULL DEFAULT 0,
  price_cents INT NOT NULL, hold_seconds INT NOT NULL,
  published BOOLEAN NOT NULL DEFAULT FALSE, version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ck_capacity CHECK (capacity > 0 AND used >= 0 AND used <= capacity),
  CONSTRAINT ck_price CHECK (price_cents >= 0),
  FOREIGN KEY (activity_id) REFERENCES activity(id)
);
CREATE TABLE booking_request (
  seq BIGINT AUTO_INCREMENT PRIMARY KEY, id VARCHAR(36) NOT NULL UNIQUE,
  session_id VARCHAR(36) NOT NULL, user_id VARCHAR(80) NOT NULL,
  idem_key VARCHAR(100) NOT NULL, status VARCHAR(20) NOT NULL,
  order_id VARCHAR(36), created_at TIMESTAMP(6) NOT NULL,
  UNIQUE (user_id, idem_key), FOREIGN KEY (session_id) REFERENCES event_session(id)
);
CREATE INDEX ix_request_session_status ON booking_request(session_id, status, seq);
CREATE TABLE active_claim (
  session_id VARCHAR(36) NOT NULL, user_id VARCHAR(80) NOT NULL, request_id VARCHAR(36) NOT NULL UNIQUE,
  PRIMARY KEY (session_id, user_id), FOREIGN KEY (request_id) REFERENCES booking_request(id)
);
CREATE TABLE booking_order (
  id VARCHAR(36) PRIMARY KEY, request_id VARCHAR(36) NOT NULL UNIQUE,
  session_id VARCHAR(36) NOT NULL, user_id VARCHAR(80) NOT NULL,
  status VARCHAR(20) NOT NULL, kind VARCHAR(20) NOT NULL,
  price_cents INT NOT NULL, expires_at TIMESTAMP(6) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  FOREIGN KEY (request_id) REFERENCES booking_request(id)
);
CREATE INDEX ix_order_expiry ON booking_order(status, expires_at);
CREATE TABLE allocation (
  order_id VARCHAR(36) PRIMARY KEY, session_id VARCHAR(36) NOT NULL,
  status VARCHAR(20) NOT NULL, FOREIGN KEY (order_id) REFERENCES booking_order(id)
);
CREATE TABLE waitlist_entry (
  id VARCHAR(36) PRIMARY KEY, seq BIGINT NOT NULL UNIQUE,
  request_id VARCHAR(36) NOT NULL UNIQUE, session_id VARCHAR(36) NOT NULL,
  user_id VARCHAR(80) NOT NULL, status VARCHAR(20) NOT NULL,
  FOREIGN KEY (request_id) REFERENCES booking_request(id)
);
CREATE INDEX ix_wait_fifo ON waitlist_entry(session_id, status, seq);
CREATE TABLE admission_ticket (
  id VARCHAR(36) PRIMARY KEY, order_id VARCHAR(36) NOT NULL UNIQUE,
  user_id VARCHAR(80) NOT NULL, token VARCHAR(80) NOT NULL UNIQUE,
  status VARCHAR(20) NOT NULL, checked_at TIMESTAMP(6),
  FOREIGN KEY (order_id) REFERENCES booking_order(id)
);
CREATE TABLE payment_event (
  event_id VARCHAR(100) PRIMARY KEY, order_id VARCHAR(36) NOT NULL,
  outcome VARCHAR(30) NOT NULL, amount_cents INT NOT NULL, created_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE outbox_event (
  id VARCHAR(36) PRIMARY KEY, topic VARCHAR(60) NOT NULL, aggregate_id VARCHAR(36) NOT NULL,
  payload VARCHAR(4000) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0, available_at TIMESTAMP(6) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL, last_error VARCHAR(300),
  lease_until TIMESTAMP(6), lease_token VARCHAR(36)
);
CREATE INDEX ix_outbox_dispatch ON outbox_event(status, available_at);
CREATE TABLE consumer_receipt (
  consumer_name VARCHAR(60) NOT NULL, event_id VARCHAR(36) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL, PRIMARY KEY (consumer_name, event_id)
);
CREATE TABLE notification (
  id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(80) NOT NULL,
  event_id VARCHAR(36) NOT NULL UNIQUE, message VARCHAR(500) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE audit_event (
  id VARCHAR(36) PRIMARY KEY, actor VARCHAR(80) NOT NULL,
  action VARCHAR(60) NOT NULL, aggregate_id VARCHAR(100) NOT NULL,
  detail VARCHAR(500) NOT NULL, created_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE session_checker (
  session_id VARCHAR(36) NOT NULL, user_id VARCHAR(80) NOT NULL,
  PRIMARY KEY(session_id,user_id), FOREIGN KEY(session_id) REFERENCES event_session(id),
  FOREIGN KEY(user_id) REFERENCES app_user(id)
);

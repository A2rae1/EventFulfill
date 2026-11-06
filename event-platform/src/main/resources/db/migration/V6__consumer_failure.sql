CREATE TABLE consumer_failure (
  consumer_name VARCHAR(80) NOT NULL,event_id VARCHAR(100) NOT NULL,
  observations INT NOT NULL,last_reconsume INT NOT NULL,error_class VARCHAR(160) NOT NULL,
  status VARCHAR(20) NOT NULL,updated_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY(consumer_name,event_id)
);

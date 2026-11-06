CREATE INDEX ix_order_session_status ON booking_order(session_id,status);
CREATE INDEX ix_wait_session_status ON waitlist_entry(session_id,status);

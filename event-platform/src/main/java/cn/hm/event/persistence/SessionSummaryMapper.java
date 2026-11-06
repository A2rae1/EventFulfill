package cn.hm.event.persistence;

import org.apache.ibatis.annotations.*;

public interface SessionSummaryMapper {
    record Counts(String sessionId,int capacity,int occupied,long requests,long waiting,long checkedIn){}
    @Options(timeout=2)
    @Select("SELECT s.id AS session_id,s.capacity,s.used AS occupied,(SELECT COUNT(*) FROM booking_request r WHERE r.session_id=s.id) AS requests,(SELECT COUNT(*) FROM waitlist_entry w WHERE w.session_id=s.id AND w.status IN ('WAITING','OFFERED')) AS waiting,(SELECT COUNT(*) FROM booking_order o JOIN admission_ticket t ON t.order_id=o.id WHERE o.session_id=s.id AND t.status='CHECKED_IN') AS checked_in FROM event_session s WHERE s.id=#{id}")
    Counts summary(String id);
}

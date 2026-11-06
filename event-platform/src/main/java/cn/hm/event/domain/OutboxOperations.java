package cn.hm.event.domain;

import cn.hm.event.persistence.EventMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxOperations {
    private final JdbcTemplate jdbc;private final EventMapper events;private final BookingService time;
    public OutboxOperations(JdbcTemplate jdbc,EventMapper events,BookingService time){this.jdbc=jdbc;this.events=events;this.time=time;}
    public List<Map<String,Object>> pending(){return jdbc.queryForList("SELECT id,topic,aggregate_id,status,attempts,available_at,last_error FROM outbox_event WHERE status='DEAD' OR id IN (SELECT event_id FROM consumer_failure WHERE status='FAILED') ORDER BY created_at LIMIT 100");}
    @Transactional
    public void replay(String operator,String id,String reason){
        int changed=jdbc.update("UPDATE outbox_event SET status='PENDING',attempts=0,available_at=?,lease_until=NULL,lease_token=NULL,last_error=NULL WHERE id=? AND (status='DEAD' OR (status='SENT' AND EXISTS(SELECT 1 FROM consumer_failure f WHERE f.event_id=outbox_event.id AND f.status='FAILED')))",time.now(),id);
        if(changed!=1)throw DomainException.conflict("事件未失败或已被其他操作重放，请刷新");
        events.audit(BookingService.id(),operator,"OUTBOX_REPLAY",id,reason,time.now());
    }
}

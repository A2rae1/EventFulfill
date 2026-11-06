package cn.hm.event.messaging;

import cn.hm.event.domain.BookingService;
import cn.hm.event.domain.Rows.*;
import cn.hm.event.persistence.EventMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventConsumer {
    private final EventMapper db; private final BookingService booking; private final AgentWakeClient agent;
    public EventConsumer(EventMapper db,BookingService booking,AgentWakeClient agent) { this.db=db;this.booking=booking;this.agent=agent; }
    @Transactional(rollbackFor=Exception.class)
    public void consume(String eventId,String topic,String aggregate,String payload) throws Exception {
        // All business effects for a session follow its row lock. Receipt uniqueness also covers concurrent redelivery.
        if(db.received("rocketmq",eventId)>0) return;
        switch(topic) {
            case "BOOKING_ACCEPTED" -> booking.allocate(payload);
            case "ORDER_DEADLINE" -> booking.expire(aggregate);
            case "AGENT_JOB_CREATED" -> agent.wake(aggregate);
            case "AGENT_JOB_CANCELLED" -> agent.cancel(aggregate);
            default -> {
                if(topic.startsWith("ORDER_")||topic.equals("WAITLIST_JOINED"))
                    db.notify(BookingService.id(),payload,eventId,topic+":"+aggregate,booking.now());
            }
        }
        db.receipt("rocketmq",eventId,booking.now());
    }
}

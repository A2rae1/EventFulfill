package cn.hm.event.messaging;

import cn.hm.event.domain.BookingService;
import cn.hm.event.domain.Rows.*;
import cn.hm.event.persistence.EventMapper;
import java.time.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Local durable adapter for development only. It is explicitly not a RocketMQ integration. */
@Component
@ConditionalOnProperty(name="app.jobs-enabled",havingValue="true",matchIfMissing=true)
public class LocalDispatcher {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(LocalDispatcher.class);
    public record ReconcileReport(int attempted,int failed){}
    private final EventMapper db; private final BookingService booking; private final TransactionTemplate tx;
    public LocalDispatcher(EventMapper db,BookingService booking,TransactionTemplate tx) { this.db=db; this.booking=booking; this.tx=tx; }
    @Scheduled(fixedDelay=700)
    public void tick() {
        if(!isLocal()) return;
        for(Outbox e:db.dispatchable(booking.now())) {
            String token=BookingService.id();
            if(db.leaseEvent(e.id(),token,booking.now(),booking.now().plusSeconds(30))!=1) continue;
            try {
                tx.executeWithoutResult(s -> {
                    if(db.received("local",e.id())==0) {
                        if(e.topic().equals("BOOKING_ACCEPTED")) booking.allocate(e.payload());
                        else {
                            String user=e.payload();
                            if(e.topic().startsWith("ORDER_")||e.topic().equals("WAITLIST_JOINED"))
                                db.notify(BookingService.id(),user,e.id(),e.topic()+":"+e.aggregateId(),booking.now());
                        }
                        db.receipt("local",e.id(),booking.now());
                    }
                    db.sent(e.id(),token);
                });
            } catch(Exception ex) {
                db.retry(e.id(),token,e.attempts()>=9?"DEAD":"PENDING",ex.getClass().getSimpleName(),booking.now().plusSeconds(Math.min(60,1L<<Math.min(e.attempts(),6))));
            }
        }
    }
    @org.springframework.beans.factory.annotation.Value("${app.messaging:local}") private String messaging;
    private boolean isLocal() { return "local".equals(messaging); }
    /** Independent DB scan is the recovery path even when a delay message is lost. */
    @org.springframework.beans.factory.annotation.Value("${app.reconcile-enabled:true}") private boolean reconcileEnabled;
    @Scheduled(fixedDelay=2000)
    public void reconcile() {
        if(!reconcileEnabled) return;
        reconcileNow();
    }
    /** Same bounded, idempotent business scan is callable by the managed scheduler. */
    public ReconcileReport reconcileNow() {
        int attempted=0,failed=0;
        for(Order o:db.expired(booking.now())) {
            attempted++;
            try{booking.expire(o.id());}catch(Exception fault){failed++;log.warn("Reconciliation order {} failed: {}",o.id(),fault.getClass().getSimpleName());}
        }
        for(String session:db.pendingSessions()) {
            attempted++;
            try{booking.allocate(session);}catch(Exception fault){failed++;log.warn("Reconciliation session {} failed: {}",session,fault.getClass().getSimpleName());}
        }
        return new ReconcileReport(attempted,failed);
    }
}

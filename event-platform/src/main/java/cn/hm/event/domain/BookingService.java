package cn.hm.event.domain;

import cn.hm.event.domain.Rows.*;
import cn.hm.event.persistence.EventMapper;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable capacity and all competing transitions share the session lock, in the same order. */
@Service
public class BookingService {
    private final EventMapper db;
    private final Clock clock;
    public BookingService(EventMapper db, Clock clock) { this.db=db; this.clock=clock; }
    public LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC); }
    public static String id() { return UUID.randomUUID().toString(); }
    private Session locked(String id) { Session s=db.lockSession(id); if(s==null) throw DomainException.missing(); return s; }
    private void owns(String owner,String actor) { if(!owner.equals(actor)) throw DomainException.forbidden(); }
    private void event(String topic,String aggregate,String payload) { db.outbox(id(),topic,aggregate,payload,now()); }
    private void audit(String actor,String action,String aggregate,String detail) { db.audit(id(),actor,action,aggregate,detail,now()); }

    @Transactional
    public Activity createActivity(String actor,String title,String description) {
        Activity a=new Activity(id(),actor,cleanTitle(title),Objects.requireNonNullElse(description,""),now());
        db.insertActivity(a); audit(actor,"ACTIVITY_CREATED",a.id(),a.title()); return a;
    }
    private String cleanTitle(String title) {
        if(title==null || title.isBlank() || title.strip().length()>120) throw new DomainException(400,"INVALID_TITLE","标题长度须为1–120");
        return title.strip();
    }
    @Transactional
    public Session createSession(String actor,String activityId,String title,int capacity,int price,int seconds) {
        Activity a=db.activity(activityId); if(a==null) throw DomainException.missing(); owns(a.ownerId(),actor);
        if(capacity<1 || capacity>100000 || price<0 || seconds<1 || seconds>86400) throw new DomainException(400,"INVALID_SESSION","容量、价格或确认期限不合法");
        Session s=new Session(id(),activityId,actor,cleanTitle(title),capacity,0,price,seconds,false,0);
        db.insertSession(s); audit(actor,"SESSION_CREATED",s.id(),"capacity="+capacity); return db.session(s.id());
    }
    @Transactional
    public Session publish(String actor,String sessionId) {
        Session s=locked(sessionId); owns(s.ownerId(),actor); db.publish(sessionId);
        audit(actor,"SESSION_PUBLISHED",sessionId,s.title()); return db.session(sessionId);
    }

    /** Request acceptance is durable, not a promise that a place was allocated. */
    @Transactional
    public Booking submit(String user,String sessionId,String key) {
        if(key==null || key.isBlank() || key.length()>100) throw new DomainException(400,"IDEMPOTENCY_KEY","需要1–100字符的幂等键");
        Session s=locked(sessionId);
        Booking previous=db.byKey(user,key);
        if(previous!=null) {
            if(!previous.sessionId().equals(sessionId)) throw DomainException.conflict("同一幂等键不可用于不同场次");
            return previous;
        }
        if(!s.published()) throw DomainException.conflict("场次尚未发布");
        Booking active=db.active(sessionId,user);
        if(active!=null) return active;
        Booking b=new Booking(0,id(),sessionId,user,key,"ACCEPTED",null,now());
        db.insertRequest(b); db.claim(sessionId,user,b.id());
        event("BOOKING_ACCEPTED",b.id(),sessionId); audit(user,"BOOKING_ACCEPTED",b.id(),sessionId);
        return db.request(b.id());
    }

    @Transactional
    public void allocate(String sessionId) {
        locked(sessionId);
        promote(sessionId);
        for(Booking b:db.pending(sessionId)) {
            if(db.firstWait(sessionId)==null && db.take(sessionId)==1) hold(b,"DIRECT");
            else {
                db.insertWait(new WaitEntry(id(),b.seq(),b.id(),sessionId,b.userId(),"WAITING"));
                db.requestState(b.id(),"WAITING",null);
                event("WAITLIST_JOINED",b.id(),b.userId());
            }
        }
    }
    private void hold(Booking b,String kind) {
        Order o=new Order(id(),b.id(),b.sessionId(),b.userId(),"HELD",kind,
                db.session(b.sessionId()).priceCents(),now().plusSeconds(db.session(b.sessionId()).holdSeconds()),0);
        db.insertOrder(o); db.allocation(o.id(),o.sessionId()); db.requestState(b.id(),"HELD",o.id());
        event("ORDER_HELD",o.id(),b.userId());
        db.outbox(id(),"ORDER_DEADLINE",o.id(),b.userId(),o.expiresAt());
        audit("system","ORDER_HELD",o.id(),kind);
    }
    private void promote(String sessionId) {
        while(true) {
            WaitEntry w=db.firstWait(sessionId); if(w==null || db.take(sessionId)==0) return;
            db.waitState(w.requestId(),"OFFERED"); hold(db.request(w.requestId()),"WAITLIST_OFFER");
        }
    }
    private Order lockOrder(String id) {
        Order o=db.order(id); if(o==null) throw DomainException.missing(); locked(o.sessionId()); return db.currentOrder(id);
    }
    @Transactional
    public Order confirm(String user,String orderId) {
        Order o=lockOrder(orderId); owns(o.userId(),user);
        if(o.priceCents()>0) throw DomainException.conflict("付费订单须通过模拟支付回调确认");
        if(o.status().equals("CONFIRMED")) return o;
        if(!o.status().equals("HELD")) throw DomainException.conflict("订单不可确认");
        if(!o.expiresAt().isAfter(now())) { expireLocked(o); return db.order(orderId); }
        confirmLocked(o); return db.order(orderId);
    }
    private void confirmLocked(Order o) {
        if(db.orderState(o.id(),"HELD","CONFIRMED")!=1 || db.allocationState(o.id(),"HELD","CONFIRMED")!=1)
            throw DomainException.conflict("确认状态竞争");
        db.requestState(o.requestId(),"CONFIRMED",o.id()); db.waitState(o.requestId(),"CONFIRMED");
        db.insertTicket(new Ticket(id(),o.id(),o.userId(),id()+id(),"ISSUED",null));
        event("ORDER_CONFIRMED",o.id(),o.userId()); audit(o.userId(),"ORDER_CONFIRMED",o.id(),"ticket issued");
    }
    @Transactional
    public Booking cancelRequest(String user,String requestId) {
        Booking b=db.request(requestId); if(b==null) throw DomainException.missing();
        locked(b.sessionId()); b=db.currentRequest(requestId); owns(b.userId(),user);
        if(b.orderId()!=null) { cancelLocked(db.order(b.orderId()),user); return db.request(requestId); }
        if(b.status().equals("CANCELLED") || b.status().equals("EXPIRED")) return b;
        if(!Set.of("ACCEPTED","WAITING").contains(b.status())) throw DomainException.conflict("请求不可取消");
        db.requestState(b.id(),"CANCELLED",null); db.waitState(b.id(),"CANCELLED"); db.unclaim(b.id());
        audit(user,"REQUEST_CANCELLED",b.id(),b.status()); return db.request(requestId);
    }
    @Transactional
    public Order cancelOrder(String user,String orderId) {
        Order o=lockOrder(orderId); owns(o.userId(),user); cancelLocked(o,user); return db.order(orderId);
    }
    private void cancelLocked(Order o,String user) {
        if(Set.of("CANCELLED","EXPIRED","REFUNDED").contains(o.status())) return;
        Ticket t=db.ticketByOrder(o.id());
        if(t!=null && t.status().equals("CHECKED_IN")) throw DomainException.conflict("已入场票据不可取消");
        if(!Set.of("HELD","CONFIRMED").contains(o.status())) throw DomainException.conflict("订单不可取消");
        String next=o.status().equals("CONFIRMED") && o.priceCents()>0?"REFUNDED":"CANCELLED";
        if(db.orderState(o.id(),o.status(),next)!=1) throw DomainException.conflict("取消状态竞争");
        if(t!=null) db.ticketState(t.id(),"ISSUED","INVALID",null);
        releaseAllocation(o,o.status()); db.requestState(o.requestId(),next,o.id());
        db.waitState(o.requestId(),"CANCELLED"); db.unclaim(o.requestId());
        event(next.equals("REFUNDED")?"MOCK_REFUND_REQUESTED":"ORDER_CANCELLED",o.id(),user);
        audit(user,"ORDER_"+next,o.id(),"simulation only"); promote(o.sessionId());
    }
    private void releaseAllocation(Order o,String expected) {
        if(db.allocationState(o.id(),expected,"RELEASED")!=1 || db.release(o.sessionId())!=1)
            throw new IllegalStateException("Allocation invariant violated: "+o.id());
    }
    @Transactional
    public void expire(String orderId) { Order o=lockOrder(orderId); if(o.status().equals("HELD")&&!o.expiresAt().isAfter(now())) expireLocked(o); }
    private void expireLocked(Order o) {
        if(db.orderState(o.id(),"HELD","EXPIRED")!=1) return;
        releaseAllocation(o,"HELD"); db.requestState(o.requestId(),"EXPIRED",o.id());
        db.waitState(o.requestId(),"EXPIRED"); db.unclaim(o.requestId());
        event("ORDER_EXPIRED",o.id(),o.userId()); audit("system","ORDER_EXPIRED",o.id(),"deadline passed"); promote(o.sessionId());
    }
    /** Idempotency covers event identity AND payload; a late success is compensated, never reclaims a place. */
    @Transactional
    public Map<String,Object> payment(String eventId,String orderId,int amount) {
        Order o=lockOrder(orderId);
        Map<String,Object> prior=db.payment(eventId);
        if(prior!=null) {
            if(!Objects.equals(prior.get("order_id"),orderId) && !Objects.equals(prior.get("ORDER_ID"),orderId))
                throw DomainException.conflict("回调编号已用于其他订单");
            Object oldAmount=prior.containsKey("amount_cents")?prior.get("amount_cents"):prior.get("AMOUNT_CENTS");
            if(((Number)oldAmount).intValue()!=amount) throw DomainException.conflict("回调金额不一致");
            return prior;
        }
        if(o.priceCents()<=0 || amount!=o.priceCents()) throw DomainException.conflict("支付金额与订单不一致");
        String outcome;
        if(o.status().equals("HELD") && o.expiresAt().isAfter(now())) { confirmLocked(o); outcome="APPLIED"; }
        else if(o.status().equals("CONFIRMED")) { outcome="DUPLICATE_PAYMENT_REFUND"; event("MOCK_REFUND_REQUESTED",o.id(),eventId); }
        else {
            if(o.status().equals("HELD")) expireLocked(o);
            outcome="LATE_PAYMENT_REFUND"; event("MOCK_REFUND_REQUESTED",o.id(),eventId);
        }
        db.insertPayment(eventId,orderId,outcome,amount,now()); audit("mock-gateway","PAYMENT_"+outcome,orderId,eventId);
        return db.payment(eventId);
    }
    @Transactional
    public Ticket checkIn(String organizer,String token) {
        Ticket t=db.ticketByToken(token); if(t==null) throw DomainException.missing();
        Order o=lockOrder(t.orderId());
        if(!db.session(o.sessionId()).ownerId().equals(organizer) && db.isChecker(o.sessionId(),organizer)==0) throw DomainException.forbidden();
        t=db.ticketByToken(token);
        if(!o.status().equals("CONFIRMED") || db.ticketState(t.id(),"ISSUED","CHECKED_IN",now())!=1)
            throw DomainException.conflict("票据无效或已核验");
        audit(organizer,"CHECKED_IN",o.id(),t.id()); return db.ticketByOrder(o.id());
    }
    public Booking request(String user,String id) { Booking b=db.request(id); if(b==null) throw DomainException.missing(); owns(b.userId(),user); return b; }
    public Order order(String user,String id) { Order o=db.order(id); if(o==null) throw DomainException.missing(); owns(o.userId(),user); return o; }
    public Ticket ticket(String user,String orderId) { order(user,orderId); Ticket t=db.ticketByOrder(orderId); if(t==null) throw DomainException.missing(); return t; }
    @Transactional public void checker(String owner,String sessionId,String userId,boolean grant) {
        Session s=locked(sessionId);owns(s.ownerId(),owner);
        User u=db.user(userId);if(u==null||!u.role().equals("CHECKER"))throw new DomainException(400,"INVALID_CHECKER","该账号不是核验人员");
        if(grant)db.grantChecker(sessionId,userId);else db.revokeChecker(sessionId,userId);
        audit(owner,grant?"CHECKER_GRANTED":"CHECKER_REVOKED",sessionId,userId);
    }
}

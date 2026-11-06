package cn.hm.event;

import cn.hm.event.domain.*;
import cn.hm.event.domain.Rows.*;
import cn.hm.event.messaging.LocalDispatcher;
import cn.hm.event.persistence.EventMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** These are transactional application integration tests on H2; real MySQL runs are recorded separately. */
@SpringBootTest @ActiveProfiles("test")
class BookingIntegrationTest {
    @Autowired BookingService service; @Autowired EventMapper db; @Autowired JdbcTemplate jdbc; @Autowired MutableClock clock;
    @TestConfiguration static class Config { @Bean @Primary MutableClock controlledClock() { return new MutableClock(); } }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> value=new AtomicReference<>(Instant.parse("2026-11-05T12:00:00Z"));
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return value.get(); }
        void advance(long seconds) { value.updateAndGet(i->i.plusSeconds(seconds)); }
    }
    Session session(int capacity,int price,int ttl) {
        Activity a=service.createActivity("owner","活动","测试");
        Session s=service.createSession("owner",a.id(),"场次",capacity,price,ttl); return service.publish("owner",s.id());
    }
    Booking book(Session s,String user) { Booking b=service.submit(user,s.id(),UUID.randomUUID().toString()); service.allocate(s.id()); return db.request(b.id()); }
    void invariant(String sessionId) {
        Integer active=jdbc.queryForObject("SELECT COUNT(*) FROM allocation WHERE session_id=? AND status IN ('HELD','CONFIRMED')",Integer.class,sessionId);
        Session s=db.session(sessionId); assertThat(s.used()).isEqualTo(active).isBetween(0,s.capacity());
    }
    @Test void freeLifecycleAndOneTimeCheckIn() {
        Session s=session(1,0,60); Booking b=book(s,"alice");
        assertThat(b.status()).isEqualTo("HELD"); assertThat(service.confirm("alice",b.orderId()).status()).isEqualTo("CONFIRMED");
        assertThat(service.confirm("alice",b.orderId()).status()).isEqualTo("CONFIRMED");
        Ticket t=service.ticket("alice",b.orderId()); assertThat(service.checkIn("owner",t.token()).status()).isEqualTo("CHECKED_IN");
        assertThatThrownBy(()->service.checkIn("owner",t.token())).isInstanceOf(DomainException.class);
        assertThatThrownBy(()->service.cancelOrder("alice",b.orderId())).isInstanceOf(DomainException.class); invariant(s.id());
    }
    @Test void waitlistIsFifoAndCannotBeOvertakenByNewRequest() {
        Session s=session(1,0,60); Booking a=book(s,"a"),b=book(s,"b"),c=book(s,"c");
        assertThat(b.status()).isEqualTo("WAITING"); assertThat(c.status()).isEqualTo("WAITING");
        service.cancelOrder("a",a.orderId());
        assertThat(db.request(b.id()).status()).isEqualTo("HELD"); assertThat(db.request(c.id()).status()).isEqualTo("WAITING");
        assertThat(book(s,"d").status()).isEqualTo("WAITING");
        clock.advance(61); service.expire(db.request(b.id()).orderId());
        assertThat(db.request(c.id()).status()).isEqualTo("HELD"); invariant(s.id());
    }
    @Test void expirationAndLatePaidCallbackNeverReclaimsPlace() {
        Session s=session(1,1000,30); Booking a=book(s,"a"),b=book(s,"b"); clock.advance(31);
        assertThat(service.payment("event-1-"+a.id(),a.orderId(),1000).values()).contains("LATE_PAYMENT_REFUND");
        assertThat(db.order(a.orderId()).status()).isEqualTo("EXPIRED"); assertThat(db.request(b.id()).status()).isEqualTo("HELD");
        service.payment("event-1-"+a.id(),a.orderId(),1000); invariant(s.id());
    }
    @Test void signedGatewayBusinessIdempotencyAndRefundInvalidatesTicket() {
        Session s=session(1,500,60); Booking a=book(s,"a"); String ev="paid-"+a.id();
        service.payment(ev,a.orderId(),500); service.payment(ev,a.orderId(),500);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admission_ticket WHERE order_id=?",Integer.class,a.orderId())).isEqualTo(1);
        assertThatThrownBy(()->service.payment(ev,a.orderId(),501)).isInstanceOf(DomainException.class);
        assertThat(service.cancelOrder("a",a.orderId()).status()).isEqualTo("REFUNDED");
        assertThat(service.ticket("a",a.orderId()).status()).isEqualTo("INVALID"); invariant(s.id());
    }
    @Test void requestDeduplicationAndRebookingAfterCancel() {
        Session s=session(1,0,60); String key="first-"+s.id();
        Booking first=service.submit("a",s.id(),key);
        assertThat(service.submit("a",s.id(),key).id()).isEqualTo(first.id());
        assertThat(service.submit("a",s.id(),"new-key-"+s.id()).id()).isEqualTo(first.id());
        service.cancelRequest("a",first.id()); service.allocate(s.id()); assertThat(db.session(s.id()).used()).isZero();
        assertThat(service.submit("a",s.id(),"after-cancel-"+s.id()).id()).isNotEqualTo(first.id()); invariant(s.id());
    }
    @Test void acceptanceDoesNotMeanAllocationAndCancelPendingWorks() {
        Session s=session(1,0,60); Booking b=service.submit("a",s.id(),"accept-"+s.id());
        assertThat(b.status()).isEqualTo("ACCEPTED"); assertThat(b.orderId()).isNull();
        service.cancelRequest("a",b.id()); service.allocate(s.id()); invariant(s.id());
    }
    @Test void unauthorizedMutationHasNoSideEffects() {
        Session s=session(1,0,60); Booking b=book(s,"a");
        assertThatThrownBy(()->service.confirm("b",b.orderId())).isInstanceOf(DomainException.class);
        assertThatThrownBy(()->service.publish("other",s.id())).isInstanceOf(DomainException.class);
        assertThat(db.order(b.orderId()).status()).isEqualTo("HELD"); invariant(s.id());
    }
    @Test void checkerPermissionIsExplicitAndSessionScoped() {
        String user="checker-"+UUID.randomUUID();db.insertUser(new User(user,"test-hash","CHECKER"));
        Session s=session(1,0,60);Booking b=book(s,"a");service.confirm("a",b.orderId());Ticket t=service.ticket("a",b.orderId());
        assertThatThrownBy(()->service.checkIn(user,t.token())).isInstanceOf(DomainException.class);
        service.checker("owner",s.id(),user,true);service.checker("owner",s.id(),user,false);
        assertThatThrownBy(()->service.checkIn(user,t.token())).isInstanceOf(DomainException.class);
        service.checker("owner",s.id(),user,true);assertThat(service.checkIn(user,t.token()).status()).isEqualTo("CHECKED_IN");
    }
    @Test void simultaneousBookingsNeverOversell() throws Exception {
        Session s=session(7,0,60);
        try(ExecutorCloser pool=new ExecutorCloser(12)) {
            List<Callable<Void>> calls=IntStream.range(0,40).mapToObj(i->(Callable<Void>)()->{ book(s,"user-"+i); return null; }).toList();
            for(Future<Void> f:pool.executor.invokeAll(calls)) f.get(30,TimeUnit.SECONDS);
        }
        assertThat(db.session(s.id()).used()).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM waitlist_entry WHERE session_id=? AND status='WAITING'",Integer.class,s.id())).isEqualTo(33); invariant(s.id());
    }
    @Test void confirmVersusExpiryAndRepeatedReleasePreservesInvariant() throws Exception {
        Session s=session(1,0,1); Booking a=book(s,"a"); clock.advance(2);
        try(ExecutorCloser pool=new ExecutorCloser(2)) {
            List<Callable<Void>> calls=List.of(()->{service.confirm("a",a.orderId());return null;},()->{service.expire(a.orderId());return null;});
            for(Future<Void> f:pool.executor.invokeAll(calls)) f.get();
        }
        service.expire(a.orderId()); service.cancelOrder("a",a.orderId());
        assertThat(db.session(s.id()).used()).isZero(); assertThat(db.order(a.orderId()).status()).isEqualTo("EXPIRED"); invariant(s.id());
    }
    @Test void transactionalOutboxRollsBackWithFailedTransaction() {
        Session s=session(1,0,60); Booking a=book(s,"a"); service.confirm("a",a.orderId());
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event",Integer.class);
        assertThatThrownBy(()->service.payment("bad",a.orderId(),999)).isInstanceOf(DomainException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event",Integer.class)).isEqualTo(before);
    }
    static class ExecutorCloser implements AutoCloseable {
        final ExecutorService executor; ExecutorCloser(int n) { executor=Executors.newFixedThreadPool(n); }
        public void close() { executor.shutdownNow(); }
    }
}

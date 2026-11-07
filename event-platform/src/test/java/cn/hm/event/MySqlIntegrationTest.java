package cn.hm.event;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Runs the same capacity/transition suite against an independent real MySQL schema, only when opted
 * in.
 */
@EnabledIfEnvironmentVariable(named = "EVENT_MYSQL_IT", matches = "true")
@ActiveProfiles({"test", "mysql-it"})
@Import(BookingIntegrationTest.Config.class)
class MySqlIntegrationTest extends BookingIntegrationTest {
  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.transaction.PlatformTransactionManager transactions;

  @org.junit.jupiter.api.Test
  void oldRepeatableReadSnapshotCannotRewriteAlreadyAllocatedRequests() {
    var session = session(1, 0, 300);
    var first = service.submit("snapshot-first", session.id(), "snapshot-first-" + session.id());
    var second = service.submit("snapshot-second", session.id(), "snapshot-second-" + session.id());
    var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
    tx.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    tx.executeWithoutResult(
        status -> {
          // MQ receipt lookup establishes a consistent-read snapshot before another allocator
          // commits.
          db.received("snapshot-test", first.id());
          java.util.concurrent.CompletableFuture.runAsync(() -> service.allocate(session.id()))
              .join();
          service.allocate(session.id());
          org.assertj.core.api.Assertions.assertThat(db.currentRequest(first.id()).status())
              .isEqualTo("HELD");
          org.assertj.core.api.Assertions.assertThat(db.currentRequest(first.id()).orderId())
              .isNotNull();
          org.assertj.core.api.Assertions.assertThat(db.currentRequest(second.id()).status())
              .isEqualTo("WAITING");
        });
    invariant(session.id());
  }

  private void concurrent(Runnable action) {
    try {
      java.util.concurrent.CompletableFuture.runAsync(action)
          .get(15, java.util.concurrent.TimeUnit.SECONDS);
    } catch (Exception error) {
      throw new AssertionError("Concurrent transaction did not complete", error);
    }
  }

  private org.springframework.transaction.support.TransactionTemplate repeatableRead() {
    var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
    tx.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    return tx;
  }

  @org.junit.jupiter.api.Test
  void cancellationCannotReleaseAnAlreadyCheckedInTicketFromAnOldSnapshot() {
    var s = session(1, 0, 300);
    var b = book(s, "rr-checkin");
    service.confirm("rr-checkin", b.orderId());
    var ticket = service.ticket("rr-checkin", b.orderId());
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                repeatableRead()
                    .executeWithoutResult(
                        status -> {
                          db.order(b.orderId()); // Same initial non-locking lookup as cancelOrder.
                          concurrent(() -> service.checkIn("owner", ticket.token()));
                          service.cancelOrder("rr-checkin", b.orderId());
                        }))
        .isInstanceOf(cn.hm.event.domain.DomainException.class);
    org.assertj.core.api.Assertions.assertThat(db.order(b.orderId()).status())
        .isEqualTo("CONFIRMED");
    org.assertj.core.api.Assertions.assertThat(db.ticketByOrder(b.orderId()).status())
        .isEqualTo("CHECKED_IN");
    org.assertj.core.api.Assertions.assertThat(db.session(s.id()).used()).isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND"
                    + " topic='ORDER_CANCELLED'",
                Integer.class,
                b.orderId()))
        .isZero();
    invariant(s.id());
  }

  @org.junit.jupiter.api.Test
  void requestCancellationSeesAnOrderAllocatedAfterItsFirstRead() {
    var s = session(1, 0, 300);
    var b = service.submit("rr-allocate", s.id(), "k-" + s.id());
    repeatableRead()
        .executeWithoutResult(
            status -> {
              org.assertj.core.api.Assertions.assertThat(db.request(b.id()).orderId()).isNull();
              concurrent(() -> service.allocate(s.id()));
              org.assertj.core.api.Assertions.assertThat(
                      service.cancelRequest("rr-allocate", b.id()).status())
                  .isEqualTo("CANCELLED");
            });
    org.assertj.core.api.Assertions.assertThat(db.order(db.request(b.id()).orderId()).status())
        .isEqualTo("CANCELLED");
    org.assertj.core.api.Assertions.assertThat(db.session(s.id()).used()).isZero();
    invariant(s.id());
  }

  @org.junit.jupiter.api.Test
  void cancellationInvalidatesATicketIssuedAfterItsInitialSnapshot() {
    var s = session(1, 0, 300);
    var b = book(s, "rr-confirm");
    repeatableRead()
        .executeWithoutResult(
            status -> {
              db.order(b.orderId());
              concurrent(() -> service.confirm("rr-confirm", b.orderId()));
              org.assertj.core.api.Assertions.assertThat(
                      service.cancelRequest("rr-confirm", b.id()).status())
                  .isEqualTo("CANCELLED");
            });
    org.assertj.core.api.Assertions.assertThat(db.ticketByOrder(b.orderId()).status())
        .isEqualTo("INVALID");
    org.assertj.core.api.Assertions.assertThat(db.session(s.id()).used()).isZero();
    invariant(s.id());
  }

  @org.junit.jupiter.api.Test
  void promotionReadsAWaiterCreatedAfterTheCancellationSnapshot() {
    var s = session(1, 0, 300);
    var first = book(s, "rr-first");
    var waiting =
        new java.util.concurrent.atomic.AtomicReference<cn.hm.event.domain.Rows.Booking>();
    repeatableRead()
        .executeWithoutResult(
            status -> {
              db.order(first.orderId());
              concurrent(() -> waiting.set(book(s, "rr-waiter")));
              service.cancelOrder("rr-first", first.orderId());
              org.assertj.core.api.Assertions.assertThat(
                      db.currentRequest(waiting.get().id()).status())
                  .isEqualTo("HELD");
            });
    org.assertj.core.api.Assertions.assertThat(
            db.order(db.request(waiting.get().id()).orderId()).kind())
        .isEqualTo("WAITLIST_OFFER");
    invariant(s.id());
  }

  @org.junit.jupiter.api.Test
  void duplicatePaymentReadsTheReceiptCommittedAfterItsSnapshot() {
    var s = session(1, 200, 300);
    var b = book(s, "rr-payment");
    var event = "payment-" + b.id();
    repeatableRead()
        .executeWithoutResult(
            status -> {
              org.assertj.core.api.Assertions.assertThat(db.payment(event)).isNull();
              concurrent(() -> service.payment(event, b.orderId(), 200));
              service.payment(event, b.orderId(), 200);
            });
    org.assertj.core.api.Assertions.assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_event WHERE event_id=?", Integer.class, event))
        .isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND"
                    + " topic='MOCK_REFUND_REQUESTED'",
                Integer.class,
                b.orderId()))
        .isZero();
    invariant(s.id());
  }
}

package cn.hm.event;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** Runs the same capacity/transition suite against an independent real MySQL schema, only when opted in. */
@EnabledIfEnvironmentVariable(named="EVENT_MYSQL_IT",matches="true")
@ActiveProfiles({"test","mysql-it"})
@Import(BookingIntegrationTest.Config.class)
class MySqlIntegrationTest extends BookingIntegrationTest {
    @org.springframework.beans.factory.annotation.Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @org.junit.jupiter.api.Test void oldRepeatableReadSnapshotCannotRewriteAlreadyAllocatedRequests() {
        var session=session(1,0,300);
        var first=service.submit("snapshot-first",session.id(),"snapshot-first-"+session.id());
        var second=service.submit("snapshot-second",session.id(),"snapshot-second-"+session.id());
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.executeWithoutResult(status->{
            // MQ receipt lookup establishes a consistent-read snapshot before another allocator commits.
            db.received("snapshot-test",first.id());
            java.util.concurrent.CompletableFuture.runAsync(()->service.allocate(session.id())).join();
            service.allocate(session.id());
            org.assertj.core.api.Assertions.assertThat(db.currentRequest(first.id()).status()).isEqualTo("HELD");
            org.assertj.core.api.Assertions.assertThat(db.currentRequest(first.id()).orderId()).isNotNull();
            org.assertj.core.api.Assertions.assertThat(db.currentRequest(second.id()).status()).isEqualTo("WAITING");
        });
        invariant(session.id());
    }
}

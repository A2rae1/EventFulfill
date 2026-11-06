package cn.hm.event;

import cn.hm.event.domain.*;
import cn.hm.event.domain.Rows.*;
import cn.hm.event.messaging.EventConsumer;
import cn.hm.event.persistence.EventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@EnabledIfEnvironmentVariable(named="EVENT_MQ_IT",matches="true")
@ActiveProfiles({"test","mysql-it"})
@SpringBootTest(properties={"app.messaging=rocketmq","app.jobs-enabled=false","app.mq-topic=event_it_v1","app.mq-consumer-group=event-integration-v1",
    "spring.datasource.url=jdbc:mysql://127.0.0.1:13406/eventfulfill_mq_it?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"})
class RocketMqIntegrationTest {
    @Autowired BookingService booking;@Autowired EventMapper db;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;
    @Test void durableAcceptanceTraversesRealBrokerWithIdempotentConsumer() throws Exception {
        Activity a=booking.createActivity("owner","mq-roundtrip","");Session s=booking.createSession("owner",a.id(),"mq",1,0,60);booking.publish("owner",s.id());
        Booking b=booking.submit("mq-user",s.id(),UUID.randomUUID().toString());assertThat(b.status()).isEqualTo("ACCEPTED");
        // No scanner or direct allocate call in this test. Allocation can only arrive through the real broker.
        await().atMost(Duration.ofSeconds(35)).untilAsserted(()->assertThat(db.request(b.id()).status()).isEqualTo("HELD"));
        String event=jdbc.queryForObject("SELECT id FROM outbox_event WHERE aggregate_id=? AND topic='BOOKING_ACCEPTED'",String.class,b.id());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(db.received("rocketmq",event)).isEqualTo(1));
        DefaultMQProducer producer=new DefaultMQProducer("event-integration-duplicate-"+UUID.randomUUID());producer.setNamesrvAddr("127.0.0.1:19876");producer.start();
        try {
            byte[] bytes=json.writeValueAsBytes(Map.of("id",event,"topic","BOOKING_ACCEPTED","aggregate",b.id(),"payload",s.id()));
            producer.send(new Message("event_it_v1","BOOKING_ACCEPTED",event,bytes));
            producer.send(new Message("event_it_v1","BOOKING_ACCEPTED",event,bytes));
            Thread.sleep(1500);
            assertThat(db.session(s.id()).used()).isEqualTo(1);assertThat(db.received("rocketmq",event)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_order WHERE request_id=?",Integer.class,b.id())).isEqualTo(1);
        } finally {producer.shutdown();}
    }
    @Test void leaseTokenPreventsOldPublisherOverwritingNewLease() {
        String id=UUID.randomUUID().toString();var now=booking.now();db.outbox(id,"TEST_LEASE",id,"test",now);
        assertThat(db.leaseEvent(id,"old",now,now.plusSeconds(1))).isEqualTo(1);
        assertThat(db.leaseEvent(id,"new",now.plusSeconds(2),now.plusSeconds(30))).isEqualTo(1);
        assertThat(db.sent(id,"old")).isZero();assertThat(db.sent(id,"new")).isEqualTo(1);
    }
}

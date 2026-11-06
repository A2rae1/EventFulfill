package cn.hm.event;

import cn.hm.event.domain.*;
import cn.hm.event.messaging.*;
import cn.hm.event.persistence.EventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer;
import org.apache.rocketmq.client.consumer.PullStatus;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@EnabledIfEnvironmentVariable(named="EVENT_MQ_IT",matches="true")
@ActiveProfiles({"test","mysql-it"})
@SpringBootTest(properties={"app.messaging=rocketmq","app.jobs-enabled=false","app.mq-topic=event_recovery_it_v1","app.mq-consumer-group=event-recovery-it-v1","app.mq-max-retries=1","app.mq-retry-delay-level=1","spring.datasource.url=jdbc:mysql://127.0.0.1:13406/eventfulfill_mq_it?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"})
class MqRecoveryIntegrationTest {
    @TestConfiguration static class Config {
        @Bean @Primary FaultConsumer faultConsumer(EventMapper db,BookingService time,AgentWakeClient wake){return new FaultConsumer(db,time,wake);}
    }
    static class FaultConsumer extends EventConsumer {
        final Map<String,AtomicInteger> faults=new ConcurrentHashMap<>();
        FaultConsumer(EventMapper db,BookingService booking,AgentWakeClient wake){super(db,booking,wake);}
        public void fail(String id,int count){faults.put(id,new AtomicInteger(count));}
        public void clear(String id){faults.remove(id);}
        @Override @Transactional(rollbackFor=Exception.class)
        public void consume(String id,String topic,String aggregate,String payload)throws Exception{
            AtomicInteger remaining=faults.get(id);
            if(remaining!=null&&(remaining.get()<0||remaining.getAndDecrement()>0))throw new IllegalStateException("Integration-injected consumer failure");
            super.consume(id,topic,aggregate,payload);
        }
    }
    @Autowired FaultConsumer consumer;@Autowired EventMapper db;@Autowired BookingService clock;
    @Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;@Autowired OutboxOperations operations;
    private String prepare(){String id=UUID.randomUUID().toString();db.outbox(id,"ORDER_HELD",id,"recovery-fixture",clock.now().plusHours(1));jdbc.update("UPDATE outbox_event SET status='SENT' WHERE id=?",id);return id;}
    private void send(String id)throws Exception{
        DefaultMQProducer producer=new DefaultMQProducer("recovery-probe-"+UUID.randomUUID());producer.setNamesrvAddr("127.0.0.1:19876");producer.start();
        try{producer.send(new Message("event_recovery_it_v1","ORDER_HELD",id,json.writeValueAsBytes(Map.of("id",id,"topic","ORDER_HELD","aggregate",id,"payload","recovery-fixture"))));}finally{producer.shutdown();}
    }
    @Test void transientFailureIsActuallyRedeliveredAndAppliesBusinessOnce()throws Exception{
        String id=prepare();consumer.fail(id,1);send(id);
        await().atMost(Duration.ofSeconds(35)).untilAsserted(()->assertThat(db.received("rocketmq",id)).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT observations FROM consumer_failure WHERE event_id=?",Integer.class,id)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM consumer_failure WHERE event_id=?",String.class,id)).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE event_id=?",Integer.class,id)).isEqualTo(1);
    }
    @Test void poisonMessageReachesRealDlqThenAuditedReplayRecovers()throws Exception{
        String id=prepare();consumer.fail(id,-1);send(id);
        DefaultMQPullConsumer pull=new DefaultMQPullConsumer("dlq-inspect-"+UUID.randomUUID());pull.setNamesrvAddr("127.0.0.1:19876");pull.start();
        try{
            await().atMost(Duration.ofSeconds(40)).until(()->{
                try{for(var queue:pull.fetchSubscribeMessageQueues("%DLQ%event-recovery-it-v1")){
                    long offset=pull.searchOffset(queue,System.currentTimeMillis()-120_000);
                    var result=pull.pull(queue,"*",offset,64);
                    if(result.getPullStatus()==PullStatus.FOUND&&result.getMsgFoundList().stream().anyMatch(m->m.getKeys().equals(id)))return true;
                }}catch(Exception unavailable){return false;}return false;
            });
        }finally{pull.shutdown();}
        assertThat(db.received("rocketmq",id)).isZero();consumer.clear(id);
        operations.replay("operator-fixture",id,"fault removed after confirmed DLQ; controlled integration test");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(()->assertThat(db.received("rocketmq",id)).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE event_id=?",Integer.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE aggregate_id=? AND action='OUTBOX_REPLAY'",Integer.class,id)).isEqualTo(1);
    }
    @Test void lostPublishResultAndExpiredLeaseRepublishWithoutSecondBusinessEffect()throws Exception{
        String id=prepare();send(id);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(()->assertThat(db.received("rocketmq",id)).isEqualTo(1));
        jdbc.update("UPDATE outbox_event SET status='SENDING',lease_token='lost-publisher',lease_until=? WHERE id=?",clock.now().minusSeconds(1),id);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(()->assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE id=?",String.class,id)).isEqualTo("SENT"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE event_id=?",Integer.class,id)).isEqualTo(1);
    }
}

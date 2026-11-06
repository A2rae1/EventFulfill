package cn.hm.event.messaging;

import cn.hm.event.domain.BookingService;
import cn.hm.event.domain.Rows.Outbox;
import cn.hm.event.persistence.EventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.*;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.messaging",havingValue="rocketmq")
public class RocketMqAdapter {
    private final EventMapper db; private final BookingService booking; private final EventConsumer handler; private final ObjectMapper json;
    private final DefaultMQProducer producer=new DefaultMQProducer("event-outbox-v1");
    private final DefaultMQPushConsumer consumer=new DefaultMQPushConsumer("event-fulfill-v1");
    private final ConsumerFailures failures;
    private final MessageTracing tracing;private final io.micrometer.core.instrument.MeterRegistry metrics;
    @Value("${ROCKETMQ_NAMESRV:127.0.0.1:19876}") private String namesrv;
    @Value("${app.mq-topic:event_domain_v1}") private String topic;
    @Value("${app.mq-consumer-group:event-fulfill-v1}") private String group;
    @Value("${app.mq-max-retries:5}") private int maxRetries;
    @Value("${app.mq-retry-delay-level:0}") private int retryDelay;
    public RocketMqAdapter(EventMapper db,BookingService booking,EventConsumer handler,ObjectMapper json,ConsumerFailures failures,MessageTracing tracing,io.micrometer.core.instrument.MeterRegistry metrics) {this.db=db;this.booking=booking;this.handler=handler;this.json=json;this.failures=failures;this.tracing=tracing;this.metrics=metrics;}
    @PostConstruct public void start() throws Exception {
        producer.setNamesrvAddr(namesrv); producer.setInstanceName(BookingService.id()); producer.setSendMsgTimeout(5000); producer.setRetryTimesWhenSendFailed(1); producer.start();
        consumer.setConsumerGroup(group);consumer.setNamesrvAddr(namesrv); consumer.setInstanceName(BookingService.id()); consumer.setConsumeThreadMin(2); consumer.setConsumeThreadMax(4); consumer.setMaxReconsumeTimes(maxRetries);
        consumer.subscribe(topic,"*");
        consumer.registerMessageListener((MessageListenerConcurrently)(messages,context)->{
            for(var message:messages) {
                String eventId=message.getMsgId();
                try {
                    var e=json.readTree(message.getBody());
                    eventId=e.path("id").asText();
                    if(!eventId.matches("[a-fA-F0-9-]{36}"))throw new IllegalArgumentException("Invalid event identifier");
                    tracing.consume(message.getUserProperty("traceparent"),()->handler.consume(e.path("id").asText(),e.path("topic").asText(),e.path("aggregate").asText(),e.path("payload").asText()));
                    failures.resolved(group,eventId);
                    metrics.counter("event.mq.deliveries","result","ok").increment();
                } catch(Exception ex) {
                    try{failures.failed(group,eventId,message.getReconsumeTimes(),ex);}catch(Exception ignored){/* Business failure must still be retried if diagnostics storage is unavailable. */}
                    if(retryDelay>0)context.setDelayLevelWhenNextConsume(retryDelay);
                    metrics.counter("event.mq.deliveries","result","retry").increment();
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }); consumer.start();
    }
    @Scheduled(fixedDelay=700) public void dispatch() {
        for(Outbox e:db.dispatchable(booking.now())) {
            String token=BookingService.id(); if(db.leaseEvent(e.id(),token,booking.now(),booking.now().plusSeconds(30))!=1) continue;
            try {
                byte[] body=json.writeValueAsBytes(Map.of("id",e.id(),"topic",e.topic(),"aggregate",e.aggregateId(),"payload",e.payload()));
                Message m=new Message(topic,e.topic(),e.id(),body);
                String parent=db.traceParent(e.id());if(parent!=null)m.putUserProperty("traceparent",parent);
                if(e.topic().equals("ORDER_DEADLINE")) m.setDelayTimeLevel(3); // >=10s; scanner and status/deadline checks are authoritative.
                if(producer.send(m).getSendStatus()!=SendStatus.SEND_OK) throw new IllegalStateException("Broker did not durably acknowledge");
                db.sent(e.id(),token);
                metrics.counter("event.outbox.publishes","result","ok").increment();
            } catch(Exception ex) {
                db.retry(e.id(),token,e.attempts()>=9?"DEAD":"PENDING",ex.getClass().getSimpleName(),booking.now().plusSeconds(Math.min(60,1L<<Math.min(e.attempts(),6))));
                metrics.counter("event.outbox.publishes","result",e.attempts()>=9?"dead":"retry").increment();
            }
        }
    }
    @PreDestroy public void stop() { consumer.shutdown(); producer.shutdown(); }
}

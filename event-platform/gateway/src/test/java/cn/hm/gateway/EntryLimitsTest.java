package cn.hm.gateway;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;

class EntryLimitsTest {
 @Test void concurrencyRejectsAndCancellationReturnsPermit(){
  var filter=new EntryLimits(new GatewayPolicy());List<Disposable> open=new ArrayList<>();AtomicInteger entered=new AtomicInteger();
  try{for(int i=0;i<32;i++)open.add(filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/read")),e->{entered.incrementAndGet();return Mono.never();}).subscribe());
   var full=MockServerWebExchange.from(MockServerHttpRequest.get("/api/read"));filter.filter(full,e->{throw new AssertionError("must not enter");}).block(Duration.ofSeconds(1));assertThat(full.getResponse().getStatusCode().value()).isEqualTo(429);
   open.remove(0).dispose();filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/read")),e->{entered.incrementAndGet();return Mono.empty();}).block(Duration.ofSeconds(1));assertThat(entered.get()).isEqualTo(33);
  }finally{open.forEach(Disposable::dispose);}
 }
 @Test void maintenanceStopsNewWorkButAllowsQueries(){var policy=new GatewayPolicy();policy.agentAdmission.set(false);var filter=new EntryLimits(policy);
  var create=MockServerWebExchange.from(MockServerHttpRequest.post("/api/agent/jobs"));filter.filter(create,e->{throw new AssertionError("must not create");}).block();assertThat(create.getResponse().getStatusCode().value()).isEqualTo(503);
  AtomicInteger queries=new AtomicInteger();filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/agent/jobs")),e->{queries.incrementAndGet();return Mono.empty();}).block();assertThat(queries.get()).isEqualTo(1);
 }
}

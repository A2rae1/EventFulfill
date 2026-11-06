package cn.hm.gateway;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.core.Ordered;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Bounded concurrent entry; does not replace the backend's distributed/user quota checks. */
@Component
public class EntryLimits implements GlobalFilter,Ordered {
 private final Semaphore ordinary=new Semaphore(32),streams=new Semaphore(4);private final GatewayPolicy policy;
 public EntryLimits(GatewayPolicy policy){this.policy=policy;}
 public int getOrder(){return -100;}
 public Mono<Void> filter(ServerWebExchange exchange,GatewayFilterChain chain){
  String path=exchange.getRequest().getPath().value();boolean creation=path.equals("/api/agent/jobs")||path.matches("/api/agent/jobs/[^/]+/followup");
  if(exchange.getRequest().getMethod()==HttpMethod.POST&&creation&&!policy.agentAdmission.get())return reject(exchange,503,"新调研受理暂时关闭，已有任务仍可查询");
  Semaphore permits=path.endsWith("/stream")?streams:ordinary;
  if(!permits.tryAcquire())return reject(exchange,429,"入口繁忙，请稍后重试");
  return Mono.defer(()->chain.filter(exchange)).doFinally(signal->permits.release());
 }
 private Mono<Void> reject(ServerWebExchange exchange,int status,String message){exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(status));exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(("{\"message\":\""+message+"\"}").getBytes(StandardCharsets.UTF_8))));}
}

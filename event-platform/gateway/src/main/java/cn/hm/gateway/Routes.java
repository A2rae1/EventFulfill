package cn.hm.gateway;

import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.*;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@Configuration @RestController
public class Routes {
 private final GatewayPolicy policy;public Routes(GatewayPolicy policy){this.policy=policy;}
 @Bean RouteLocator gatewayRoutes(RouteLocatorBuilder builder,@Value("${gateway.agent-url}")String agent){
  return builder.routes()
   .route("agent-stream",r->r.path("/agent/jobs/*/stream").filters(f->f.stripPrefix(1)).metadata("response-timeout",-1).uri(agent))
   .route("agent-api",r->r.path("/agent/jobs/**","/agent/uploads/**","/agent/memory").filters(f->f.stripPrefix(1).circuitBreaker(c->{c.setName("agentApi");c.setFallbackUri("forward:/fallback/agent");c.setStatusCodes(Set.of("500","502","503","504"));})).uri(agent))
   .route("event-api",r->r.path("/api/**","/v3/api-docs/**").filters(f->f.circuitBreaker(c->{c.setName("javaApi");c.setFallbackUri("forward:/fallback/java");c.setStatusCodes(Set.of("500","502","503","504"));})).uri("lb://event-app"))
   .route("event-static",r->r.path("/","/index.html","/app.js","/style.css","/vendor/**","/favicon.svg").uri("lb://event-app"))
   .build();
 }
 @RequestMapping("/fallback/{service}") public ResponseEntity<?> fallback(){return ResponseEntity.status(503).body(Map.of("code","UPSTREAM_UNAVAILABLE","message","服务暂不可用。创建请求重试时请沿用原幂等键；已受理任务可恢复后查询。"));}
 @GetMapping("/gateway/status") public Map<String,Object> status(){return Map.of("agentAdmissionEnabled",policy.agentAdmission.get());}
}

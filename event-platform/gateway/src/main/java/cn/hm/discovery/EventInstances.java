package cn.hm.discovery;

import com.alibaba.nacos.api.naming.NamingService;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.*;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;

/** Loaded only into this service's LoadBalancer child context. */
public class EventInstances {
 @Bean ServiceInstanceListSupplier instances(ObjectProvider<NamingService> provider,@Value("${gateway.java-fallback}")String fallback){
  var scheduler=Schedulers.newBoundedElastic(2,16,"nacos-lookup");
  Mono<List<ServiceInstance>> instances=Mono.<List<ServiceInstance>>fromCallable(()->{
   NamingService naming=provider.getIfAvailable();
   if(naming==null){URI uri=URI.create(fallback);return List.of(new DefaultServiceInstance("static-event","event-app",uri.getHost(),uri.getPort(),false));}
   return naming.selectInstances("event-app",true).stream().map(instance->(ServiceInstance)new DefaultServiceInstance(instance.getInstanceId(),"event-app",instance.getIp(),instance.getPort(),false)).toList();
  }).subscribeOn(scheduler).onErrorReturn(List.of()).cache(Duration.ofSeconds(1));
  return new ServiceInstanceListSupplier(){public String getServiceId(){return "event-app";}public Flux<List<ServiceInstance>> get(){return instances.flux();}};
 }
}

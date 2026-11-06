package cn.hm.gateway;

import cn.hm.discovery.EventInstances;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;

@SpringBootApplication
@LoadBalancerClient(name="event-app",configuration=EventInstances.class)
public class GatewayApplication {
 public static void main(String[] args){SpringApplication.run(GatewayApplication.class,args);}
}

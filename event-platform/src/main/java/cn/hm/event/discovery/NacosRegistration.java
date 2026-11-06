package cn.hm.event.discovery;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.naming.NamingService;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component @ConditionalOnProperty(name="app.discovery.enabled",havingValue="true")
public class NacosRegistration {
    private final NamingService naming;private final String service,ip;private final int port;
    public NacosRegistration(@Value("${NACOS_SERVER_ADDR}")String address,@Value("${NACOS_PASSWORD}")String password,@Value("${server.port:8090}")int port,@Value("${app.discovery.service:event-app}")String service)throws Exception{
        Properties props=new Properties();props.setProperty("serverAddr",address);props.setProperty("username","nacos");props.setProperty("password",password);
        this.naming=NacosFactory.createNamingService(props);this.service=service;this.port=port;this.ip=InetAddress.getLocalHost().getHostAddress();
    }
    @EventListener(ApplicationReadyEvent.class) public void register()throws Exception{naming.registerInstance(service,ip,port);}
    @PreDestroy public void close()throws Exception{try{naming.deregisterInstance(service,ip,port);}finally{naming.shutDown();}}
}

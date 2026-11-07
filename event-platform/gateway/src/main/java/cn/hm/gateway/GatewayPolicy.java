package cn.hm.gateway;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.naming.NamingService;
import jakarta.annotation.PreDestroy;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.Component;

@Component
public class GatewayPolicy {
 public final AtomicBoolean bookingAdmission=new AtomicBoolean(true);
 private ConfigService config;private Listener listener;
 private final ExecutorService updates=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),new ThreadPoolExecutor.DiscardOldestPolicy());
 private static Properties credentials(String address,String password){Properties p=new Properties();p.setProperty("serverAddr",address);p.setProperty("username","nacos");p.setProperty("password",password);return p;}
 private void apply(String text){
  if(text==null||text.length()>4096)return;
  try{Properties p=new Properties();p.load(new StringReader(text));String value=p.getProperty("booking_admission_enabled");if("true".equals(value)||"false".equals(value))bookingAdmission.set(Boolean.parseBoolean(value));}catch(Exception invalid){/*Keep the last valid complete policy snapshot.*/}
 }
 @Bean(destroyMethod="shutDown") @ConditionalOnProperty(name="gateway.nacos-enabled",havingValue="true")
 NamingService naming(@Value("${NACOS_SERVER_ADDR}")String address,@Value("${NACOS_PASSWORD}")String password)throws Exception{
  Properties p=credentials(address,password);config=NacosFactory.createConfigService(p);apply(config.getConfig("gateway-policy.properties","PROJECT",3000));
  listener=new Listener(){public Executor getExecutor(){return updates;}public void receiveConfigInfo(String text){apply(text);}};
  config.addListener("gateway-policy.properties","PROJECT",listener);
  return NacosFactory.createNamingService(p);
 }
 @PreDestroy public void close()throws Exception{if(config!=null){config.removeListener("gateway-policy.properties","PROJECT",listener);config.shutDown();}updates.shutdownNow();}
}

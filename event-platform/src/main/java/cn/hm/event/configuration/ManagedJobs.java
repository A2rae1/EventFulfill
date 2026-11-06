package cn.hm.event.configuration;

import cn.hm.event.messaging.LocalDispatcher;
import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import com.xxl.job.core.handler.annotation.XxlJob;
import com.xxl.job.core.context.XxlJobHelper;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

/** Trusted administration only; the executor's network port must never be public. */
@Configuration
@ConditionalOnProperty(name="app.xxl-enabled",havingValue="true")
public class ManagedJobs {
    private final LocalDispatcher dispatcher;
    private final ObjectProvider<RedissonClient> redis;
    public ManagedJobs(LocalDispatcher dispatcher,ObjectProvider<RedissonClient> redis){this.dispatcher=dispatcher;this.redis=redis;}
    @Bean XxlJobSpringExecutor managedExecutor(
        @Value("${XXL_ADMIN_URL}")String admin,@Value("${XXL_ACCESS_TOKEN}")String token,
        @Value("${XXL_EXECUTOR_PORT:9999}")int port,@Value("${XXL_LOG_PATH:/tmp/xxl-job}")String log){
        if(token.length()<24)throw new IllegalArgumentException("Private scheduler token required");
        XxlJobSpringExecutor executor=new XxlJobSpringExecutor();
        executor.setAdminAddresses(admin);executor.setAppname("event-fulfill");executor.setPort(port);
        executor.setAccessToken(token);executor.setLogPath(log);executor.setLogRetentionDays(7);
        return executor;
    }
    @XxlJob("bookingReconcile") public void reconcile()throws Exception{
        RedissonClient client=redis.getIfAvailable();
        if(client==null){complete();return;}
        var lock=client.getLock("event:job:booking-reconcile");
        if(!lock.tryLock()){XxlJobHelper.log("Another executor owns reconciliation; no duplicate scan needed");return;}
        try{complete();}
        finally{if(lock.isHeldByCurrentThread())lock.unlock();}
    }
    private void complete(){
        var result=dispatcher.reconcileNow();
        XxlJobHelper.log("Reconciliation attempted={}, failed={}; DB state authoritative",result.attempted(),result.failed());
        if(result.failed()>0)XxlJobHelper.handleFail("Partial reconciliation failure; successful items retained, failed items need retry/diagnosis");
    }
}

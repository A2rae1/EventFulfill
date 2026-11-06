package cn.hm.event.security;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty(name="app.redis-enabled",havingValue="true",matchIfMissing=true)
public class RedisLocks {
    @Bean(destroyMethod="shutdown") RedissonClient redisson(@Value("${spring.data.redis.host}") String host,@Value("${spring.data.redis.port}") int port) {
        Config c=new Config();c.setThreads(2);c.setNettyThreads(2);c.setLockWatchdogTimeout(30000);
        c.useSingleServer().setAddress("redis://"+host+":"+port).setConnectionMinimumIdleSize(1).setConnectionPoolSize(8).setSubscriptionConnectionPoolSize(2).setSubscriptionConnectionMinimumIdleSize(1).setTimeout(700).setRetryAttempts(1);
        return Redisson.create(c);
    }
}

package cn.hm.event;

import cn.hm.event.domain.*;
import cn.hm.event.domain.Rows.*;
import cn.hm.event.security.AdmissionGate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"app.redis-enabled=true","spring.data.redis.port=16379"})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="EVENT_REDIS_IT",matches="true")
class RedisIntegrationTest {
    @Autowired AdmissionGate gate;@Autowired StringRedisTemplate redis;@Autowired RedissonClient locks;
    @Autowired BookingService booking;@Autowired SessionCatalog catalog;
    @Test void luaAdmissionIsAtomicUnderContention() throws Exception {
        String dimension="integration-"+UUID.randomUUID();ExecutorService pool=Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> calls=new ArrayList<>();
            for(int i=0;i<30;i++)calls.add(()->{try{gate.check(dimension,7,10000);return 1;}catch(DomainException ex){assertThat(ex.status()).isEqualTo(429);return 0;}});
            int accepted=0;for(Future<Integer> f:pool.invokeAll(calls))accepted+=f.get();assertThat(accepted).isEqualTo(7);
            assertThat(redis.getExpire("admission:"+dimension)).isBetween(0L,10L);
        } finally {pool.shutdownNow();redis.delete("admission:"+dimension);}
    }
    @Test void cacheAsideAndNegativeCacheHaveBoundedTtl() {
        Activity a=booking.createActivity("owner","cache-test","");Session s=booking.createSession("owner",a.id(),"cached",1,0,60);booking.publish("owner",s.id());
        assertThat(catalog.get(s.id()).id()).isEqualTo(s.id());assertThat(redis.getExpire("session:display:"+s.id())).isBetween(0L,20L);
        String missing=UUID.randomUUID().toString();assertThatThrownBy(()->catalog.get(missing)).isInstanceOf(DomainException.class);
        assertThat(redis.opsForValue().get("session:display:"+missing)).isEqualTo("NULL");
        redis.delete(List.of("session:display:"+s.id(),"session:display:"+missing));
    }
    @Test void explicitLeaseExpiryAllowsAnotherHolderAndIsNotAnOwnershipGuarantee() throws Exception {
        String key="lease-lab:"+UUID.randomUUID();var original=locks.getLock(key);
        assertThat(original.tryLock(0,250,TimeUnit.MILLISECONDS)).isTrue();Thread.sleep(400);
        assertThat(original.isHeldByCurrentThread()).isFalse();
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {assertThat(pool.submit(()->{var next=locks.getLock(key);boolean acquired=next.tryLock(1,TimeUnit.SECONDS);if(acquired)next.unlock();return acquired;}).get()).isTrue();}
        finally{pool.shutdownNow();}
    }
    private RedissonClient shortWatchdogClient(){
        var config=new org.redisson.config.Config();config.setThreads(1);config.setNettyThreads(1);config.setLockWatchdogTimeout(3000);
        config.useSingleServer().setAddress("redis://127.0.0.1:16379").setConnectionMinimumIdleSize(1).setConnectionPoolSize(2).setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2);
        return org.redisson.Redisson.create(config);
    }
    @Test void watchdogRenewsLongWorkAndAnotherClientRecoversAfterHolderStops()throws Exception{
        var first=shortWatchdogClient();var second=shortWatchdogClient();String key="watchdog-lab:"+UUID.randomUUID();
        try{
            var held=first.getLock(key);assertThat(held.tryLock(100,TimeUnit.MILLISECONDS)).isTrue();
            Thread.sleep(6500); // More than two initial leases; renewal must actually occur.
            assertThat(held.isHeldByCurrentThread()).isTrue();
            assertThat(second.getLock(key).tryLock(100,TimeUnit.MILLISECONDS)).isFalse();
            first.shutdown();
            var replacement=second.getLock(key);assertThat(replacement.tryLock(7,TimeUnit.SECONDS)).isTrue();replacement.unlock();
        }finally{if(!first.isShutdown())first.shutdown();second.shutdown();}
    }
}

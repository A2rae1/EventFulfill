package cn.hm.event.domain;

import cn.hm.event.domain.Rows.Session;
import cn.hm.event.persistence.EventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Cache Aside for display only. Database allocation remains authoritative even during cache outage/staleness. */
@Service
public class SessionCatalog {
    private final EventMapper db; private final StringRedisTemplate redis; private final ObjectMapper json;
    private final ObjectProvider<RedissonClient> locks; private final boolean enabled;
    public SessionCatalog(EventMapper db,StringRedisTemplate redis,ObjectMapper json,ObjectProvider<RedissonClient> locks,@Value("${app.redis-enabled:true}") boolean enabled) {
        this.db=db;this.redis=redis;this.json=json;this.locks=locks;this.enabled=enabled;
    }
    public Session get(String id) {
        if(!enabled) return load(id);
        String key="session:display:"+id;
        try {
            String cached=redis.opsForValue().get(key);
            if(cached!=null) return parse(cached);
            var client=locks.getIfAvailable();
            if(client==null) return load(id);
            var lock=client.getLock("cache-rebuild:"+id);
            if(!lock.tryLock(50,TimeUnit.MILLISECONDS)) throw new DomainException(503,"CACHE_REBUILDING","页面正在更新，请重试");
            try {
                cached=redis.opsForValue().get(key); if(cached!=null) return parse(cached);
                Session s=db.session(id);
                if(s==null||!s.published()) { redis.opsForValue().set(key,"NULL",Duration.ofSeconds(5)); throw DomainException.missing(); }
                redis.opsForValue().set(key,json.writeValueAsString(s),Duration.ofSeconds(10+ThreadLocalRandom.current().nextInt(10)));
                return s;
            } finally { if(lock.isHeldByCurrentThread()) lock.unlock(); }
        } catch(DomainException e) {throw e;}
        catch(InterruptedException e) {Thread.currentThread().interrupt();throw new DomainException(503,"INTERRUPTED","请求被中断");}
        catch(Exception e) {return load(id);} // Read availability fallback. Admission has a separate fail-closed policy.
    }
    private Session parse(String s) throws Exception { if(s.equals("NULL")) throw DomainException.missing();return json.readValue(s,Session.class); }
    private Session load(String id) {Session s=db.session(id);if(s==null||!s.published())throw DomainException.missing();return s;}
}

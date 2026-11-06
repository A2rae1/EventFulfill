package cn.hm.event;

import cn.hm.event.domain.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class PublishingTest {
    @Autowired PublishingService publishing;@Autowired JdbcTemplate jdbc;
    @Test void concurrentReplayCreatesOneAggregateAndChangedPayloadConflicts()throws Exception{
        String owner="publish-"+UUID.randomUUID(),key=UUID.randomUUID().toString();
        var draft=new PublishingService.Draft("原子发布","","场次",1,0,60);
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{
            var tasks=new ArrayList<Future<?>>();
            for(int i=0;i<2;i++)tasks.add(pool.submit(()->{try{start.await();publishing.publish(owner,key,draft);}catch(org.springframework.dao.DuplicateKeyException expected){/*409 is safe to retry.*/}catch(InterruptedException e){Thread.currentThread().interrupt();}}));
            start.countDown();for(var future:tasks)future.get(10,TimeUnit.SECONDS);
            var first=publishing.publish(owner,key,draft);assertThat(first.sessionId()).isNotBlank();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM activity WHERE owner_id=?",Integer.class,owner)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_session WHERE owner_id=?",Integer.class,owner)).isEqualTo(1);
            assertThatThrownBy(()->publishing.publish(owner,key,new PublishingService.Draft("更改内容","","场次",1,0,60))).isInstanceOf(DomainException.class);
        }finally{pool.shutdownNow();}
    }
    @Test void invalidSessionRollsBackActivityAndKeyReservation(){
        String owner="rollback-"+UUID.randomUUID(),key=UUID.randomUUID().toString();
        assertThatThrownBy(()->publishing.publish(owner,key,new PublishingService.Draft("活动","","场次",0,0,60))).isInstanceOf(DomainException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM activity WHERE owner_id=?",Integer.class,owner)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM publication_request WHERE owner_id=?",Integer.class,owner)).isZero();
    }
}

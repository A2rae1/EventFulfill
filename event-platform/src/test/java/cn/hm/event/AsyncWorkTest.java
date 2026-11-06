package cn.hm.event;

import cn.hm.event.domain.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AsyncWorkTest {
    @Test void contextPropagatesButIsRemovedBeforeWorkerReuse()throws Exception{
        var pool=Executors.newSingleThreadExecutor();
        try{
            RequestScope.set("request-one");
            var observed=new CompletableFuture<String>();
            pool.execute(RequestScope.decorate(()->observed.complete(RequestScope.get())));
            assertThat(observed.get(3,TimeUnit.SECONDS)).isEqualTo("request-one");
            // A plain JDK executor really bypasses Spring's decoration override.
            assertThat(pool.submit(RequestScope::get).get(3,TimeUnit.SECONDS)).isNull();
            RequestScope.set(null);
            assertThat(CompletableFuture.supplyAsync(RequestScope::get,pool).get(3,TimeUnit.SECONDS)).isNull();
        }finally{RequestScope.set(null);pool.shutdown();}
    }
    @Test void fullBoundedQueueRejectsWithoutRunningOnCaller()throws Exception{
        var pool=AsyncWork.pool(1,1,1);pool.initialize();
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        try{
            pool.execute(()->{started.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();pool.execute(()->{});
            assertThatThrownBy(()->pool.execute(()->{throw new AssertionError("must not run on request thread");})).isInstanceOf(RejectedExecutionException.class);
            assertThat(pool.getThreadPoolExecutor().getQueue().size()).isEqualTo(1);
        }finally{release.countDown();pool.shutdown();}
    }
}

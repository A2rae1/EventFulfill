package cn.hm.event.domain;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncWork {
    public static ThreadPoolTaskExecutor pool(int core,int max,int queue){
        ThreadPoolTaskExecutor pool=new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(core);pool.setMaxPoolSize(max);pool.setQueueCapacity(queue);
        pool.setThreadNamePrefix("session-summary-");pool.setTaskDecorator(RequestScope::decorate);
        pool.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        pool.setAwaitTerminationSeconds(3);pool.setWaitForTasksToCompleteOnShutdown(true);
        return pool;
    }
    @Bean("summaryExecutor") public ThreadPoolTaskExecutor summaryExecutor(){return pool(2,4,16);}
}

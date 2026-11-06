package cn.hm.event.domain;

import cn.hm.event.persistence.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class BatchSummaries {
    private final EventMapper events;private final SessionSummaryMapper stats;private final ThreadPoolTaskExecutor pool;
    public BatchSummaries(EventMapper events,SessionSummaryMapper stats,@Qualifier("summaryExecutor")ThreadPoolTaskExecutor pool){this.events=events;this.stats=stats;this.pool=pool;}
    public record Result(String sessionId,String status,SessionSummaryMapper.Counts counts){}
    public List<Result> load(String owner,List<String> input){
        if(input.isEmpty()||input.size()>20)throw new DomainException(400,"BATCH_SIZE","每批须为1至20个场次");
        List<String> ids=new ArrayList<>(new LinkedHashSet<>(input));
        // Validate the entire scope before submitting any cross-thread read. A ThreadLocal never grants authority.
        for(String id:ids){var session=events.session(id);if(session==null)throw DomainException.missing();if(!session.ownerId().equals(owner))throw DomainException.forbidden();}
        List<CompletableFuture<Result>> pending=new ArrayList<>();
        for(String id:ids){
            try{pending.add(CompletableFuture.supplyAsync(()->new Result(id,"OK",stats.summary(id)),pool)
                .orTimeout(2,TimeUnit.SECONDS).exceptionally(error->new Result(id,"UNAVAILABLE",null)));}
            catch(RejectedExecutionException error){pending.add(CompletableFuture.completedFuture(new Result(id,"BUSY",null)));}
        }
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).join();
        return pending.stream().map(CompletableFuture::join).toList();
    }
}

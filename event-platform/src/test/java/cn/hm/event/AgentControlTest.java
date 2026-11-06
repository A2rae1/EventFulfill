package cn.hm.event;
import cn.hm.event.domain.*;
import cn.hm.event.domain.AgentRows.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest @ActiveProfiles("test")
class AgentControlTest {
    @Autowired AgentJobService jobs;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Test void commitNormalizationDoesNotBreakIdempotentReplay(){
        Workspace w=jobs.workspace("alice","canonical-sha");String key=java.util.UUID.randomUUID().toString();
        Job first=jobs.create("alice",w.id(),key,"https://github.com/pallets/click","B".repeat(40),"same task");
        assertThat(jobs.create("alice",w.id(),key,"https://github.com/pallets/click","B".repeat(40),"same task").id()).isEqualTo(first.id());
        assertThatThrownBy(()->jobs.create("alice",w.id(),"invalid","https://github.com/../click","b".repeat(40),"bad path")).isInstanceOf(DomainException.class);
    }
    @Test void dedupeClaimCancelAndStaleResultAreEnforced() {
        Workspace w=jobs.workspace("alice","测试");String sha="a".repeat(40);
        Job j=jobs.create("alice",w.id(),w.id(),"https://github.com/pallets/flask",sha,"版本要求");
        assertThat(jobs.create("alice",w.id(),w.id(),"https://github.com/pallets/flask",sha,"版本要求").id()).isEqualTo(j.id());
        assertThatThrownBy(()->jobs.create("bob",w.id(),"x","https://github.com/pallets/flask",sha,"x")).isInstanceOf(DomainException.class);
        Job claimed=jobs.claim(j.id());assertThat(claimed.attempt()).isEqualTo(1);
        assertThatThrownBy(()->jobs.claim(j.id())).isInstanceOf(DomainException.class);
        jobs.cancel("alice",j.id());
        assertThatThrownBy(()->jobs.finish(j.id(),claimed.leaseToken(),"COMPLETED","report",null)).isInstanceOf(DomainException.class);
    }
    @Test void expiredAttemptIsFencedWhenNewWorkerClaims() {
        Workspace w=jobs.workspace("alice","lease-test");Job j=jobs.create("alice",w.id(),w.id(),"https://github.com/pallets/click","b".repeat(40),"test");
        Job old=jobs.claim(j.id());
        jdbc.update("UPDATE agent_job SET lease_until=? WHERE id=?",java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1),j.id());
        Job next=jobs.claim(j.id());assertThat(next.attempt()).isEqualTo(old.attempt()+1);
        assertThatThrownBy(()->jobs.finish(j.id(),old.leaseToken(),"COMPLETED","stale",null)).isInstanceOf(DomainException.class);
        assertThat(jobs.finish(j.id(),next.leaseToken(),"COMPLETED","valid",null).resultRef()).isEqualTo("valid");
    }
}

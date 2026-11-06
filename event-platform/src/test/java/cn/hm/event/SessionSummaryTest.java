package cn.hm.event;

import cn.hm.event.domain.*;
import cn.hm.event.persistence.EventMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class SessionSummaryTest {
    @Autowired BookingService service;@Autowired EventMapper db;@Autowired BatchSummaries summaries;
    @Test void countsReflectFulfillmentAndCannotExposeAnotherOrganizer(){
        String owner="summary-"+UUID.randomUUID();
        var activity=service.createActivity(owner,"摘要验证","");
        var session=service.createSession(owner,activity.id(),"一席",1,0,600);service.publish(owner,session.id());
        var first=service.submit("alice",session.id(),UUID.randomUUID().toString());service.allocate(session.id());
        first=db.request(first.id());service.confirm("alice",first.orderId());
        service.checkIn(owner,service.ticket("alice",first.orderId()).token());
        service.submit("bob",session.id(),UUID.randomUUID().toString());service.allocate(session.id());
        var result=summaries.load(owner,List.of(session.id(),session.id()));
        assertThat(result).hasSize(1);assertThat(result.get(0).status()).isEqualTo("OK");
        var counts=result.get(0).counts();assertThat(counts.requests()).isEqualTo(2);assertThat(counts.waiting()).isEqualTo(1);assertThat(counts.checkedIn()).isEqualTo(1);assertThat(counts.occupied()).isEqualTo(1);
        assertThatThrownBy(()->summaries.load("other-organizer",List.of(session.id()))).isInstanceOf(DomainException.class);
    }
}

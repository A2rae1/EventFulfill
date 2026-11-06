package cn.hm.event;
import cn.hm.event.domain.BookingService;
import cn.hm.event.domain.Rows.Order;
import cn.hm.event.messaging.LocalDispatcher;
import cn.hm.event.persistence.EventMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class ReconciliationIsolationTest {
 @Test void oneBrokenOrderDoesNotPreventOtherOrdersAndPendingSessions(){
  var db=mock(EventMapper.class);var booking=mock(BookingService.class);var now=LocalDateTime.now();
  when(booking.now()).thenReturn(now);
  when(db.expired(now)).thenReturn(List.of(new Order("bad","r1","s1","u","HELD","DIRECT",0,now,0),new Order("good","r2","s2","u","HELD","DIRECT",0,now,0)));
  when(db.pendingSessions()).thenReturn(List.of("pending"));doThrow(new IllegalStateException("injected corrupt aggregate")).when(booking).expire("bad");
  var result=new LocalDispatcher(db,booking,mock(TransactionTemplate.class)).reconcileNow();
  assertThat(result.attempted()).isEqualTo(3);assertThat(result.failed()).isEqualTo(1);
  verify(booking).expire("good");verify(booking).allocate("pending");
 }
}

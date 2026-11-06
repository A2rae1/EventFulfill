package cn.hm.event.persistence;

import cn.hm.event.domain.Rows.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** Session row is the serialization point. Always lock it before changing claims/orders/capacity. */
public interface EventMapper {
    @Insert("INSERT INTO activity VALUES(#{id},#{ownerId},#{title},#{description},#{createdAt})") int insertActivity(Activity row);
    @Select("SELECT * FROM activity WHERE id=#{id}") Activity activity(String id);
    @Select("SELECT * FROM activity ORDER BY created_at DESC LIMIT 100") List<Activity> activities();
    @Insert("INSERT INTO event_session VALUES(#{id},#{activityId},#{ownerId},#{title},#{capacity},0,#{priceCents},#{holdSeconds},false,0)") int insertSession(Session row);
    @Select("SELECT * FROM event_session WHERE id=#{id}") Session session(String id);
    @Select("SELECT * FROM event_session WHERE id=#{id} FOR UPDATE") Session lockSession(String id);
    @Select("SELECT * FROM event_session WHERE published=true ORDER BY id LIMIT 100") List<Session> sessions();
    @Update("UPDATE event_session SET published=true,version=version+1 WHERE id=#{id}") int publish(String id);
    @Update("UPDATE event_session SET used=used+1,version=version+1 WHERE id=#{id} AND used<capacity") int take(String id);
    @Update("UPDATE event_session SET used=used-1,version=version+1 WHERE id=#{id} AND used>0") int release(String id);
    @Insert("INSERT INTO booking_request(id,session_id,user_id,idem_key,status,created_at) VALUES(#{id},#{sessionId},#{userId},#{idemKey},'ACCEPTED',#{createdAt})") int insertRequest(Booking row);
    @Select("SELECT * FROM booking_request WHERE id=#{id}") Booking request(String id);
    @Select("SELECT * FROM booking_request WHERE id=#{id} FOR UPDATE") Booking currentRequest(String id);
    @Select("SELECT * FROM booking_request WHERE user_id=#{userId} AND idem_key=#{key}") Booking byKey(@Param("userId") String userId,@Param("key") String key);
    @Select("SELECT b.* FROM active_claim c JOIN booking_request b ON b.id=c.request_id WHERE c.session_id=#{sessionId} AND c.user_id=#{userId}") Booking active(@Param("sessionId") String sessionId,@Param("userId") String userId);
    @Select("SELECT * FROM booking_request WHERE session_id=#{sessionId} AND status='ACCEPTED' ORDER BY seq FOR UPDATE") List<Booking> pending(String sessionId);
    @Select("SELECT * FROM booking_request WHERE user_id=#{userId} ORDER BY seq DESC LIMIT 100") List<Booking> mine(String userId);
    @Insert("INSERT INTO active_claim VALUES(#{sessionId},#{userId},#{requestId})") int claim(@Param("sessionId") String sessionId,@Param("userId") String userId,@Param("requestId") String requestId);
    @Delete("DELETE FROM active_claim WHERE request_id=#{id}") int unclaim(String id);
    @Update("UPDATE booking_request SET status=#{status},order_id=#{orderId} WHERE id=#{id}") int requestState(@Param("id") String id,@Param("status") String status,@Param("orderId") String orderId);
    @Insert("INSERT INTO booking_order VALUES(#{id},#{requestId},#{sessionId},#{userId},#{status},#{kind},#{priceCents},#{expiresAt},0)") int insertOrder(Order row);
    @Select("SELECT * FROM booking_order WHERE id=#{id}") Order order(String id);
    @Select("SELECT * FROM booking_order WHERE id=#{id} FOR UPDATE") Order currentOrder(String id);
    @Select("SELECT * FROM booking_order WHERE status='HELD' AND expires_at<=#{now} ORDER BY expires_at LIMIT 200") List<Order> expired(LocalDateTime now);
    @Update("UPDATE booking_order SET status=#{next},version=version+1 WHERE id=#{id} AND status=#{expected}") int orderState(@Param("id") String id,@Param("expected") String expected,@Param("next") String next);
    @Insert("INSERT INTO allocation VALUES(#{orderId},#{sessionId},'HELD')") int allocation(@Param("orderId") String orderId,@Param("sessionId") String sessionId);
    @Update("UPDATE allocation SET status=#{next} WHERE order_id=#{id} AND status=#{expected}") int allocationState(@Param("id") String id,@Param("expected") String expected,@Param("next") String next);
    @Select("SELECT status FROM allocation WHERE order_id=#{id}") String allocationStatus(String id);
    @Insert("INSERT INTO waitlist_entry VALUES(#{id},#{seq},#{requestId},#{sessionId},#{userId},'WAITING')") int insertWait(WaitEntry row);
    @Select("SELECT * FROM waitlist_entry WHERE session_id=#{id} AND status='WAITING' ORDER BY seq LIMIT 1 FOR UPDATE") WaitEntry firstWait(String id);
    @Select("SELECT * FROM waitlist_entry WHERE session_id=#{id} ORDER BY seq LIMIT 100") List<WaitEntry> waitlist(String id);
    @Update("UPDATE waitlist_entry SET status=#{status} WHERE request_id=#{requestId}") int waitState(@Param("requestId") String requestId,@Param("status") String status);
    @Insert("INSERT INTO admission_ticket VALUES(#{id},#{orderId},#{userId},#{token},'ISSUED',NULL)") int insertTicket(Ticket row);
    @Select("SELECT * FROM admission_ticket WHERE order_id=#{id}") Ticket ticketByOrder(String id);
    @Select("SELECT * FROM admission_ticket WHERE token=#{token}") Ticket ticketByToken(String token);
    @Update("UPDATE admission_ticket SET status=#{next},checked_at=#{now} WHERE id=#{id} AND status=#{expected}") int ticketState(@Param("id") String id,@Param("expected") String expected,@Param("next") String next,@Param("now") LocalDateTime now);
    @Select("SELECT * FROM payment_event WHERE event_id=#{id}") Map<String,Object> payment(String id);
    @Insert("INSERT INTO payment_event VALUES(#{eventId},#{orderId},#{outcome},#{amount},#{now})") int insertPayment(@Param("eventId") String eventId,@Param("orderId") String orderId,@Param("outcome") String outcome,@Param("amount") int amount,@Param("now") LocalDateTime now);
    default int outbox(String id,String topic,String aggregateId,String payload,LocalDateTime now){return insertOutbox(id,topic,aggregateId,payload,now,cn.hm.event.domain.TraceContext.parent());}
    @Insert("INSERT INTO outbox_event(id,topic,aggregate_id,payload,status,attempts,available_at,created_at,trace_parent) VALUES(#{id},#{topic},#{aggregateId},#{payload},'PENDING',0,#{now},#{now},#{parent})") int insertOutbox(@Param("id") String id,@Param("topic") String topic,@Param("aggregateId") String aggregateId,@Param("payload") String payload,@Param("now") LocalDateTime now,@Param("parent")String parent);
    @Select("SELECT trace_parent FROM outbox_event WHERE id=#{id}")String traceParent(String id);
    @Select("SELECT * FROM outbox_event WHERE (status='PENDING' AND available_at<=#{now}) OR (status='SENDING' AND lease_until<=#{now}) ORDER BY created_at LIMIT 100") List<Outbox> dispatchable(LocalDateTime now);
    @Update("UPDATE outbox_event SET status='SENDING',lease_token=#{token},lease_until=#{until},attempts=attempts+1 WHERE id=#{id} AND ((status='PENDING' AND available_at<=#{now}) OR (status='SENDING' AND lease_until<=#{now}))") int leaseEvent(@Param("id") String id,@Param("token") String token,@Param("now") LocalDateTime now,@Param("until") LocalDateTime until);
    @Update("UPDATE outbox_event SET status='SENT',last_error=NULL WHERE id=#{id} AND status='SENDING' AND lease_token=#{token}") int sent(@Param("id") String id,@Param("token") String token);
    @Update("UPDATE outbox_event SET status=#{status},last_error=#{error},available_at=#{next} WHERE id=#{id} AND status='SENDING' AND lease_token=#{token}") int retry(@Param("id") String id,@Param("token") String token,@Param("status") String status,@Param("error") String error,@Param("next") LocalDateTime next);
    @Select("SELECT COUNT(*) FROM consumer_receipt WHERE consumer_name=#{consumer} AND event_id=#{id}") int received(@Param("consumer") String consumer,@Param("id") String id);
    @Insert("INSERT INTO consumer_receipt VALUES(#{consumer},#{id},#{now})") int receipt(@Param("consumer") String consumer,@Param("id") String id,@Param("now") LocalDateTime now);
    @Insert("INSERT INTO notification VALUES(#{id},#{userId},#{eventId},#{message},#{now})") int notify(@Param("id") String id,@Param("userId") String userId,@Param("eventId") String eventId,@Param("message") String message,@Param("now") LocalDateTime now);
    @Select("SELECT id,message,created_at FROM notification WHERE user_id=#{userId} ORDER BY created_at DESC LIMIT 100") List<Map<String,Object>> notifications(String userId);
    @Insert("INSERT INTO audit_event VALUES(#{id},#{actor},#{action},#{aggregateId},#{detail},#{now})") int audit(@Param("id") String id,@Param("actor") String actor,@Param("action") String action,@Param("aggregateId") String aggregateId,@Param("detail") String detail,@Param("now") LocalDateTime now);
    @Select("SELECT * FROM audit_event WHERE aggregate_id=#{id} ORDER BY created_at LIMIT 100") List<Map<String,Object>> audits(String id);
    @Select("SELECT * FROM app_user WHERE id=#{id}") User user(String id);
    @Insert("INSERT INTO app_user VALUES(#{id},#{passwordHash},#{role})") int insertUser(User row);
    @Select("SELECT DISTINCT session_id FROM booking_request WHERE status='ACCEPTED' LIMIT 100") List<String> pendingSessions();
    @Select("SELECT COUNT(*) FROM session_checker WHERE session_id=#{session} AND user_id=#{user}") int isChecker(@Param("session")String session,@Param("user")String user);
    @Insert("INSERT INTO session_checker(session_id,user_id) VALUES(#{session},#{user}) ON DUPLICATE KEY UPDATE user_id=VALUES(user_id)") int grantChecker(@Param("session")String session,@Param("user")String user);
    @Delete("DELETE FROM session_checker WHERE session_id=#{session} AND user_id=#{user}") int revokeChecker(@Param("session")String session,@Param("user")String user);
}

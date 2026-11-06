package cn.hm.event.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reserve the idempotency key before any aggregate writes, in the same transaction. */
@Service
public class PublishingService {
    private final JdbcTemplate jdbc;private final BookingService booking;private final ObjectMapper json;
    public PublishingService(JdbcTemplate jdbc,BookingService booking,ObjectMapper json){this.jdbc=jdbc;this.booking=booking;this.json=json;}
    public record Draft(String title,String description,String sessionTitle,int capacity,int priceCents,int holdSeconds){}
    public record Published(String activityId,String sessionId){}
    @Transactional
    public Published publish(String owner,String key,Draft draft){
        if(key==null||key.isBlank()||key.length()>100)throw new DomainException(400,"IDEMPOTENCY_KEY","需要1至100字符的幂等键");
        String hash;
        try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(draft)));}catch(Exception impossible){throw new IllegalStateException("Publication serialization failed");}
        var found=jdbc.queryForList("SELECT * FROM publication_request WHERE owner_id=? AND idem_key=?",owner,key);
        if(!found.isEmpty()){
            var previous=found.get(0);
            if(!hash.equals(previous.get("payload_hash")))throw DomainException.conflict("幂等键已经绑定另一份发布内容");
            return new Published((String)previous.get("activity_id"),(String)previous.get("session_id"));
        }
        // A concurrent duplicate may receive409 and retry the same key after the first commit.
        // It cannot create a second activity because this uniqueness check happens first.
        jdbc.update("INSERT INTO publication_request(owner_id,idem_key,payload_hash) VALUES(?,?,?)",owner,key,hash);
        var activity=booking.createActivity(owner,draft.title(),draft.description());
        var session=booking.createSession(owner,activity.id(),draft.sessionTitle(),draft.capacity(),draft.priceCents(),draft.holdSeconds());
        booking.publish(owner,session.id());
        jdbc.update("UPDATE publication_request SET activity_id=?,session_id=? WHERE owner_id=? AND idem_key=?",activity.id(),session.id(),owner,key);
        return new Published(activity.id(),session.id());
    }
}

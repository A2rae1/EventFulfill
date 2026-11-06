package cn.hm.event.security;

import cn.hm.event.domain.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PaymentSignature {
    private final byte[] key;
    public PaymentSignature(@Value("${app.payment-secret}") String secret) {
        key=secret.getBytes(StandardCharsets.UTF_8); if(key.length<32) throw new IllegalArgumentException("Payment signing secret must have >=32 bytes");
    }
    public String sign(String eventId,String orderId,int amount) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((eventId+"\n"+orderId+"\n"+amount).getBytes(StandardCharsets.UTF_8)));
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    public void verify(String eventId,String orderId,int amount,String signature) {
        if(signature==null || !MessageDigest.isEqual(sign(eventId,orderId,amount).getBytes(StandardCharsets.US_ASCII),signature.getBytes(StandardCharsets.US_ASCII)))
            throw new DomainException(401,"BAD_SIGNATURE","模拟支付签名无效");
    }
}

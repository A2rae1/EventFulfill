package cn.hm.event;

import cn.hm.event.domain.BookingService;
import cn.hm.event.domain.Rows.*;
import cn.hm.event.security.PaymentSignature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability(metrics=true,tracing=false)
@SpringBootTest(properties="METRICS_PASSWORD=test-metrics-only-0123456789abcdef0123456789") @ActiveProfiles("test") @AutoConfigureMockMvc
class ApiSecurityTest {
    @Autowired MockMvc mvc; @Autowired BookingService service; @Autowired PaymentSignature signatures;
    @Test void scraperCredentialsAreRestrictedToMetrics()throws Exception{
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        var credentials=org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic("metrics","test-metrics-only-0123456789abcdef0123456789");
        mvc.perform(get("/actuator/prometheus").with(credentials)).andExpect(status().isOk());
        mvc.perform(get("/api/sessions").with(credentials)).andExpect(status().isUnauthorized());
    }
    @Test void requiresAuthAndRole() throws Exception {
        mvc.perform(get("/api/sessions")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/activities").with(jwt().jwt(j->j.subject("alice"))).contentType("application/json").content("{\"title\":\"x\"}"))
            .andExpect(status().isForbidden());
    }
    @Test void ownershipAndCallbackSignature() throws Exception {
        Activity a=service.createActivity("owner","test","test"); Session s=service.createSession("owner",a.id(),"test",1,100,60);
        service.publish("owner",s.id()); Booking b=service.submit("alice",s.id(),"security"); service.allocate(s.id()); b=service.request("alice",b.id());
        mvc.perform(get("/api/orders/"+b.orderId()).with(jwt().jwt(j->j.subject("bob")))).andExpect(status().isForbidden());
        String body="{\"eventId\":\"signed-event\",\"orderId\":\""+b.orderId()+"\",\"amountCents\":100}";
        mvc.perform(post("/api/mock-payments/callback").contentType("application/json").header("X-Mock-Signature","bad").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/mock-payments/callback").contentType("application/json").header("X-Mock-Signature",signatures.sign("signed-event",b.orderId(),100)).content(body)).andExpect(status().isOk());
    }
}

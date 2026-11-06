package cn.hm.event.api;

import cn.hm.event.domain.*;
import cn.hm.event.domain.Rows.*;
import cn.hm.event.persistence.EventMapper;
import cn.hm.event.security.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class EventController {
    private final EventMapper db; private final BookingService service; private final AdmissionGate gate;
    private final PasswordEncoder passwords; private final JwtEncoder encoder; private final PaymentSignature signature;
    public EventController(EventMapper db,BookingService service,AdmissionGate gate,PasswordEncoder passwords,JwtEncoder encoder,PaymentSignature signature) {
        this.db=db; this.service=service; this.gate=gate; this.passwords=passwords; this.encoder=encoder; this.signature=signature;
    }
    public record Login(@NotBlank @Size(max=80) String username,@NotBlank @Size(max=200) String password) {}
    @PostMapping("/auth/login") public Map<String,Object> login(@Valid @RequestBody Login body,jakarta.servlet.http.HttpServletRequest request) {
        gate.check("login-ip:"+request.getRemoteAddr(),30,60000);
        User u=db.user(body.username());
        if(u==null || !passwords.matches(body.password(),u.passwordHash())) throw new DomainException(401,"LOGIN_FAILED","账号或密码不正确");
        Instant now=Instant.now();
        JwtClaimsSet claims=JwtClaimsSet.builder().issuer("event-fulfill").subject(u.id()).issuedAt(now).expiresAt(now.plusSeconds(3600)).claim("roles",List.of(u.role())).build();
        String jwt=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
        return Map.of("accessToken",jwt,"expiresIn",3600,"user",u.id(),"role",u.role());
    }
    public record ActivityInput(@NotBlank @Size(max=120) String title,@Size(max=2000) String description) {}
    public record SessionInput(@NotBlank String activityId,@NotBlank @Size(max=120) String title,@Min(1) @Max(100000) int capacity,@Min(0) int priceCents,@Min(1) @Max(86400) int holdSeconds) {}
    public record BookingInput(@NotBlank String sessionId) {}
    public record CheckIn(@NotBlank String token) {}
    public record Payment(@NotBlank @Size(max=100) @Pattern(regexp="[a-zA-Z0-9_.:-]+") String eventId,@NotBlank @Size(max=36) String orderId,@Min(1) int amountCents) {}
    @GetMapping("/activities") public List<Activity> activities() { return db.activities(); }
    @GetMapping("/sessions") public List<Session> sessions() { return db.sessions(); }
    @org.springframework.beans.factory.annotation.Autowired private SessionCatalog catalog;
    @GetMapping("/sessions/{id}") public Session session(@PathVariable String id) { return catalog.get(id); }
    @PostMapping("/activities") @PreAuthorize("hasRole('ORGANIZER')") @ResponseStatus(HttpStatus.CREATED)
    public Activity activity(Authentication auth,@Valid @RequestBody ActivityInput b) { return service.createActivity(auth.getName(),b.title(),b.description()); }
    @PostMapping("/sessions") @PreAuthorize("hasRole('ORGANIZER')") @ResponseStatus(HttpStatus.CREATED)
    public Session session(Authentication auth,@Valid @RequestBody SessionInput b) { return service.createSession(auth.getName(),b.activityId(),b.title(),b.capacity(),b.priceCents(),b.holdSeconds()); }
    @PostMapping("/sessions/{id}/publish") @PreAuthorize("hasRole('ORGANIZER')")
    public Session publish(Authentication auth,@PathVariable String id) { return service.publish(auth.getName(),id); }
    @PostMapping("/booking-requests") @ResponseStatus(HttpStatus.ACCEPTED)
    public Booking submit(Authentication auth,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody BookingInput body) {
        gate.check("booking:"+auth.getName(),20,10000); return service.submit(auth.getName(),body.sessionId(),key);
    }
    @GetMapping("/booking-requests") public List<Booking> mine(Authentication auth) { return db.mine(auth.getName()); }
    @GetMapping("/booking-requests/{id}") public Booking request(Authentication auth,@PathVariable String id) { return service.request(auth.getName(),id); }
    @PostMapping("/booking-requests/{id}/cancel") public Booking cancelRequest(Authentication auth,@PathVariable String id) { return service.cancelRequest(auth.getName(),id); }
    @GetMapping("/orders/{id}") public Order order(Authentication auth,@PathVariable String id) { return service.order(auth.getName(),id); }
    @PostMapping("/orders/{id}/confirm") public Order confirm(Authentication auth,@PathVariable String id) { return service.confirm(auth.getName(),id); }
    @PostMapping("/orders/{id}/cancel") public Order cancel(Authentication auth,@PathVariable String id) { return service.cancelOrder(auth.getName(),id); }
    @GetMapping("/orders/{id}/ticket") public Ticket ticket(Authentication auth,@PathVariable String id) { return service.ticket(auth.getName(),id); }
    @PostMapping("/check-in") @PreAuthorize("hasAnyRole('ORGANIZER','CHECKER')")
    public Ticket checkIn(Authentication auth,@Valid @RequestBody CheckIn b) { return service.checkIn(auth.getName(),b.token()); }
    public record CheckerInput(@NotBlank @Size(max=80)String userId,boolean grant){}
    @PostMapping("/sessions/{id}/checkers") @PreAuthorize("hasRole('ORGANIZER')")
    public Object checker(Authentication auth,@PathVariable String id,@Valid @RequestBody CheckerInput body){service.checker(auth.getName(),id,body.userId(),body.grant());return Map.of("updated",true);}
    @GetMapping("/notifications") public Object notifications(Authentication auth) { return db.notifications(auth.getName()); }
    @GetMapping("/sessions/{id}/waitlist") @PreAuthorize("hasRole('ORGANIZER')")
    public Object waitlist(Authentication auth,@PathVariable String id) {
        Session s=db.session(id); if(s==null) throw DomainException.missing(); if(!s.ownerId().equals(auth.getName())) throw DomainException.forbidden(); return db.waitlist(id);
    }
    @GetMapping("/orders/{id}/audit") @PreAuthorize("hasRole('ORGANIZER')")
    public Object audit(Authentication auth,@PathVariable String id) {
        Order o=db.order(id); if(o==null) throw DomainException.missing(); if(!db.session(o.sessionId()).ownerId().equals(auth.getName())) throw DomainException.forbidden(); return db.audits(id);
    }
    @PostMapping("/mock-payments/callback") public Object callback(@Valid @RequestBody Payment b,@RequestHeader("X-Mock-Signature") String signed) {
        signature.verify(b.eventId(),b.orderId(),b.amountCents(),signed); return service.payment(b.eventId(),b.orderId(),b.amountCents());
    }
    /** Authenticated simulator; it cannot change the amount or pay someone else's order. */
    @PostMapping("/orders/{id}/simulate-payment") public Object simulatedPayment(Authentication auth,@PathVariable String id) {
        Order o=service.order(auth.getName(),id); return service.payment("mock-"+o.id(),o.id(),o.priceCents());
    }
}

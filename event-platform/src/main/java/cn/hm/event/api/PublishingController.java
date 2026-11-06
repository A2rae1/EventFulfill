package cn.hm.event.api;

import cn.hm.event.domain.PublishingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class PublishingController {
    private final PublishingService service;
    public PublishingController(PublishingService service){this.service=service;}
    public record Input(@NotBlank @Size(max=120)String title,@Size(max=2000)String description,@NotBlank @Size(max=120)String sessionTitle,@Min(1) @Max(100000)int capacity,@Min(0)int priceCents,@Min(1) @Max(86400)int holdSeconds){}
    @PostMapping("/api/activities/publish") @PreAuthorize("hasRole('ORGANIZER')")
    public Object publish(Authentication auth,@RequestHeader("Idempotency-Key")String key,@Valid @RequestBody Input draft){return service.publish(auth.getName(),key,new PublishingService.Draft(draft.title(),draft.description(),draft.sessionTitle(),draft.capacity(),draft.priceCents(),draft.holdSeconds()));}
}

package cn.hm.event.api;

import cn.hm.event.domain.BatchSummaries;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class SummaryController {
    private final BatchSummaries service;
    public SummaryController(BatchSummaries service){this.service=service;}
    public record Input(@NotEmpty @Size(max=20) List<@NotBlank @Size(max=36) String> sessionIds){}
    @PostMapping("/api/organizer/session-summaries") @PreAuthorize("hasRole('ORGANIZER')")
    public Object summaries(Authentication auth,@Valid @RequestBody Input body){return service.load(auth.getName(),body.sessionIds());}
}

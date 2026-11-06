package cn.hm.event.api;

import cn.hm.event.domain.OutboxOperations;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @PreAuthorize("hasRole('OPERATOR')")
public class OutboxOperationsController {
    private final OutboxOperations operations;
    public OutboxOperationsController(OutboxOperations operations){this.operations=operations;}
    public record Replay(@NotBlank @Size(max=400)String reason){}
    @GetMapping("/api/ops/outbox")public Object failed(){return operations.pending();}
    @PostMapping("/api/ops/outbox/{id}/replay")public Object replay(Authentication auth,@PathVariable String id,@Valid @RequestBody Replay body){operations.replay(auth.getName(),id,body.reason());return Map.of("accepted",true);}
}

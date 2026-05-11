package kirill.ked.auditlog.api;

import jakarta.validation.Valid;
import kirill.ked.auditlog.domain.AuditEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/audit-events")
@RequiredArgsConstructor
@Validated
public class AuditEventController {

    private final AuditEventService auditEventService;

    /**
     * Accepts a new audit event. The timestamp is always set by the server; any client-supplied
     * timestamp is silently ignored.
     */
    @PostMapping
    public ResponseEntity<AuditEventResponse> create(@RequestBody @Valid CreateAuditEventRequest request) {
        AuditEventResponse response = auditEventService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}

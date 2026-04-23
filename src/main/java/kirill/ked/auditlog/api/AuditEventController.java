package kirill.ked.auditlog.api;

import kirill.ked.auditlog.domain.AuditEventService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

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

    /**
     * Searches audit events with optional filters. All parameters are combined with AND.
     * Results are sorted by timestamp DESC.
     */
    @GetMapping
    public ResponseEntity<PagedResponse<AuditEventResponse>> search(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String resource,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") @Max(500) int size) {
        return ResponseEntity.ok(auditEventService.search(actor, resource, from, to, page, size));
    }
}

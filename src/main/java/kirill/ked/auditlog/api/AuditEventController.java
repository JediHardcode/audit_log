package kirill.ked.auditlog.api;

import jakarta.validation.Valid;
import java.time.Instant;
import kirill.ked.auditlog.domain.AuditEventQueryService;
import kirill.ked.auditlog.domain.AuditEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/audit-events")
@RequiredArgsConstructor
@Validated
public class AuditEventController {

    private final AuditEventService auditEventService;
    private final AuditEventQueryService auditEventQueryService;

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
     * Searches audit events with optional filters and cursor pagination.
     *
     * <p>All filters optional. {@code outcome}/{@code sort}/{@code limit} are bound as raw
     * strings so the query validator can return uniform 400s; {@code from}/{@code to} use
     * Spring's ISO-8601 binding (malformed values surface through
     * {@link GlobalExceptionHandler#handleTypeMismatch}).
     */
    @GetMapping
    public ResponseEntity<AuditEventPage> search(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String resource,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false, name = "outcome") String outcomeRaw,
            @RequestParam(required = false, name = "sort") String sortRaw,
            @RequestParam(required = false, name = "limit") String limitRaw,
            @RequestParam(required = false) String cursor) {
        AuditEventQuery query = AuditEventQuery.builder()
                .actor(actor)
                .resource(resource)
                .from(from)
                .to(to)
                .outcomeRaw(outcomeRaw)
                .sortRaw(sortRaw)
                .limitRaw(limitRaw)
                .cursor(cursor)
                .build();
        return ResponseEntity.ok(auditEventQueryService.search(query));
    }
}

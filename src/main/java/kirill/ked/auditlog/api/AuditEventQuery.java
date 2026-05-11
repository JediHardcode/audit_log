package kirill.ked.auditlog.api;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;

/**
 * Bound query parameters for {@code GET /audit-events}. All fields are optional.
 * Raw string forms ({@code outcomeRaw}, {@code sortRaw}, {@code limitRaw}) preserve
 * the client input so validation can distinguish "absent" from "invalid".
 */
@Value
@Builder
public class AuditEventQuery {

    String actor;
    String resource;
    Instant from;
    Instant to;
    String outcomeRaw;
    String sortRaw;
    String limitRaw;
    String cursor;
}

package kirill.ked.auditlog.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import kirill.ked.auditlog.domain.Outcome;
import kirill.ked.auditlog.persistence.AuditEventEntity;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Single audit event item exposed by the public API. Hash chain fields ({@code prevHash},
 * {@code eventHash}) are deliberately omitted — tamper-evidence is an internal concern.
 */
@Value
@Builder
@Jacksonized
public class AuditEventResponse {

    UUID id;
    Instant timestamp;
    String actor;
    String action;
    String resource;
    Outcome outcome;
    Map<String, Object> context;

    public static AuditEventResponse from(AuditEventEntity entity) {
        return AuditEventResponse.builder()
                .id(entity.getId())
                .timestamp(entity.getTimestamp())
                .actor(entity.getActor())
                .action(entity.getAction())
                .resource(entity.getResource())
                .outcome(entity.getOutcome())
                .context(entity.getContext())
                .build();
    }
}

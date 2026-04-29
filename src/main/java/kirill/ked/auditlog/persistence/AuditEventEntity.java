package kirill.ked.auditlog.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import kirill.ked.auditlog.domain.Outcome;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditEventEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false)
    private String action;

    @Column(nullable = false)
    private String resource;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Outcome outcome;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> context;

    @Column(name = "prev_hash")
    private String prevHash;

    @Column(name = "event_hash", nullable = false)
    private String eventHash;

    @Builder
    public AuditEventEntity(
            UUID id,
            Instant timestamp,
            String actor,
            String action,
            String resource,
            Outcome outcome,
            Map<String, Object> context,
            String prevHash,
            String eventHash) {
        this.id = id;
        this.timestamp = timestamp;
        this.actor = actor;
        this.action = action;
        this.resource = resource;
        this.outcome = outcome;
        this.context = context;
        this.prevHash = prevHash;
        this.eventHash = eventHash;
    }
}

package kirill.ked.auditlog.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import kirill.ked.auditlog.api.AuditEventResponse;
import kirill.ked.auditlog.api.CreateAuditEventRequest;
import kirill.ked.auditlog.hashchain.HashChainService;
import kirill.ked.auditlog.persistence.AuditEventEntity;
import kirill.ked.auditlog.persistence.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditEventService {

    private final AuditEventRepository repository;
    private final HashChainService hashChainService;

    /**
     * Persists a new audit event. Timestamp is always server-assigned.
     * An advisory lock serializes concurrent inserts to protect hash chain integrity.
     */
    @Transactional
    public AuditEventResponse create(CreateAuditEventRequest request) {
        repository.acquireInsertLock();

        String prevHash =
                repository.findLatest().map(AuditEventEntity::getEventHash).orElse(null);

        UUID id = UUID.randomUUID();
        Instant timestamp = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String chainPrev = prevHash != null ? prevHash : "GENESIS";

        String eventHash = hashChainService.computeHash(
                chainPrev,
                id,
                timestamp,
                request.getActor(),
                request.getAction(),
                request.getResource(),
                request.getOutcome(),
                request.getContext());

        AuditEventEntity entity = AuditEventEntity.builder()
                .id(id)
                .timestamp(timestamp)
                .actor(request.getActor())
                .action(request.getAction())
                .resource(request.getResource())
                .outcome(request.getOutcome())
                .context(request.getContext())
                .prevHash(prevHash)
                .eventHash(eventHash)
                .build();

        return AuditEventResponse.from(repository.save(entity));
    }
}

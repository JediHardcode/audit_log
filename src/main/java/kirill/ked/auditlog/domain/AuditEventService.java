package kirill.ked.auditlog.domain;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kirill.ked.auditlog.api.AuditEventResponse;
import kirill.ked.auditlog.api.CreateAuditEventRequest;
import kirill.ked.auditlog.api.PagedResponse;
import kirill.ked.auditlog.hashchain.HashChainService;
import kirill.ked.auditlog.persistence.AuditEventEntity;
import kirill.ked.auditlog.persistence.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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

    /**
     * Returns a paginated, filtered list of audit events sorted by timestamp DESC.
     */
    @Transactional(readOnly = true)
    public PagedResponse<AuditEventResponse> search(
            String actor, String resource, Instant from, Instant to, int page, int size) {
        Specification<AuditEventEntity> spec = buildSpec(actor, resource, from, to);
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "timestamp"));
        Page<AuditEventEntity> result = repository.findAll(spec, pageable);

        return PagedResponse.<AuditEventResponse>builder()
                .content(result.getContent().stream()
                        .map(AuditEventResponse::from)
                        .toList())
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .build();
    }

    private Specification<AuditEventEntity> buildSpec(String actor, String resource, Instant from, Instant to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (actor != null) {
                predicates.add(cb.equal(root.get("actor"), actor));
            }
            if (resource != null) {
                predicates.add(cb.equal(root.get("resource"), resource));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("timestamp"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("timestamp"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}

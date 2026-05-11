package kirill.ked.auditlog.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface AuditEventRepository extends Repository<AuditEventEntity, UUID> {

    AuditEventEntity save(AuditEventEntity entity);

    Optional<AuditEventEntity> findById(UUID id);

    /** Returns the most recent event for hash chain computation. */
    @Query(value = "SELECT * FROM audit_events ORDER BY timestamp DESC, id DESC LIMIT 1", nativeQuery = true)
    Optional<AuditEventEntity> findLatest();

    /** Acquires a session-level advisory lock to serialize concurrent inserts. */
    @Query(value = "SELECT pg_advisory_xact_lock(7483921)", nativeQuery = true)
    void acquireInsertLock();

    /** Returns all events in chronological order for chain verification. */
    @Query(value = "SELECT * FROM audit_events ORDER BY timestamp ASC, id ASC", nativeQuery = true)
    List<AuditEventEntity> findAllForChainVerification();
}

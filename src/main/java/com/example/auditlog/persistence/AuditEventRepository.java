package com.example.auditlog.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditEventRepository extends Repository<AuditEventEntity, UUID>,
        JpaSpecificationExecutor<AuditEventEntity> {

    AuditEventEntity save(AuditEventEntity entity);

    Optional<AuditEventEntity> findById(UUID id);

    /** Returns the most recent event for hash chain computation. */
    @Query(value = "SELECT * FROM audit_events ORDER BY timestamp DESC, id DESC LIMIT 1",
           nativeQuery = true)
    Optional<AuditEventEntity> findLatest();

    /** Acquires a session-level advisory lock to serialize concurrent inserts. */
    @Query(value = "SELECT pg_advisory_xact_lock(7483921)", nativeQuery = true)
    void acquireInsertLock();

    Page<AuditEventEntity> findAll(Specification<AuditEventEntity> spec, Pageable pageable);

    /** Returns all events in chronological order for chain verification. */
    @Query(value = "SELECT * FROM audit_events ORDER BY timestamp ASC, id ASC",
           nativeQuery = true)
    List<AuditEventEntity> findAllForChainVerification();
}

package com.example.auditlog.persistence;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The only place in the codebase where DELETE on audit_events is permitted.
 * Deletion must only occur after events have been successfully written to the archive file.
 * Hash chain verification after retention is valid only against live data + archive combined.
 */
public interface RetentionRepository extends Repository<AuditEventEntity, UUID> {

    @Query("SELECT e FROM AuditEventEntity e WHERE e.timestamp < :cutoff ORDER BY e.timestamp ASC")
    List<AuditEventEntity> findOlderThan(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query("DELETE FROM AuditEventEntity e WHERE e.timestamp < :cutoff")
    void deleteOlderThan(@Param("cutoff") Instant cutoff);
}

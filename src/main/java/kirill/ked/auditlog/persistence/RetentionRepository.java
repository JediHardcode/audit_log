package kirill.ked.auditlog.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Reads events eligible for archival export without mutating the append-only store. */
public interface RetentionRepository extends Repository<AuditEventEntity, UUID> {

    @Query("SELECT e FROM AuditEventEntity e WHERE e.timestamp < :cutoff ORDER BY e.timestamp ASC")
    List<AuditEventEntity> findOlderThan(@Param("cutoff") Instant cutoff);
}

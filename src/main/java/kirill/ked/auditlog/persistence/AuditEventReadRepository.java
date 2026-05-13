package kirill.ked.auditlog.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kirill.ked.auditlog.api.AuditEventResponse;
import kirill.ked.auditlog.domain.Outcome;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-side repository for {@code GET /audit-events}. Uses
 * {@link NamedParameterJdbcTemplate} directly to keep the query path off the
 * JPA path (per {@code design.md#layering--classes}).
 *
 * <p>Cursor-aware: callers may pass a tuple {@code (cursorTs, cursorId)} to
 * advance keyset pagination in either direction.
 */
@Repository
@RequiredArgsConstructor
public class AuditEventReadRepository {

    private static final String BASE_SQL =
            "SELECT id, timestamp, actor, action, resource, outcome, context " + "FROM audit_events";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * Runs the filtered query. {@code resourcePrefix} must already be escaped for
     * SQL {@code LIKE} ({@code \\}, {@code %}, {@code _}); the caller is responsible
     * for that translation. {@code limit} is the row cap, applied as-is — callers
     * usually pass {@code requestedLimit + 1} to detect a next page.
     *
     * <p>{@code from} is inclusive, {@code to} is exclusive.
     */
    public List<AuditEventResponse> search(
            List<String> actors,
            String resourcePrefix,
            Instant from,
            Instant to,
            Outcome outcome,
            SortDirection direction,
            Instant cursorTs,
            UUID cursorId,
            int limit) {
        StringBuilder sql = new StringBuilder(BASE_SQL).append(" WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (actors != null && !actors.isEmpty()) {
            sql.append(" AND actor IN (:actors)");
            params.addValue("actors", actors);
        }
        if (resourcePrefix != null) {
            sql.append(" AND resource LIKE :resourcePrefix || '%' ESCAPE '\\'");
            params.addValue("resourcePrefix", resourcePrefix);
        }
        if (from != null) {
            sql.append(" AND timestamp >= :fromTs");
            params.addValue("fromTs", java.sql.Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND timestamp < :toTs");
            params.addValue("toTs", java.sql.Timestamp.from(to));
        }
        if (outcome != null) {
            sql.append(" AND outcome = :outcome");
            params.addValue("outcome", outcome.name());
        }
        if (cursorTs != null && cursorId != null) {
            String cmp = direction == SortDirection.DESC ? "<" : ">";
            sql.append(" AND (timestamp, id) ").append(cmp).append(" (:cursorTs, :cursorId)");
            params.addValue("cursorTs", java.sql.Timestamp.from(cursorTs));
            params.addValue("cursorId", cursorId);
        }

        String order = direction == SortDirection.DESC ? "DESC" : "ASC";
        sql.append(" ORDER BY timestamp ").append(order).append(", id ").append(order);
        sql.append(" LIMIT :limit");
        params.addValue("limit", limit);

        return jdbc.query(sql.toString(), params, rowMapper(objectMapper));
    }

    public enum SortDirection {
        ASC,
        DESC
    }

    private static RowMapper<AuditEventResponse> rowMapper(ObjectMapper mapper) {
        return (rs, rowNum) -> AuditEventResponse.builder()
                .id(rs.getObject("id", UUID.class))
                .timestamp(rs.getTimestamp("timestamp").toInstant())
                .actor(rs.getString("actor"))
                .action(rs.getString("action"))
                .resource(rs.getString("resource"))
                .outcome(Outcome.valueOf(rs.getString("outcome")))
                .context(readContext(rs, mapper))
                .build();
    }

    private static Map<String, Object> readContext(ResultSet rs, ObjectMapper mapper) throws SQLException {
        String raw = rs.getString("context");
        if (raw == null) {
            return null;
        }
        try {
            return mapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            return Collections.emptyMap();
        }
    }
}

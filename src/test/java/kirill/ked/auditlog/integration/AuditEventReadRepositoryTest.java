package kirill.ked.auditlog.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import kirill.ked.auditlog.api.AuditEventResponse;
import kirill.ked.auditlog.domain.Outcome;
import kirill.ked.auditlog.persistence.AuditEventReadRepository;
import kirill.ked.auditlog.persistence.AuditEventReadRepository.SortDirection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
class AuditEventReadRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16").withReuse(true);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AuditEventReadRepository readRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("TRUNCATE TABLE audit_events");
    }

    @Test
    void search_byActor_returnsOnlyMatching() {
        Instant base = Instant.parse("2026-04-01T10:00:00Z");
        seed(base, "alice", "order/1", Outcome.SUCCESS);
        seed(base.plusSeconds(1), "bob", "order/2", Outcome.SUCCESS);

        List<AuditEventResponse> rows =
                readRepository.search("alice", null, null, null, null, SortDirection.DESC, null, null, 10);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getActor()).isEqualTo("alice");
    }

    @Test
    void search_byResourcePrefix_returnsMatchingPrefix() {
        Instant base = Instant.parse("2026-04-01T10:00:00Z");
        seed(base, "alice", "order/1", Outcome.SUCCESS);
        seed(base.plusSeconds(1), "alice", "order/2", Outcome.SUCCESS);
        seed(base.plusSeconds(2), "alice", "invoice/9", Outcome.SUCCESS);

        List<AuditEventResponse> rows =
                readRepository.search(null, "order/", null, null, null, SortDirection.ASC, null, null, 10);

        assertThat(rows).extracting(AuditEventResponse::getResource).containsExactly("order/1", "order/2");
    }

    @Test
    void search_byTimestampRange_isInclusiveLowerExclusiveUpper() {
        Instant t1 = Instant.parse("2026-04-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-04-01T11:00:00Z");
        Instant t3 = Instant.parse("2026-04-01T12:00:00Z");

        seed(t1, "a", "r", Outcome.SUCCESS);
        seed(t2, "b", "r", Outcome.SUCCESS);
        seed(t3, "c", "r", Outcome.SUCCESS);

        List<AuditEventResponse> rows =
                readRepository.search(null, null, t1, t3, null, SortDirection.ASC, null, null, 10);

        assertThat(rows).extracting(AuditEventResponse::getActor).containsExactly("a", "b");
    }

    @Test
    void search_byOutcome_returnsOnlyMatching() {
        Instant base = Instant.parse("2026-04-01T10:00:00Z");
        seed(base, "a", "r", Outcome.SUCCESS);
        seed(base.plusSeconds(1), "b", "r", Outcome.DENIED);
        seed(base.plusSeconds(2), "c", "r", Outcome.ERROR);

        List<AuditEventResponse> rows =
                readRepository.search(null, null, null, null, Outcome.DENIED, SortDirection.DESC, null, null, 10);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getOutcome()).isEqualTo(Outcome.DENIED);
    }

    @Test
    void search_sortAsc_orderedByTimestampThenId() {
        Instant t = Instant.parse("2026-04-01T10:00:00Z");
        UUID id1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID id2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        seedWithId(id2, t, "a", "r", Outcome.SUCCESS);
        seedWithId(id1, t, "b", "r", Outcome.SUCCESS);

        List<AuditEventResponse> rows =
                readRepository.search(null, null, null, null, null, SortDirection.ASC, null, null, 10);

        assertThat(rows).extracting(AuditEventResponse::getId).containsExactly(id1, id2);
    }

    @Test
    void search_sortDesc_orderedByTimestampThenIdDesc() {
        Instant t = Instant.parse("2026-04-01T10:00:00Z");
        UUID id1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID id2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        seedWithId(id2, t, "a", "r", Outcome.SUCCESS);
        seedWithId(id1, t, "b", "r", Outcome.SUCCESS);

        List<AuditEventResponse> rows =
                readRepository.search(null, null, null, null, null, SortDirection.DESC, null, null, 10);

        assertThat(rows).extracting(AuditEventResponse::getId).containsExactly(id2, id1);
    }

    private void seed(Instant ts, String actor, String resource, Outcome outcome) {
        seedWithId(UUID.randomUUID(), ts, actor, resource, outcome);
    }

    private void seedWithId(UUID id, Instant ts, String actor, String resource, Outcome outcome) {
        Instant truncated = ts.truncatedTo(ChronoUnit.MICROS);
        jdbcTemplate.update(
                "INSERT INTO audit_events (id, timestamp, actor, action, resource, outcome, context, prev_hash, event_hash)"
                        + " VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?)",
                id,
                java.sql.Timestamp.from(truncated),
                actor,
                "action.test",
                resource,
                outcome.name(),
                "h_" + id);
    }
}

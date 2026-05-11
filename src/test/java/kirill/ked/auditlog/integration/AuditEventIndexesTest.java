package kirill.ked.auditlog.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the composite indexes added in {@code V3__query_api_indexes.sql} are
 * present after Flyway migrations run, and the now-redundant single-column
 * indexes from {@code V1} are dropped.
 *
 * <p>Driven by {@code design.md#indexes} and the p95 < 300ms non-functional
 * requirement — without these composites, keyset pagination fans out to a heap
 * scan.
 */
@Testcontainers
@SpringBootTest
class AuditEventIndexesTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16").withReuse(true);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void compositeIndexesPresent() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'audit_events'", String.class);

        assertThat(indexes)
                .contains(
                        "idx_audit_events_ts_id",
                        "idx_audit_events_actor_ts_id",
                        "idx_audit_events_resource_ts_id",
                        "idx_audit_events_outcome_ts_id");
    }

    @Test
    void legacySingleColumnIndexesDropped() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'audit_events'", String.class);

        assertThat(indexes)
                .doesNotContain("idx_audit_events_actor", "idx_audit_events_resource", "idx_audit_events_timestamp");
    }
}

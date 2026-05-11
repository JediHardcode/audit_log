package kirill.ked.auditlog.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for cursor pagination stability of {@code GET /audit-events}.
 *
 * <p>Direct {@link JdbcTemplate} inserts let the test pin exact timestamps —
 * required for AC-P6 (concurrent-insert scenario).
 *
 * <p>Covers: AC-P3, AC-P4, AC-P5, AC-P6.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditEventQueryPaginationIT {

    private static final String USER = "audit";
    private static final String PASSWORD = "audit";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16").withReuse(true);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TestRestTemplate authed;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("TRUNCATE TABLE audit_events");
        authed = restTemplate.withBasicAuth(USER, PASSWORD);
    }

    /** AC-P5 — empty result returns {"items": [], "nextCursor": null}. */
    @Test
    void emptyResult_emptyItemsNullCursor() throws Exception {
        JsonNode body = body(get("/audit-events"));

        assertThat(body.get("items")).isEmpty();
        assertThat(body.get("nextCursor").isNull()).isTrue();
    }

    /** AC-P4 — single page (≤ limit rows) returns nextCursor=null. */
    @Test
    void singlePage_nextCursorNull() throws Exception {
        seed("u", "a", "r", "SUCCESS", Instant.parse("2026-05-01T10:00:00Z"));
        seed("u", "a", "r", "SUCCESS", Instant.parse("2026-05-01T11:00:00Z"));

        JsonNode body = body(get("/audit-events?limit=5"));

        assertThat(body.get("items")).hasSize(2);
        assertThat(body.get("nextCursor").isNull()).isTrue();
    }

    /** AC-P3 — more rows than limit → non-null nextCursor. */
    @Test
    void moreRowsThanLimit_nextCursorPresent() throws Exception {
        seed("u", "a", "r", "SUCCESS", Instant.parse("2026-05-01T10:00:00Z"));
        seed("u", "a", "r", "SUCCESS", Instant.parse("2026-05-01T11:00:00Z"));
        seed("u", "a", "r", "SUCCESS", Instant.parse("2026-05-01T12:00:00Z"));

        JsonNode body = body(get("/audit-events?limit=2"));

        assertThat(body.get("items")).hasSize(2);
        assertThat(body.get("nextCursor").isNull()).isFalse();
        assertThat(body.get("nextCursor").asText()).isNotBlank();
    }

    /**
     * AC-P6 — pagination stable under concurrent inserts.
     *
     * <p>Seed 5 events at t1..t5. Fetch page 1 (sort=desc, limit=2) → [e5,e4] + cursor.
     * Insert 2 newer events e6, e7. Fetch page 2 via cursor → must contain only
     * originally-seeded older rows (e3,e2), never e6/e7. Final page → e1.
     */
    @Test
    void stableUnderConcurrentInserts() throws Exception {
        Instant t1 = Instant.parse("2026-05-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-05-01T11:00:00Z");
        Instant t3 = Instant.parse("2026-05-01T12:00:00Z");
        Instant t4 = Instant.parse("2026-05-01T13:00:00Z");
        Instant t5 = Instant.parse("2026-05-01T14:00:00Z");
        UUID e1 = seed("u", "a", "r", "SUCCESS", t1);
        UUID e2 = seed("u", "a", "r", "SUCCESS", t2);
        UUID e3 = seed("u", "a", "r", "SUCCESS", t3);
        UUID e4 = seed("u", "a", "r", "SUCCESS", t4);
        UUID e5 = seed("u", "a", "r", "SUCCESS", t5);

        JsonNode page1 = body(get("/audit-events?sort=desc&limit=2"));
        assertThat(ids(page1.get("items"))).containsExactly(e5, e4);
        String cursor1 = page1.get("nextCursor").asText();
        assertThat(cursor1).isNotBlank();

        // Concurrent inserts arriving after page 1 — newer than the cursor.
        UUID e6 = seed("u", "a", "r", "SUCCESS", Instant.parse("2026-05-01T15:00:00Z"));
        UUID e7 = seed("u", "a", "r", "SUCCESS", Instant.parse("2026-05-01T16:00:00Z"));

        JsonNode page2 = body(get("/audit-events?sort=desc&limit=2&cursor=" + cursor1));
        assertThat(ids(page2.get("items"))).containsExactly(e3, e2).doesNotContain(e6, e7);
        String cursor2 = page2.get("nextCursor").asText();

        JsonNode page3 = body(get("/audit-events?sort=desc&limit=2&cursor=" + cursor2));
        assertThat(ids(page3.get("items"))).containsExactly(e1);
        assertThat(page3.get("nextCursor").isNull()).isTrue();

        // Sanity: e1 not skipped, no duplicates across pages.
        assertThat(ids(page1.get("items")))
                .doesNotContainAnyElementsOf(ids(page2.get("items")))
                .doesNotContainAnyElementsOf(ids(page3.get("items")));
    }

    private UUID seed(String actor, String action, String resource, String outcome, Instant ts) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO audit_events (id, timestamp, actor, action, resource, outcome, context, prev_hash, event_hash) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?)",
                id,
                Timestamp.from(ts),
                actor,
                action,
                resource,
                outcome,
                "h_" + id);
        return id;
    }

    private ResponseEntity<String> get(String url) {
        ResponseEntity<String> response = authed.getForEntity(url, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response;
    }

    private JsonNode body(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody());
    }

    private List<UUID> ids(JsonNode items) {
        return java.util.stream.StreamSupport.stream(items.spliterator(), false)
                .map(n -> UUID.fromString(n.get("id").asText()))
                .toList();
    }
}

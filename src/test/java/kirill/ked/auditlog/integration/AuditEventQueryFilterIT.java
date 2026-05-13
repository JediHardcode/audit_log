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
 * Integration tests for {@code GET /audit-events} filter and sort behavior.
 *
 * <p>Seeds rows directly via {@link JdbcTemplate} to control timestamps and bypass
 * the hash-chain write path (out of scope for read-side tests). Each event uses a
 * fixed {@code event_hash} placeholder — the table requires NOT NULL, but chain
 * verification is not exercised here.
 *
 * <p>Covers: AC-F1, AC-F2, AC-F3, AC-F4, AC-F5, AC-F6, AC-F7, AC-P1, AC-P2.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditEventQueryFilterIT {

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

    /** AC-F1 — exact actor match. */
    @Test
    void actorFilter_returnsOnlyMatchingActor() throws Exception {
        seed("u_42", "login", "app:1", "SUCCESS", Instant.parse("2026-05-01T10:00:00Z"));
        seed("u_99", "login", "app:1", "SUCCESS", Instant.parse("2026-05-01T11:00:00Z"));
        seed("u_42", "logout", "app:1", "SUCCESS", Instant.parse("2026-05-01T12:00:00Z"));

        JsonNode items = items(get("/audit-events?actor=u_42"));

        assertThat(items).hasSize(2);
        items.forEach(i -> assertThat(i.get("actor").asText()).isEqualTo("u_42"));
    }

    /** AC-F1a — comma-separated actor values match any listed actor. */
    @Test
    void actorFilter_multipleActors_returnsOnlyMatchingActors() throws Exception {
        seed("u_42", "login", "app:1", "SUCCESS", Instant.parse("2026-05-01T10:00:00Z"));
        seed("svc_billing", "charge", "app:1", "SUCCESS", Instant.parse("2026-05-01T11:00:00Z"));
        seed("u_99", "login", "app:1", "SUCCESS", Instant.parse("2026-05-01T12:00:00Z"));

        JsonNode items = items(get("/audit-events?actor=u_42,svc_billing"));

        assertThat(items).hasSize(2);
        assertThat(items).extracting(i -> i.get("actor").asText()).containsOnly("u_42", "svc_billing");
    }

    /** AC-F1b — duplicate actor values are collapsed by validation. */
    @Test
    void actorFilter_duplicateActors_doNotDuplicateRows() throws Exception {
        seed("u_42", "login", "app:1", "SUCCESS", Instant.parse("2026-05-01T10:00:00Z"));
        seed("u_42", "logout", "app:1", "SUCCESS", Instant.parse("2026-05-01T11:00:00Z"));
        seed("u_99", "login", "app:1", "SUCCESS", Instant.parse("2026-05-01T12:00:00Z"));

        JsonNode items = items(get("/audit-events?actor=u_42,u_42"));

        assertThat(items).hasSize(2);
        items.forEach(i -> assertThat(i.get("actor").asText()).isEqualTo("u_42"));
    }

    /** AC-F1c + AC-E9 — same actor set in different order can reuse a cursor. */
    @Test
    void actorFilter_sameActorSetDifferentOrder_reusesCursor() throws Exception {
        seed("a", "x", "r", "SUCCESS", Instant.parse("2026-05-01T10:00:00Z"));
        seed("b", "x", "r", "SUCCESS", Instant.parse("2026-05-01T11:00:00Z"));
        seed("a", "x", "r", "SUCCESS", Instant.parse("2026-05-01T12:00:00Z"));

        JsonNode page1 = mapper.readTree(get("/audit-events?actor=a,b&limit=1").getBody());
        String cursor = page1.get("nextCursor").asText();

        JsonNode page2 = items(get("/audit-events?actor=b,a&limit=2&cursor=" + cursor));

        assertThat(page2).hasSize(2);
        assertThat(page2).extracting(i -> i.get("actor").asText()).containsOnly("a", "b");
    }

    /** AC-F2 — resource prefix match. */
    @Test
    void resourcePrefix_returnsAllChildren() throws Exception {
        seed("u", "a", "order/1", "SUCCESS", Instant.parse("2026-05-01T10:00:00Z"));
        seed("u", "a", "order/2", "SUCCESS", Instant.parse("2026-05-01T11:00:00Z"));
        seed("u", "a", "invoice/9", "SUCCESS", Instant.parse("2026-05-01T12:00:00Z"));

        JsonNode items = items(get("/audit-events?resource=order/"));

        assertThat(items).hasSize(2);
        items.forEach(i -> assertThat(i.get("resource").asText()).startsWith("order/"));
    }

    /** AC-F3 + AC-F4 (from inclusive) + AC-F5 (to exclusive). */
    @Test
    void timeRange_lowerInclusive_upperExclusive() throws Exception {
        Instant t1 = Instant.parse("2026-05-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-05-01T11:00:00Z");
        Instant t3 = Instant.parse("2026-05-01T12:00:00Z");

        seed("u", "a", "r", "SUCCESS", t1); // == from → included
        seed("u", "a", "r", "SUCCESS", Instant.parse("2026-05-01T10:30:00Z"));
        seed("u", "a", "r", "SUCCESS", t2); // == to → excluded
        seed("u", "a", "r", "SUCCESS", t3); // > to → excluded

        JsonNode items = items(get("/audit-events?from=" + t1 + "&to=" + t2));

        assertThat(items).hasSize(2);
        items.forEach(i -> {
            Instant ts = Instant.parse(i.get("timestamp").asText());
            assertThat(ts).isBeforeOrEqualTo(t1.plusSeconds(30 * 60));
            assertThat(ts).isBefore(t2);
        });
    }

    /** AC-F6 — outcome exact match (lowercase wire form). */
    @Test
    void outcomeFilter_exactMatch() throws Exception {
        seed("u", "a", "r", "SUCCESS", Instant.parse("2026-05-01T10:00:00Z"));
        seed("u", "a", "r", "DENIED", Instant.parse("2026-05-01T11:00:00Z"));
        seed("u", "a", "r", "ERROR", Instant.parse("2026-05-01T12:00:00Z"));

        JsonNode items = items(get("/audit-events?outcome=denied"));

        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("outcome").asText()).isEqualTo("denied");
    }

    /** AC-F7 — no filters returns latest first (timestamp desc). */
    @Test
    void noFilters_returnsLatestFirst() throws Exception {
        Instant t1 = Instant.parse("2026-05-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-05-01T11:00:00Z");
        Instant t3 = Instant.parse("2026-05-01T12:00:00Z");
        seed("u", "a", "r", "SUCCESS", t1);
        seed("u", "a", "r", "SUCCESS", t2);
        seed("u", "a", "r", "SUCCESS", t3);

        JsonNode items = items(get("/audit-events"));

        assertThat(items).hasSize(3);
        List<Instant> ts = List.of(
                Instant.parse(items.get(0).get("timestamp").asText()),
                Instant.parse(items.get(1).get("timestamp").asText()),
                Instant.parse(items.get(2).get("timestamp").asText()));
        assertThat(ts).containsExactly(t3, t2, t1);
    }

    /** AC-P1 — sort=desc orders by (timestamp DESC, id DESC). */
    @Test
    void sortDesc_orderedDescByTsThenId() throws Exception {
        Instant shared = Instant.parse("2026-05-01T10:00:00Z");
        UUID idLow = new UUID(0, 1);
        UUID idHigh = new UUID(0, 2);
        seed(idLow, "u", "a", "r", "SUCCESS", shared);
        seed(idHigh, "u", "a", "r", "SUCCESS", shared);
        seed("u", "a", "r", "SUCCESS", Instant.parse("2026-05-01T09:00:00Z"));

        JsonNode items = items(get("/audit-events?sort=desc"));

        assertThat(items).hasSize(3);
        assertThat(items.get(0).get("id").asText()).isEqualTo(idHigh.toString());
        assertThat(items.get(1).get("id").asText()).isEqualTo(idLow.toString());
    }

    /** AC-P2 — sort=asc orders by (timestamp ASC, id ASC). */
    @Test
    void sortAsc_orderedAscByTsThenId() throws Exception {
        Instant shared = Instant.parse("2026-05-01T10:00:00Z");
        UUID idLow = new UUID(0, 1);
        UUID idHigh = new UUID(0, 2);
        seed(idLow, "u", "a", "r", "SUCCESS", shared);
        seed(idHigh, "u", "a", "r", "SUCCESS", shared);
        seed("u", "a", "r", "SUCCESS", Instant.parse("2026-05-01T11:00:00Z"));

        JsonNode items = items(get("/audit-events?sort=asc"));

        assertThat(items).hasSize(3);
        assertThat(items.get(0).get("id").asText()).isEqualTo(idLow.toString());
        assertThat(items.get(1).get("id").asText()).isEqualTo(idHigh.toString());
    }

    private void seed(String actor, String action, String resource, String outcome, Instant ts) {
        seed(UUID.randomUUID(), actor, action, resource, outcome, ts);
    }

    private void seed(UUID id, String actor, String action, String resource, String outcome, Instant ts) {
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
    }

    private ResponseEntity<String> get(String url) {
        ResponseEntity<String> response = authed.getForEntity(url, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response;
    }

    private JsonNode items(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody()).get("items");
    }
}

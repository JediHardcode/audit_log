package kirill.ked.auditlog.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
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
 * Integration tests for {@code GET /audit-events} validation rejections (HTTP 400)
 * and the silent {@code limit} clamp.
 *
 * <p>Covers: AC-E1..AC-E10 and AC-P7.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditEventQueryValidationIT {

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

    /** AC-E1 — malformed ISO-8601 in from. */
    @Test
    void invalidFromIso_returns400() {
        assertBadRequest("/audit-events?from=not-a-date");
    }

    /** AC-E1 — malformed ISO-8601 in to. */
    @Test
    void invalidToIso_returns400() {
        assertBadRequest("/audit-events?to=2026-13-99");
    }

    /** AC-E2 — outcome not in {success, denied, error}. */
    @Test
    void invalidOutcome_returns400() {
        assertBadRequest("/audit-events?outcome=Success");
    }

    /** AC-E3 — sort not in {asc, desc}. */
    @Test
    void invalidSort_returns400() {
        assertBadRequest("/audit-events?sort=sideways");
    }

    /** AC-E4 — limit non-numeric. */
    @Test
    void nonNumericLimit_returns400() {
        assertBadRequest("/audit-events?limit=many");
    }

    /** AC-E4 — limit below minimum. */
    @Test
    void limitBelowOne_returns400() {
        assertBadRequest("/audit-events?limit=0");
    }

    /** AC-E5 — from > to. */
    @Test
    void fromAfterTo_returns400() {
        assertBadRequest("/audit-events?from=2026-05-02T00:00:00Z&to=2026-05-01T00:00:00Z");
    }

    /** AC-E6 — to - from > 90 days. */
    @Test
    void rangeOver90Days_returns400() {
        assertBadRequest("/audit-events?from=2026-01-01T00:00:00Z&to=2026-05-01T00:00:00Z");
    }

    /** AC-E7 — only from set → no 90d check, no 400. */
    @Test
    void onlyFromSet_returns200() {
        ResponseEntity<String> response = authed.getForEntity("/audit-events?from=2020-01-01T00:00:00Z", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** AC-E7 — only to set → no 90d check, no 400. */
    @Test
    void onlyToSet_returns200() {
        ResponseEntity<String> response = authed.getForEntity("/audit-events?to=2030-01-01T00:00:00Z", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** AC-E8 — unparseable cursor. */
    @Test
    void unparseableCursor_returns400() {
        assertBadRequest("/audit-events?cursor=!!!not-valid-base64!!!");
    }

    /** AC-E9 — cursor filter hash mismatch (different actor). */
    @Test
    void cursorFilterMismatch_returns400() throws Exception {
        seedRows(3);
        String cursor = fetchCursor("/audit-events?actor=a1&limit=1");
        assertBadRequest("/audit-events?actor=a2&limit=1&cursor=" + cursor);
    }

    /** AC-E10 — cursor sort mismatch. */
    @Test
    void cursorSortMismatch_returns400() throws Exception {
        seedRows(3);
        String cursor = fetchCursor("/audit-events?actor=a1&sort=desc&limit=1");
        assertBadRequest("/audit-events?actor=a1&sort=asc&limit=1&cursor=" + cursor);
    }

    /** AC-P7 — limit=500 silently clamped to ≤200; status 200. */
    @Test
    void limitOverMax_clampedSilently() throws Exception {
        for (int i = 0; i < 5; i++) {
            seed("a1", Instant.parse("2026-05-01T10:00:00Z").plusSeconds(i));
        }
        ResponseEntity<String> response = authed.getForEntity("/audit-events?limit=500", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.get("items").size()).isLessThanOrEqualTo(200);
    }

    private void seedRows(int n) {
        for (int i = 0; i < n; i++) {
            seed("a1", Instant.parse("2026-05-01T10:00:00Z").plusSeconds(i));
        }
    }

    private void seed(String actor, Instant ts) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO audit_events (id, timestamp, actor, action, resource, outcome, context, prev_hash, event_hash) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?)",
                id,
                Timestamp.from(ts),
                actor,
                "a",
                "r",
                "SUCCESS",
                "h_" + id);
    }

    private String fetchCursor(String url) throws Exception {
        ResponseEntity<String> response = authed.getForEntity(url, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode cursor = mapper.readTree(response.getBody()).get("nextCursor");
        assertThat(cursor.isNull()).isFalse();
        return cursor.asText();
    }

    private void assertBadRequest(String url) {
        ResponseEntity<String> response = authed.getForEntity(url, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}

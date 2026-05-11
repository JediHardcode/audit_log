package kirill.ked.auditlog.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
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
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@code GET /audit-events} resource-prefix escaping. Verifies
 * that the SQL {@code LIKE} special characters ({@code \\}, {@code %}, {@code _}) in
 * client-supplied {@code resource} input are treated as literals, not wildcards.
 *
 * <p>Covers: AC-X1, AC-X2, AC-X3.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditEventResourcePrefixIT {

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

    /**
     * AC-X1 — literal {@code %} in input matches a stored {@code %} via prefix.
     * Client sends {@code resource=order/100%}; the server escapes the {@code %} to
     * {@code \%} before LIKE binding, so it matches the literal {@code %} in
     * {@code order/100%off}.
     */
    @Test
    void literalPercentInInput_matchesLiteralPercentInRow_AC_X1() throws Exception {
        seed("u", "a", "order/100%off", "SUCCESS", Instant.parse("2026-05-01T10:00:00Z"));
        seed("u", "a", "order/200", "SUCCESS", Instant.parse("2026-05-01T11:00:00Z"));

        JsonNode items = items(get("/audit-events", "resource", "order/100%"));

        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("resource").asText()).isEqualTo("order/100%off");
    }

    /** AC-X1 — plain prefix without special chars still matches the {@code %off} row. */
    @Test
    void plainPrefix_matchesRowWithSpecialCharSuffix_AC_X1() throws Exception {
        seed("u", "a", "order/100%off", "SUCCESS", Instant.parse("2026-05-01T10:00:00Z"));
        seed("u", "a", "invoice/9", "SUCCESS", Instant.parse("2026-05-01T11:00:00Z"));

        JsonNode items = items(get("/audit-events", "resource", "order/100"));

        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("resource").asText()).isEqualTo("order/100%off");
    }

    /**
     * AC-X2 — underscore in input does NOT act as a single-char wildcard. Row whose
     * resource is {@code order/x} must NOT match input {@code order/_}; only a row
     * starting with literal {@code order/_} matches.
     */
    @Test
    void underscoreInInput_doesNotMatchAsWildcard_AC_X2() throws Exception {
        seed("u", "a", "order/x", "SUCCESS", Instant.parse("2026-05-01T10:00:00Z"));
        seed("u", "a", "order/y", "SUCCESS", Instant.parse("2026-05-01T11:00:00Z"));
        seed("u", "a", "order/_special", "SUCCESS", Instant.parse("2026-05-01T12:00:00Z"));

        JsonNode items = items(get("/audit-events", "resource", "order/_"));

        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("resource").asText()).isEqualTo("order/_special");
    }

    /**
     * AC-X3 — backslash, percent, and underscore in input are all escaped. Mixed
     * input {@code order/a\b%c_d} must only match a row whose resource starts with
     * that exact literal string, not any wildcard expansion.
     */
    @Test
    void allLikeSpecialChars_escapedLiterally_AC_X3() throws Exception {
        seed("u", "a", "order/a\\b%c_d-tail", "SUCCESS", Instant.parse("2026-05-01T10:00:00Z"));
        seed("u", "a", "order/aXbYcZd", "SUCCESS", Instant.parse("2026-05-01T11:00:00Z"));

        JsonNode items = items(get("/audit-events", "resource", "order/a\\b%c_d"));

        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("resource").asText()).isEqualTo("order/a\\b%c_d-tail");
    }

    private void seed(String actor, String action, String resource, String outcome, Instant ts) {
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
    }

    private ResponseEntity<String> get(String path, String paramName, String paramValue) {
        URI uri = UriComponentsBuilder.fromUriString(path)
                .queryParam(paramName, paramValue)
                .encode()
                .build()
                .toUri();
        ResponseEntity<String> response = authed.getForEntity(uri, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response;
    }

    private JsonNode items(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody()).get("items");
    }
}

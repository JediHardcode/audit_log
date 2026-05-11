package kirill.ked.auditlog.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import kirill.ked.auditlog.api.AuditEventResponse;
import kirill.ked.auditlog.api.CreateAuditEventRequest;
import kirill.ked.auditlog.domain.Outcome;
import kirill.ked.auditlog.hashchain.HashChainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditEventIntegrationTest {

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
    private HashChainService hashChainService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TestRestTemplate authed;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("TRUNCATE TABLE audit_events");
        authed = restTemplate.withBasicAuth(USER, PASSWORD);
    }

    @Test
    void contextLoads() {
        // smoke test — Spring context starts successfully
    }

    @Test
    void post_happyPath() {
        CreateAuditEventRequest request = buildRequest("user:42", "project.updated", "project:17", Outcome.SUCCESS);

        ResponseEntity<AuditEventResponse> postResponse =
                authed.postForEntity("/audit-events", request, AuditEventResponse.class);

        assertThat(postResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        AuditEventResponse created = postResponse.getBody();
        assertThat(created).isNotNull();
        assertThat(created.getId()).isNotNull();
        assertThat(created.getTimestamp()).isNotNull();
        assertThat(created.getActor()).isEqualTo("user:42");
    }

    @Test
    void post_withTimestampInBody_serverIgnoresIt() throws Exception {
        Instant clientTimestamp = Instant.parse("2000-01-01T00:00:00Z");

        Map<String, Object> body = Map.of(
                "actor", "user:1",
                "action", "login",
                "resource", "app:1",
                "outcome", "success",
                "timestamp", clientTimestamp.toString());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String json = new ObjectMapper().writeValueAsString(body);
        HttpEntity<String> entity = new HttpEntity<>(json, headers);

        Instant before = Instant.now();
        ResponseEntity<AuditEventResponse> response =
                authed.exchange("/audit-events", HttpMethod.POST, entity, AuditEventResponse.class);
        Instant after = Instant.now();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getTimestamp()).isBetween(before, after);
        assertThat(response.getBody().getTimestamp()).isNotEqualTo(clientTimestamp);
    }

    @Test
    void post_withoutActor_returns400() {
        CreateAuditEventRequest request = new CreateAuditEventRequest();
        request.setAction("login");
        request.setResource("app:1");
        request.setOutcome(Outcome.SUCCESS);

        ResponseEntity<Map> response = authed.postForEntity("/audit-events", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsKey("error");
    }

    @Test
    void hashChain_verifyAfterInserts() {
        authed.postForEntity(
                "/audit-events", buildRequest("u:1", "login", "app:1", Outcome.SUCCESS), AuditEventResponse.class);
        authed.postForEntity(
                "/audit-events", buildRequest("u:2", "update", "doc:1", Outcome.SUCCESS), AuditEventResponse.class);
        authed.postForEntity(
                "/audit-events", buildRequest("u:1", "logout", "app:1", Outcome.SUCCESS), AuditEventResponse.class);

        assertThat(hashChainService.verifyChain()).isTrue();
    }

    @Test
    void auditEventsTable_rejectsUpdate() {
        AuditEventResponse created = authed.postForEntity(
                        "/audit-events",
                        buildRequest("user:7", "project.updated", "project:77", Outcome.SUCCESS),
                        AuditEventResponse.class)
                .getBody();

        assertThat(created).isNotNull();

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE audit_events SET actor = ? WHERE id = ?", "user:8", created.getId()))
                .hasMessageContaining("audit_events is append-only")
                .hasMessageContaining("UPDATE");
    }

    @Test
    void auditEventsTable_rejectsDelete() {
        AuditEventResponse created = authed.postForEntity(
                        "/audit-events",
                        buildRequest("user:9", "project.deleted", "project:99", Outcome.SUCCESS),
                        AuditEventResponse.class)
                .getBody();

        assertThat(created).isNotNull();

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM audit_events WHERE id = ?", created.getId()))
                .hasMessageContaining("audit_events is append-only")
                .hasMessageContaining("DELETE");
    }

    @Test
    void get_unauthenticated_returns401() {
        ResponseEntity<String> response = restTemplate.getForEntity("/audit-events", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void post_unauthenticated_returns401() {
        CreateAuditEventRequest request = buildRequest("u:1", "login", "app:1", Outcome.SUCCESS);
        ResponseEntity<String> response = restTemplate.postForEntity("/audit-events", request, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void get_emptyResult_returnsItemsAndNullCursor() throws Exception {
        ResponseEntity<String> response = authed.getForEntity("/audit-events", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = new ObjectMapper().readTree(response.getBody());
        assertThat(body.has("items")).isTrue();
        assertThat(body.get("items").isArray()).isTrue();
        assertThat(body.get("items")).isEmpty();
        assertThat(body.has("nextCursor")).isTrue();
        assertThat(body.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void get_returnsFlatItemShape() throws Exception {
        authed.postForEntity(
                "/audit-events",
                buildRequest("user:42", "order.refunded", "order/9f3b", Outcome.SUCCESS),
                AuditEventResponse.class);

        ResponseEntity<String> response = authed.getForEntity("/audit-events", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = new ObjectMapper().readTree(response.getBody());
        assertThat(body.get("items")).hasSize(1);
        JsonNode item = body.get("items").get(0);
        assertThat(item.has("id")).isTrue();
        assertThat(item.has("timestamp")).isTrue();
        assertThat(item.get("actor").asText()).isEqualTo("user:42");
        assertThat(item.get("action").asText()).isEqualTo("order.refunded");
        assertThat(item.get("resource").asText()).isEqualTo("order/9f3b");
        assertThat(item.get("outcome").asText()).isEqualTo("success");
        assertThat(item.has("context")).isTrue();
        assertThat(item.has("prevHash")).isFalse();
        assertThat(item.has("eventHash")).isFalse();
        assertThat(body.get("nextCursor").isNull()).isTrue();
    }

    private CreateAuditEventRequest buildRequest(String actor, String action, String resource, Outcome outcome) {
        CreateAuditEventRequest req = new CreateAuditEventRequest();
        req.setActor(actor);
        req.setAction(action);
        req.setResource(resource);
        req.setOutcome(outcome);
        return req;
    }
}

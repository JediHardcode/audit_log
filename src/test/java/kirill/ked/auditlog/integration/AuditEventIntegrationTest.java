package kirill.ked.auditlog.integration;

import kirill.ked.auditlog.api.AuditEventResponse;
import kirill.ked.auditlog.api.CreateAuditEventRequest;
import kirill.ked.auditlog.api.PagedResponse;
import kirill.ked.auditlog.domain.Outcome;
import kirill.ked.auditlog.hashchain.HashChainService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditEventIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withReuse(true);

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

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("TRUNCATE TABLE audit_events");
    }

    @Test
    void contextLoads() {
        // smoke test — Spring context starts successfully
    }

    @Test
    void postAndGet_happyPath() {
        CreateAuditEventRequest request = buildRequest("user:42", "project.updated", "project:17", Outcome.SUCCESS);

        ResponseEntity<AuditEventResponse> postResponse = restTemplate.postForEntity(
                "/audit-events", request, AuditEventResponse.class);

        assertThat(postResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        AuditEventResponse created = postResponse.getBody();
        assertThat(created).isNotNull();
        assertThat(created.getId()).isNotNull();
        assertThat(created.getTimestamp()).isNotNull();
        assertThat(created.getActor()).isEqualTo("user:42");
        assertThat(created.getEventHash()).isNotBlank();

        ResponseEntity<PagedResponse<AuditEventResponse>> getResponse = restTemplate.exchange(
                "/audit-events",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().getContent()).hasSize(1);
        assertThat(getResponse.getBody().getTotalElements()).isEqualTo(1);
    }

    @Test
    void post_withTimestampInBody_serverIgnoresIt() throws Exception {
        Instant clientTimestamp = Instant.parse("2000-01-01T00:00:00Z");

        Map<String, Object> body = Map.of(
                "actor", "user:1",
                "action", "login",
                "resource", "app:1",
                "outcome", "success",
                "timestamp", clientTimestamp.toString()
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String json = new ObjectMapper().writeValueAsString(body);
        HttpEntity<String> entity = new HttpEntity<>(json, headers);

        Instant before = Instant.now();
        ResponseEntity<AuditEventResponse> response = restTemplate.exchange(
                "/audit-events", HttpMethod.POST, entity, AuditEventResponse.class);
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

        ResponseEntity<Map> response = restTemplate.postForEntity("/audit-events", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsKey("error");
    }

    @Test
    void pagination_secondPage() {
        for (int i = 0; i < 120; i++) {
            restTemplate.postForEntity("/audit-events",
                    buildRequest("user:" + i, "action", "resource:1", Outcome.SUCCESS),
                    AuditEventResponse.class);
        }

        ResponseEntity<PagedResponse<AuditEventResponse>> response = restTemplate.exchange(
                "/audit-events?page=1&size=50",
                HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PagedResponse<AuditEventResponse> body = response.getBody();
        assertThat(body.getContent()).hasSize(50);
        assertThat(body.getTotalElements()).isEqualTo(120);
        assertThat(body.getPage()).isEqualTo(1);
    }

    @Test
    void hashChain_verifyAfterInserts() {
        restTemplate.postForEntity("/audit-events",
                buildRequest("u:1", "login", "app:1", Outcome.SUCCESS), AuditEventResponse.class);
        restTemplate.postForEntity("/audit-events",
                buildRequest("u:2", "update", "doc:1", Outcome.SUCCESS), AuditEventResponse.class);
        restTemplate.postForEntity("/audit-events",
                buildRequest("u:1", "logout", "app:1", Outcome.SUCCESS), AuditEventResponse.class);

        assertThat(hashChainService.verifyChain()).isTrue();
    }

    @Test
    void auditEventsTable_rejectsUpdate() {
        AuditEventResponse created = restTemplate.postForEntity(
                "/audit-events",
                buildRequest("user:7", "project.updated", "project:77", Outcome.SUCCESS),
                AuditEventResponse.class
        ).getBody();

        assertThat(created).isNotNull();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE audit_events SET actor = ? WHERE id = ?",
                "user:8",
                created.getId()
        ))
                .hasMessageContaining("audit_events is append-only")
                .hasMessageContaining("UPDATE");
    }

    @Test
    void auditEventsTable_rejectsDelete() {
        AuditEventResponse created = restTemplate.postForEntity(
                "/audit-events",
                buildRequest("user:9", "project.deleted", "project:99", Outcome.SUCCESS),
                AuditEventResponse.class
        ).getBody();

        assertThat(created).isNotNull();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM audit_events WHERE id = ?",
                created.getId()
        ))
                .hasMessageContaining("audit_events is append-only")
                .hasMessageContaining("DELETE");
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

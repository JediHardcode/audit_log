package kirill.ked.auditlog.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kirill.ked.auditlog.api.AuditEventPage;
import kirill.ked.auditlog.api.AuditEventResponse;
import kirill.ked.auditlog.domain.Outcome;
import org.junit.jupiter.api.Test;

class AuditEventPageJsonTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void emptyPage_serializesToItemsAndNullCursor() throws Exception {
        AuditEventPage page =
                AuditEventPage.builder().items(List.of()).nextCursor(null).build();

        String json = mapper.writeValueAsString(page);

        assertThat(json).isEqualTo("{\"items\":[],\"nextCursor\":null}");
    }

    @Test
    void itemKeys_matchSpec() throws Exception {
        AuditEventResponse item = AuditEventResponse.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .timestamp(Instant.parse("2026-04-17T11:02:14Z"))
                .actor("u_42")
                .action("order.refunded")
                .resource("order/9f3b")
                .outcome(Outcome.SUCCESS)
                .context(Map.of("k", "v"))
                .build();

        AuditEventPage page = AuditEventPage.builder()
                .items(List.of(item))
                .nextCursor("eyJ0cyI6Li4ufQ")
                .build();

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = mapper.readValue(mapper.writeValueAsString(page), Map.class);

        assertThat(parsed.keySet()).containsExactlyInAnyOrder("items", "nextCursor");
        @SuppressWarnings("unchecked")
        Map<String, Object> first = ((List<Map<String, Object>>) parsed.get("items")).get(0);
        assertThat(first.keySet())
                .containsExactlyInAnyOrder("id", "timestamp", "actor", "action", "resource", "outcome", "context");
        assertThat(first).doesNotContainKey("prevHash");
        assertThat(first).doesNotContainKey("eventHash");
    }
}

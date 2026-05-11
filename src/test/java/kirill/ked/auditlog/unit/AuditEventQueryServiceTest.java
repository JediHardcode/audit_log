package kirill.ked.auditlog.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import kirill.ked.auditlog.api.AuditEventPage;
import kirill.ked.auditlog.api.AuditEventQuery;
import kirill.ked.auditlog.api.AuditEventResponse;
import kirill.ked.auditlog.domain.AuditEventQueryService;
import kirill.ked.auditlog.domain.Outcome;
import kirill.ked.auditlog.domain.query.CursorCodec;
import kirill.ked.auditlog.domain.query.QueryValidator;
import kirill.ked.auditlog.persistence.AuditEventReadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditEventQueryServiceTest {

    @Mock
    private AuditEventReadRepository readRepository;

    private AuditEventQueryService service;

    @BeforeEach
    void setUp() {
        CursorCodec codec = new CursorCodec(new ObjectMapper().registerModule(new JavaTimeModule()));
        service = new AuditEventQueryService(new QueryValidator(), codec, readRepository);
    }

    @Test
    void nextCursor_nullWhenResultFitsInPage() {
        when(readRepository.search(any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(sample(Instant.parse("2026-04-01T10:00:00Z"))));

        AuditEventPage page =
                service.search(AuditEventQuery.builder().limitRaw("50").build());

        assertThat(page.getNextCursor()).isNull();
        assertThat(page.getItems()).hasSize(1);
    }

    @Test
    void nextCursor_nullWhenResultEmpty() {
        when(readRepository.search(any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        AuditEventPage page = service.search(AuditEventQuery.builder().build());

        assertThat(page.getNextCursor()).isNull();
        assertThat(page.getItems()).isEmpty();
    }

    @Test
    void nextCursor_setWhenExtraRowFetched() {
        List<AuditEventResponse> rows = IntStream.range(0, 3)
                .mapToObj(i -> sample(Instant.parse("2026-04-01T10:00:00Z").plusSeconds(i)))
                .toList();
        when(readRepository.search(any(), any(), any(), any(), any(), any(), any(), any(), eq(3)))
                .thenReturn(rows);

        AuditEventPage page =
                service.search(AuditEventQuery.builder().limitRaw("2").build());

        assertThat(page.getItems()).hasSize(2);
        assertThat(page.getNextCursor()).isNotNull();
    }

    @Test
    void nanosecondInstant_producesSameCursorAsTruncated() {
        AuditEventResponse withNanos = AuditEventResponse.builder()
                .id(UUID.fromString("11111111-2222-3333-4444-555555555555"))
                .timestamp(Instant.parse("2026-04-01T10:00:00.123456789Z"))
                .actor("a")
                .action("x")
                .resource("r")
                .outcome(Outcome.SUCCESS)
                .context(null)
                .build();
        AuditEventResponse truncated = AuditEventResponse.builder()
                .id(withNanos.getId())
                .timestamp(Instant.parse("2026-04-01T10:00:00.123456Z"))
                .actor(withNanos.getActor())
                .action(withNanos.getAction())
                .resource(withNanos.getResource())
                .outcome(withNanos.getOutcome())
                .context(withNanos.getContext())
                .build();

        when(readRepository.search(any(), any(), any(), any(), any(), any(), any(), any(), eq(2)))
                .thenReturn(List.of(withNanos, withNanos))
                .thenReturn(List.of(truncated, truncated));

        String cursorA =
                service.search(AuditEventQuery.builder().limitRaw("1").build()).getNextCursor();
        String cursorB =
                service.search(AuditEventQuery.builder().limitRaw("1").build()).getNextCursor();

        assertThat(cursorA).isEqualTo(cursorB);
    }

    private AuditEventResponse sample(Instant ts) {
        return AuditEventResponse.builder()
                .id(UUID.randomUUID())
                .timestamp(ts)
                .actor("a")
                .action("x")
                .resource("r")
                .outcome(Outcome.SUCCESS)
                .context(null)
                .build();
    }
}

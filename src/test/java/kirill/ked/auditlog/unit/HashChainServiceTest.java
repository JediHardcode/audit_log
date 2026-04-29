package kirill.ked.auditlog.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kirill.ked.auditlog.domain.Outcome;
import kirill.ked.auditlog.hashchain.HashChainService;
import kirill.ked.auditlog.persistence.AuditEventEntity;
import kirill.ked.auditlog.persistence.AuditEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HashChainServiceTest {

    @Mock
    private AuditEventRepository repository;

    private HashChainService hashChainService;

    @BeforeEach
    void setUp() {
        ObjectMapper canonicalMapper = new ObjectMapper()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        hashChainService = new HashChainService(repository, canonicalMapper);
    }

    @Test
    void computeHash_returnsDeterministicHexString() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Instant ts = Instant.parse("2026-01-01T00:00:00Z");

        String hash1 = hashChainService.computeHash("GENESIS", id, ts, "u:1", "login", "app:1", Outcome.SUCCESS, null);
        String hash2 = hashChainService.computeHash("GENESIS", id, ts, "u:1", "login", "app:1", Outcome.SUCCESS, null);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).matches("[0-9a-f]{64}");
    }

    @Test
    void computeHash_changeOnDifferentInput() {
        UUID id = UUID.randomUUID();
        Instant ts = Instant.now();

        String h1 = hashChainService.computeHash("GENESIS", id, ts, "u:1", "login", "app:1", Outcome.SUCCESS, null);
        String h2 = hashChainService.computeHash("GENESIS", id, ts, "u:2", "login", "app:1", Outcome.SUCCESS, null);

        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void verifyChain_emptyTable_returnsTrue() {
        when(repository.findAllForChainVerification()).thenReturn(List.of());
        assertThat(hashChainService.verifyChain()).isTrue();
    }

    @Test
    void verifyChain_validChain_returnsTrue() {
        UUID id1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID id2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Instant ts1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant ts2 = Instant.parse("2026-01-01T00:00:01Z");

        String hash1 =
                hashChainService.computeHash("GENESIS", id1, ts1, "u:1", "login", "app:1", Outcome.SUCCESS, null);
        String hash2 = hashChainService.computeHash(hash1, id2, ts2, "u:2", "logout", "app:1", Outcome.SUCCESS, null);

        AuditEventEntity e1 = AuditEventEntity.builder()
                .id(id1)
                .timestamp(ts1)
                .actor("u:1")
                .action("login")
                .resource("app:1")
                .outcome(Outcome.SUCCESS)
                .prevHash(null)
                .eventHash(hash1)
                .build();

        AuditEventEntity e2 = AuditEventEntity.builder()
                .id(id2)
                .timestamp(ts2)
                .actor("u:2")
                .action("logout")
                .resource("app:1")
                .outcome(Outcome.SUCCESS)
                .prevHash(hash1)
                .eventHash(hash2)
                .build();

        when(repository.findAllForChainVerification()).thenReturn(List.of(e1, e2));

        assertThat(hashChainService.verifyChain()).isTrue();
    }

    @Test
    void verifyChain_tamperedEvent_returnsFalse() {
        UUID id1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Instant ts1 = Instant.parse("2026-01-01T00:00:00Z");

        AuditEventEntity tampered = AuditEventEntity.builder()
                .id(id1)
                .timestamp(ts1)
                .actor("u:1")
                .action("login")
                .resource("app:1")
                .outcome(Outcome.SUCCESS)
                .prevHash(null)
                .eventHash("deadbeef")
                .build();

        when(repository.findAllForChainVerification()).thenReturn(List.of(tampered));

        assertThat(hashChainService.verifyChain()).isFalse();
    }
}

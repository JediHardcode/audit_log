package kirill.ked.auditlog.unit;

import kirill.ked.auditlog.api.AuditEventResponse;
import kirill.ked.auditlog.api.CreateAuditEventRequest;
import kirill.ked.auditlog.domain.AuditEventService;
import kirill.ked.auditlog.domain.Outcome;
import kirill.ked.auditlog.hashchain.HashChainService;
import kirill.ked.auditlog.persistence.AuditEventEntity;
import kirill.ked.auditlog.persistence.AuditEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.temporal.ChronoUnit;

@ExtendWith(MockitoExtension.class)
class AuditEventServiceTest {

    @Mock
    private AuditEventRepository repository;

    @Mock
    private HashChainService hashChainService;

    private AuditEventService service;

    @BeforeEach
    void setUp() {
        service = new AuditEventService(repository, hashChainService);
    }

    @Test
    void create_setsServerSideTimestamp() {
        Instant before = Instant.now();
        mockSaveReturnsInput();
        when(repository.findLatest()).thenReturn(Optional.empty());
        when(hashChainService.computeHash(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("fakehash");

        CreateAuditEventRequest request = buildRequest();
        AuditEventResponse response = service.create(request);

        Instant after = Instant.now();
        assertThat(response.getTimestamp()).isBetween(before, after);
    }

    @Test
    void create_usesGenesisWhenNoEvents() {
        mockSaveReturnsInput();
        when(repository.findLatest()).thenReturn(Optional.empty());
        when(hashChainService.computeHash(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("hash1");

        service.create(buildRequest());

        ArgumentCaptor<String> prevCaptor = ArgumentCaptor.forClass(String.class);
        verify(hashChainService).computeHash(prevCaptor.capture(), any(), any(), any(), any(), any(), any(), any());
        assertThat(prevCaptor.getValue()).isEqualTo("GENESIS");
    }

    @Test
    void create_usesPreviousEventHash() {
        AuditEventEntity prev = AuditEventEntity.builder()
                .id(UUID.randomUUID())
                .timestamp(Instant.now().minusSeconds(60))
                .actor("svc")
                .action("login")
                .resource("app:1")
                .outcome(Outcome.SUCCESS)
                .prevHash(null)
                .eventHash("previoushash")
                .build();
        when(repository.findLatest()).thenReturn(Optional.of(prev));
        when(hashChainService.computeHash(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("nexthash");
        mockSaveReturnsInput();

        service.create(buildRequest());

        ArgumentCaptor<String> prevCaptor = ArgumentCaptor.forClass(String.class);
        verify(hashChainService).computeHash(prevCaptor.capture(), any(), any(), any(), any(), any(), any(), any());
        assertThat(prevCaptor.getValue()).isEqualTo("previoushash");
    }

    @Test
    void create_callsHashChainService() {
        when(repository.findLatest()).thenReturn(Optional.empty());
        when(hashChainService.computeHash(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("hash");
        mockSaveReturnsInput();

        service.create(buildRequest());

        verify(hashChainService).computeHash(anyString(), any(UUID.class), any(Instant.class),
                eq("user:1"), eq("doc.created"), eq("doc:99"), eq(Outcome.SUCCESS), isNull());
    }

    @Test
    void create_acquiresInsertLockBeforeHashRead() {
        when(repository.findLatest()).thenReturn(Optional.empty());
        when(hashChainService.computeHash(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("hash");
        mockSaveReturnsInput();

        service.create(buildRequest());

        var inOrder = inOrder(repository);
        inOrder.verify(repository).acquireInsertLock();
        inOrder.verify(repository).findLatest();
    }

    private void mockSaveReturnsInput() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private CreateAuditEventRequest buildRequest() {
        CreateAuditEventRequest req = new CreateAuditEventRequest();
        req.setActor("user:1");
        req.setAction("doc.created");
        req.setResource("doc:99");
        req.setOutcome(Outcome.SUCCESS);
        return req;
    }
}

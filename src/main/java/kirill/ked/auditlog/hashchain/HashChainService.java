package kirill.ked.auditlog.hashchain;

import kirill.ked.auditlog.domain.Outcome;
import kirill.ked.auditlog.persistence.AuditEventEntity;
import kirill.ked.auditlog.persistence.AuditEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class HashChainService {

    private final AuditEventRepository repository;
    @Qualifier("canonicalMapper")
    private final ObjectMapper canonicalMapper;

    /**
     * Computes SHA-256 hash for an event.
     *
     * @param prevHash previous event's hash, or "GENESIS" for the first event
     */
    public String computeHash(String prevHash, UUID id, Instant timestamp,
                               String actor, String action, String resource,
                               Outcome outcome, Map<String, Object> context) {
        String input = prevHash
                + id
                + timestamp
                + actor
                + action
                + resource
                + outcome.toJson()
                + serializeContext(context);
        return sha256Hex(input);
    }

    /**
     * Walks all events in chronological order and verifies the hash chain integrity.
     *
     * @return true if the chain is intact
     */
    public boolean verifyChain() {
        List<AuditEventEntity> events = repository.findAllForChainVerification();
        if (events.isEmpty()) {
            return true;
        }

        String expectedPrevHash = "GENESIS";
        for (AuditEventEntity event : events) {
            String storedPrev = event.getPrevHash() != null ? event.getPrevHash() : "GENESIS";
            if (!storedPrev.equals(expectedPrevHash)) {
                return false;
            }
            String expectedHash = computeHash(
                    expectedPrevHash,
                    event.getId(),
                    event.getTimestamp(),
                    event.getActor(),
                    event.getAction(),
                    event.getResource(),
                    event.getOutcome(),
                    event.getContext()
            );
            if (!expectedHash.equals(event.getEventHash())) {
                return false;
            }
            expectedPrevHash = event.getEventHash();
        }
        return true;
    }

    private String serializeContext(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return "";
        }
        try {
            return canonicalMapper.writeValueAsString(context);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize context for hashing", e);
        }
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

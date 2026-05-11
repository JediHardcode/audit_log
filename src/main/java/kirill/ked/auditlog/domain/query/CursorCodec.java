package kirill.ked.auditlog.domain.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * URL-safe base64 (no padding) ↔ {@link Cursor} JSON. Cursors are opaque to clients —
 * tampering surfaces here as {@link InvalidQueryException} ({@code invalid_cursor}) and
 * maps to HTTP 400. No HMAC: the service is internal and trusts callers (per design).
 *
 * <p>Mismatch between the cursor's sort/filter hash and the current request is also a
 * 400, but those checks live in the calling service.
 */
@Component
public class CursorCodec {

    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private final ObjectMapper mapper;

    public CursorCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String encode(Cursor cursor) {
        ObjectNode node = mapper.createObjectNode();
        node.put("ts", cursor.ts().truncatedTo(ChronoUnit.MICROS).toString());
        node.put("id", cursor.id().toString());
        node.put("s", cursor.sort().wire());
        node.put("f", cursor.filterHash());
        try {
            byte[] bytes = mapper.writeValueAsBytes(node);
            return ENC.encodeToString(bytes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode cursor", e);
        }
    }

    public Cursor decode(String raw) {
        byte[] bytes;
        try {
            bytes = DEC.decode(raw);
        } catch (IllegalArgumentException e) {
            throw new InvalidQueryException("invalid_cursor", "cursor is not valid base64");
        }

        JsonNode node;
        try {
            node = mapper.readTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw new InvalidQueryException("invalid_cursor", "cursor is not valid JSON");
        }

        if (node == null || !node.isObject()) {
            throw new InvalidQueryException("invalid_cursor", "cursor payload is not an object");
        }

        String ts = textField(node, "ts");
        String id = textField(node, "id");
        String s = textField(node, "s");
        String f = textField(node, "f");

        Instant tsInstant;
        try {
            tsInstant = Instant.parse(ts);
        } catch (DateTimeException e) {
            throw new InvalidQueryException("invalid_cursor", "cursor ts is not ISO-8601");
        }

        UUID idValue;
        try {
            idValue = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new InvalidQueryException("invalid_cursor", "cursor id is not a UUID");
        }

        SortDirection sort;
        try {
            sort = SortDirection.fromWire(s);
        } catch (InvalidQueryException e) {
            throw new InvalidQueryException("invalid_cursor", "cursor sort is not 'asc' or 'desc'");
        }

        return new Cursor(tsInstant, idValue, sort, f);
    }

    private static String textField(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual()) {
            throw new InvalidQueryException("invalid_cursor", "cursor missing field: " + key);
        }
        return value.asText();
    }
}

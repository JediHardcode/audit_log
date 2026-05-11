package kirill.ked.auditlog.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import kirill.ked.auditlog.domain.query.Cursor;
import kirill.ked.auditlog.domain.query.CursorCodec;
import kirill.ked.auditlog.domain.query.InvalidQueryException;
import kirill.ked.auditlog.domain.query.SortDirection;
import org.junit.jupiter.api.Test;

class CursorCodecTest {

    private final CursorCodec codec = new CursorCodec(new ObjectMapper().registerModule(new JavaTimeModule()));

    @Test
    void roundTrip_preservesAllFields() {
        Cursor original = new Cursor(
                Instant.parse("2026-04-17T11:02:14.123456Z"),
                UUID.fromString("11111111-2222-3333-4444-555555555555"),
                SortDirection.DESC,
                "abc123");

        Cursor decoded = codec.decode(codec.encode(original));

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void encoded_isUrlSafeBase64WithoutPadding() {
        Cursor c = new Cursor(
                Instant.parse("2026-04-17T11:02:14Z"),
                UUID.fromString("11111111-2222-3333-4444-555555555555"),
                SortDirection.ASC,
                "h");

        String encoded = codec.encode(c);

        assertThat(encoded).doesNotContain("=").doesNotContain("+").doesNotContain("/");
    }

    @Test
    void decode_badBase64_throwsInvalidCursor() {
        assertThatThrownBy(() -> codec.decode("not!base64!!"))
                .isInstanceOf(InvalidQueryException.class)
                .extracting("code")
                .isEqualTo("invalid_cursor");
    }

    @Test
    void decode_validBase64ButNotJson_throwsInvalidCursor() {
        String notJson =
                Base64.getUrlEncoder().withoutPadding().encodeToString("hello".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> codec.decode(notJson))
                .isInstanceOf(InvalidQueryException.class)
                .extracting("code")
                .isEqualTo("invalid_cursor");
    }

    @Test
    void decode_missingField_throwsInvalidCursor() {
        String partial = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"ts\":\"2026-04-17T11:02:14Z\"}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> codec.decode(partial))
                .isInstanceOf(InvalidQueryException.class)
                .extracting("code")
                .isEqualTo("invalid_cursor");
    }

    @Test
    void decode_badSortValue_throwsInvalidCursor() {
        String body = "{\"ts\":\"2026-04-17T11:02:14Z\","
                + "\"id\":\"11111111-2222-3333-4444-555555555555\","
                + "\"s\":\"sideways\","
                + "\"f\":\"h\"}";
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> codec.decode(encoded))
                .isInstanceOf(InvalidQueryException.class)
                .extracting("code")
                .isEqualTo("invalid_cursor");
    }

    @Test
    void filterHashDifference_isVisibleAfterDecode() {
        Cursor c1 = new Cursor(Instant.parse("2026-04-17T11:02:14Z"), UUID.randomUUID(), SortDirection.DESC, "hashA");
        Cursor c2 = new Cursor(c1.ts(), c1.id(), c1.sort(), "hashB");

        assertThat(codec.decode(codec.encode(c1)).filterHash()).isEqualTo("hashA");
        assertThat(codec.decode(codec.encode(c2)).filterHash()).isEqualTo("hashB");
    }
}

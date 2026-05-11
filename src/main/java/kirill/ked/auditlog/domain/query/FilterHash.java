package kirill.ked.auditlog.domain.query;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import kirill.ked.auditlog.domain.Outcome;

/**
 * Canonical filter fingerprint used to bind a cursor to the originating request.
 *
 * <p>Format: lowercase SHA-256 hex over {@code actor|resource|from|to|outcome},
 * each field stringified as below, separated by a single ASCII pipe.
 * <ul>
 *   <li>{@code null} → empty string</li>
 *   <li>{@code Instant} → ISO-8601 truncated to microseconds (matches storage precision)</li>
 *   <li>{@code Outcome} → lowercase wire name</li>
 * </ul>
 */
public final class FilterHash {

    private FilterHash() {}

    public static String compute(String actor, String resource, Instant from, Instant to, Outcome outcome) {
        String canonical = String.join(
                "|",
                nullSafe(actor),
                nullSafe(resource),
                instantToString(from),
                instantToString(to),
                outcome == null ? "" : outcome.toJson());
        return sha256Hex(canonical);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String instantToString(Instant ts) {
        if (ts == null) {
            return "";
        }
        return ts.truncatedTo(ChronoUnit.MICROS).toString();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

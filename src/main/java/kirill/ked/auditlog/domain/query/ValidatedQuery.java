package kirill.ked.auditlog.domain.query;

import java.time.Instant;
import java.util.List;
import kirill.ked.auditlog.domain.Outcome;
import lombok.Builder;
import lombok.Value;

/**
 * Result of {@link QueryValidator}: every field is parsed, semantically valid, and
 * normalized (timestamps truncated to microseconds, limit clamped to {@code [1, 200]}).
 * The query service operates exclusively on this type — never on raw request strings.
 */
@Value
@Builder
public class ValidatedQuery {

    List<String> actors;
    String resource;
    Instant from;
    Instant to;
    Outcome outcome;
    SortDirection sort;
    int limit;
    String cursor;
}

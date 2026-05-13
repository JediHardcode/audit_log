package kirill.ked.auditlog.domain.query;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import kirill.ked.auditlog.api.AuditEventQuery;
import kirill.ked.auditlog.domain.Outcome;
import org.springframework.stereotype.Component;

/**
 * Validates raw {@link AuditEventQuery} request params and returns a typed
 * {@link ValidatedQuery}. Every rejection is an {@link InvalidQueryException} →
 * HTTP 400.
 *
 * <p>Behaviour matches {@code requirements.md} AC-E1..E7 and AC-P7/P8:
 * <ul>
 *   <li>{@code limit} default 50, min 1, silently clamped to 200</li>
 *   <li>{@code outcome} strict lowercase (rejects {@code Success})</li>
 *   <li>{@code sort} strict lowercase, default {@code desc}</li>
 *   <li>{@code from > to} rejected; {@code to - from > 90d} rejected;
 *       open-ended ranges allowed</li>
 * </ul>
 */
@Component
public class QueryValidator {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final int MAX_ACTORS = 10;
    private static final Duration MAX_WINDOW = Duration.ofDays(90);

    public ValidatedQuery validate(AuditEventQuery raw) {
        Instant from = truncate(raw.getFrom());
        Instant to = truncate(raw.getTo());

        if (from != null && to != null) {
            if (from.isAfter(to)) {
                throw new InvalidQueryException("invalid_range", "from must be <= to");
            }
            if (Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
                throw new InvalidQueryException("range_too_large", "to - from must be <= 90 days");
            }
        }

        Outcome outcome = parseOutcome(raw.getOutcomeRaw());
        SortDirection sort = SortDirection.fromWire(raw.getSortRaw());
        int limit = parseLimit(raw.getLimitRaw());

        return ValidatedQuery.builder()
                .actors(parseActors(raw.getActor()))
                .resource(blankToNull(raw.getResource()))
                .from(from)
                .to(to)
                .outcome(outcome)
                .sort(sort)
                .limit(limit)
                .cursor(blankToNull(raw.getCursor()))
                .build();
    }

    private List<String> parseActors(String raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> actors = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .sorted()
                .toList();
        if (actors.size() > MAX_ACTORS) {
            throw new InvalidQueryException("too_many_actors", "actor filter supports at most 10 values");
        }
        return actors;
    }

    private Instant truncate(Instant ts) {
        return ts == null ? null : ts.truncatedTo(ChronoUnit.MICROS);
    }

    private Outcome parseOutcome(String raw) {
        if (raw == null) {
            return null;
        }
        for (Outcome o : Outcome.values()) {
            if (o.toJson().equals(raw)) {
                return o;
            }
        }
        throw new InvalidQueryException("invalid_outcome", "outcome must be one of: success, denied, error");
    }

    private int parseLimit(String raw) {
        if (raw == null) {
            return DEFAULT_LIMIT;
        }
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new InvalidQueryException("invalid_limit", "limit is not a valid integer");
        }
        if (value < 1) {
            throw new InvalidQueryException("invalid_limit", "limit must be >= 1");
        }
        return Math.min(value, MAX_LIMIT);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}

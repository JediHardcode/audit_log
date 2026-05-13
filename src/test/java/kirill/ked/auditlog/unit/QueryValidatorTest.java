package kirill.ked.auditlog.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.stream.IntStream;
import kirill.ked.auditlog.api.AuditEventQuery;
import kirill.ked.auditlog.domain.Outcome;
import kirill.ked.auditlog.domain.query.InvalidQueryException;
import kirill.ked.auditlog.domain.query.QueryValidator;
import kirill.ked.auditlog.domain.query.SortDirection;
import kirill.ked.auditlog.domain.query.ValidatedQuery;
import org.junit.jupiter.api.Test;

class QueryValidatorTest {

    private final QueryValidator validator = new QueryValidator();

    @Test
    void allDefaults_emptyQuery_returnsDefaults() {
        ValidatedQuery v = validator.validate(AuditEventQuery.builder().build());

        assertThat(v.getActors()).isEmpty();
        assertThat(v.getResource()).isNull();
        assertThat(v.getFrom()).isNull();
        assertThat(v.getTo()).isNull();
        assertThat(v.getOutcome()).isNull();
        assertThat(v.getSort()).isEqualTo(SortDirection.DESC);
        assertThat(v.getLimit()).isEqualTo(50);
    }

    @Test
    void outcome_mustBeStrictLowercase() {
        AuditEventQuery uppercase =
                AuditEventQuery.builder().outcomeRaw("Success").build();
        assertThatThrownBy(() -> validator.validate(uppercase))
                .isInstanceOf(InvalidQueryException.class)
                .extracting("code")
                .isEqualTo("invalid_outcome");

        AuditEventQuery lowercase =
                AuditEventQuery.builder().outcomeRaw("success").build();
        assertThat(validator.validate(lowercase).getOutcome()).isEqualTo(Outcome.SUCCESS);
    }

    @Test
    void sort_invalidValue_throws() {
        AuditEventQuery q = AuditEventQuery.builder().sortRaw("sideways").build();

        assertThatThrownBy(() -> validator.validate(q))
                .isInstanceOf(InvalidQueryException.class)
                .extracting("code")
                .isEqualTo("invalid_sort");
    }

    @Test
    void limit_nonNumeric_throws() {
        AuditEventQuery q = AuditEventQuery.builder().limitRaw("abc").build();

        assertThatThrownBy(() -> validator.validate(q))
                .isInstanceOf(InvalidQueryException.class)
                .extracting("code")
                .isEqualTo("invalid_limit");
    }

    @Test
    void limit_belowOne_throws() {
        AuditEventQuery q = AuditEventQuery.builder().limitRaw("0").build();

        assertThatThrownBy(() -> validator.validate(q))
                .isInstanceOf(InvalidQueryException.class)
                .extracting("code")
                .isEqualTo("invalid_limit");
    }

    @Test
    void limit_aboveMax_isClampedNot400() {
        AuditEventQuery q = AuditEventQuery.builder().limitRaw("500").build();

        assertThat(validator.validate(q).getLimit()).isEqualTo(200);
    }

    @Test
    void range_fromAfterTo_throws() {
        AuditEventQuery q = AuditEventQuery.builder()
                .from(Instant.parse("2026-04-01T10:00:00Z"))
                .to(Instant.parse("2026-04-01T09:00:00Z"))
                .build();

        assertThatThrownBy(() -> validator.validate(q))
                .isInstanceOf(InvalidQueryException.class)
                .extracting("code")
                .isEqualTo("invalid_range");
    }

    @Test
    void range_windowOver90Days_throws() {
        AuditEventQuery q = AuditEventQuery.builder()
                .from(Instant.parse("2026-01-01T00:00:00Z"))
                .to(Instant.parse("2026-04-05T00:00:00Z"))
                .build();

        assertThatThrownBy(() -> validator.validate(q))
                .isInstanceOf(InvalidQueryException.class)
                .extracting("code")
                .isEqualTo("range_too_large");
    }

    @Test
    void range_openEndedFrom_skipsWindowCheck() {
        AuditEventQuery q = AuditEventQuery.builder()
                .from(Instant.parse("2000-01-01T00:00:00Z"))
                .build();

        ValidatedQuery v = validator.validate(q);

        assertThat(v.getFrom()).isNotNull();
        assertThat(v.getTo()).isNull();
    }

    @Test
    void range_openEndedTo_skipsWindowCheck() {
        AuditEventQuery q = AuditEventQuery.builder()
                .to(Instant.parse("2099-01-01T00:00:00Z"))
                .build();

        ValidatedQuery v = validator.validate(q);

        assertThat(v.getFrom()).isNull();
        assertThat(v.getTo()).isNotNull();
    }

    @Test
    void timestamps_truncatedToMicroseconds() {
        AuditEventQuery q = AuditEventQuery.builder()
                .from(Instant.parse("2026-04-01T10:00:00.123456789Z"))
                .build();

        ValidatedQuery v = validator.validate(q);

        assertThat(v.getFrom()).isEqualTo(Instant.parse("2026-04-01T10:00:00.123456Z"));
    }

    @Test
    void actor_blankValues_ignored() {
        AuditEventQuery q = AuditEventQuery.builder().actor(" , , ").build();

        ValidatedQuery v = validator.validate(q);

        assertThat(v.getActors()).isEmpty();
    }

    @Test
    void actor_singleValue_returnsOneActor() {
        AuditEventQuery q = AuditEventQuery.builder().actor("u_42").build();

        ValidatedQuery v = validator.validate(q);

        assertThat(v.getActors()).containsExactly("u_42");
    }

    @Test
    void actor_tokensTrimmedDeduplicatedAndSorted() {
        AuditEventQuery q = AuditEventQuery.builder().actor("b, a, b, c ").build();

        ValidatedQuery v = validator.validate(q);

        assertThat(v.getActors()).containsExactly("a", "b", "c");
    }

    @Test
    void actor_exactlyTenDistinctValues_valid() {
        String actors = IntStream.rangeClosed(1, 10)
                .mapToObj(i -> "a" + i)
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();

        ValidatedQuery v =
                validator.validate(AuditEventQuery.builder().actor(actors).build());

        assertThat(v.getActors()).hasSize(10);
    }

    @Test
    void actor_moreThanTenDistinctValues_throws() {
        String actors = IntStream.rangeClosed(1, 11)
                .mapToObj(i -> "a" + i)
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();

        assertThatThrownBy(() -> validator.validate(
                        AuditEventQuery.builder().actor(actors).build()))
                .isInstanceOf(InvalidQueryException.class)
                .extracting("code")
                .isEqualTo("too_many_actors");
    }

    @Test
    void actor_moreThanTenRawTokensWithDuplicates_validWhenDistinctLimitNotExceeded() {
        AuditEventQuery q = AuditEventQuery.builder()
                .actor("a1,a2,a3,a4,a5,a6,a7,a8,a9,a10,a1,a2")
                .build();

        ValidatedQuery v = validator.validate(q);

        assertThat(v.getActors()).hasSize(10);
    }
}

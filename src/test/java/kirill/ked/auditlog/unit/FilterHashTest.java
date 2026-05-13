package kirill.ked.auditlog.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import kirill.ked.auditlog.domain.Outcome;
import kirill.ked.auditlog.domain.query.FilterHash;
import org.junit.jupiter.api.Test;

class FilterHashTest {

    @Test
    void sameEffectiveActorSetInDifferentOrder_hasSameHash() {
        String first = FilterHash.compute(
                List.of("a", "b"), "resource", Instant.parse("2026-05-01T10:00:00Z"), null, Outcome.SUCCESS);
        String second = FilterHash.compute(
                List.of("b", "a"), "resource", Instant.parse("2026-05-01T10:00:00Z"), null, Outcome.SUCCESS);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void duplicateActors_haveSameHashAsDistinctActorSet() {
        String withDuplicates = FilterHash.compute(List.of("a", "a", "b"), null, null, null, null);
        String distinct = FilterHash.compute(List.of("a", "b"), null, null, null, null);

        assertThat(withDuplicates).isEqualTo(distinct);
    }

    @Test
    void differentActorSets_haveDifferentHashes() {
        String first = FilterHash.compute(List.of("a", "b"), null, null, null, null);
        String second = FilterHash.compute(List.of("a", "c"), null, null, null, null);

        assertThat(first).isNotEqualTo(second);
    }
}

package kirill.ked.auditlog.architecture;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Guards current package-level architecture against accidental cross-layer coupling.
 * Rules reflect existing design, including DTO usage from domain, shared outcome enum,
 * and entity mapping in API.
 */
@AnalyzeClasses(packages = "kirill.ked.auditlog")
class LayerBoundariesArchTest {

    @ArchTest
    static final ArchRule layer_boundaries_are_respected = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("API")
            .definedBy("..api..")
            .layer("DOMAIN")
            .definedBy("..domain..")
            .layer("HASHCHAIN")
            .definedBy("..hashchain..")
            .layer("PERSISTENCE")
            .definedBy("..persistence..")
            .layer("RETENTION")
            .definedBy("..retention..")
            .layer("CONFIG")
            .definedBy("..config..")
            .whereLayer("API")
            .mayOnlyBeAccessedByLayers("DOMAIN")
            .whereLayer("DOMAIN")
            .mayOnlyBeAccessedByLayers("API", "PERSISTENCE", "HASHCHAIN")
            .whereLayer("HASHCHAIN")
            .mayOnlyBeAccessedByLayers("DOMAIN")
            .whereLayer("PERSISTENCE")
            .mayOnlyBeAccessedByLayers("API", "DOMAIN", "HASHCHAIN", "RETENTION")
            .whereLayer("RETENTION")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("CONFIG")
            .mayNotBeAccessedByAnyLayer();
}

package kirill.ked.auditlog.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import kirill.ked.auditlog.domain.Outcome;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateAuditEventRequest {

    @NotBlank
    private String actor;

    @NotBlank
    private String action;

    @NotBlank
    private String resource;

    @NotNull
    private Outcome outcome;

    private Map<String, Object> context;
}

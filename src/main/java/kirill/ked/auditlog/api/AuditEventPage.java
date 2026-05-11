package kirill.ked.auditlog.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Cursor-paginated page of audit events. {@code nextCursor} is {@code null} for the
 * last page and empty results.
 */
@Value
@Builder
@Jacksonized
public class AuditEventPage {

    List<AuditEventResponse> items;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    String nextCursor;
}

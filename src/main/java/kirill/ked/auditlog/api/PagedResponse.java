package kirill.ked.auditlog.api;

import java.util.List;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class PagedResponse<T> {

    List<T> content;
    int page;
    int size;
    long totalElements;
}

package kirill.ked.auditlog.api;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

@Value
@Builder
@Jacksonized
public class PagedResponse<T> {

    List<T> content;
    int page;
    int size;
    long totalElements;
}

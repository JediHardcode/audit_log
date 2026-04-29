package kirill.ked.auditlog.api;

import java.util.List;
import lombok.Value;

@Value
public class ErrorResponse {
    String error;
    String message;
    List<String> details;
}

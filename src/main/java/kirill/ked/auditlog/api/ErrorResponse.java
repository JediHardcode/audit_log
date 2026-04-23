package kirill.ked.auditlog.api;

import lombok.Value;

import java.util.List;

@Value
public class ErrorResponse {
    String error;
    String message;
    List<String> details;
}

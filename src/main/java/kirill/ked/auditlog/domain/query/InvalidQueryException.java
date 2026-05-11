package kirill.ked.auditlog.domain.query;

/**
 * Thrown by query layer when a request is rejected with HTTP 400. Carries a short
 * machine-readable {@code code} so the API layer can serialize a consistent error body.
 */
public class InvalidQueryException extends RuntimeException {

    private final String code;

    public InvalidQueryException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

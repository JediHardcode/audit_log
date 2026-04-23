package kirill.ked.auditlog.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Outcome {
    SUCCESS, DENIED, ERROR;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static Outcome fromJson(String value) {
        if (value == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown outcome: " + value);
        }
    }
}

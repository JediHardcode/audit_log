package kirill.ked.auditlog.domain.query;

/**
 * Public sort direction for the read API. Mirrors the parameter in {@code GET /audit-events}.
 */
public enum SortDirection {
    ASC("asc"),
    DESC("desc");

    private final String wire;

    SortDirection(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static SortDirection fromWire(String value) {
        if (value == null) {
            return DESC;
        }
        for (SortDirection d : values()) {
            if (d.wire.equals(value)) {
                return d;
            }
        }
        throw new InvalidQueryException("invalid_sort", "sort must be 'asc' or 'desc'");
    }
}

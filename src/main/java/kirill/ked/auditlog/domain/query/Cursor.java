package kirill.ked.auditlog.domain.query;

import java.time.Instant;
import java.util.UUID;

/**
 * Decoded continuation token: pointer to the row immediately after the previous page,
 * plus the sort and filter fingerprint that produced the page.
 */
public record Cursor(Instant ts, UUID id, SortDirection sort, String filterHash) {}

package kirill.ked.auditlog.domain;

import java.util.List;
import kirill.ked.auditlog.api.AuditEventPage;
import kirill.ked.auditlog.api.AuditEventQuery;
import kirill.ked.auditlog.api.AuditEventResponse;
import kirill.ked.auditlog.domain.query.Cursor;
import kirill.ked.auditlog.domain.query.CursorCodec;
import kirill.ked.auditlog.domain.query.FilterHash;
import kirill.ked.auditlog.domain.query.InvalidQueryException;
import kirill.ked.auditlog.domain.query.LikePrefix;
import kirill.ked.auditlog.domain.query.QueryValidator;
import kirill.ked.auditlog.domain.query.SortDirection;
import kirill.ked.auditlog.domain.query.ValidatedQuery;
import kirill.ked.auditlog.persistence.AuditEventReadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side query orchestrator for {@code GET /audit-events}. Validates request
 * params, decodes the optional cursor, enforces cursor binding (sort + filter hash),
 * fetches {@code limit + 1} rows from {@link AuditEventReadRepository}, and builds the
 * paged response with a {@code nextCursor} only when another page exists.
 */
@Service
@RequiredArgsConstructor
public class AuditEventQueryService {

    private final QueryValidator validator;
    private final CursorCodec cursorCodec;
    private final AuditEventReadRepository readRepository;

    @Transactional(readOnly = true)
    public AuditEventPage search(AuditEventQuery raw) {
        ValidatedQuery query = validator.validate(raw);

        String filterHash = FilterHash.compute(
                query.getActor(), query.getResource(), query.getFrom(), query.getTo(), query.getOutcome());

        Cursor cursor = null;
        if (query.getCursor() != null) {
            cursor = cursorCodec.decode(query.getCursor());
            if (cursor.sort() != query.getSort()) {
                throw new InvalidQueryException("invalid_cursor", "cursor sort does not match request");
            }
            if (!cursor.filterHash().equals(filterHash)) {
                throw new InvalidQueryException("invalid_cursor", "cursor filters do not match request");
            }
        }

        String resourcePrefix = LikePrefix.escape(query.getResource());

        List<AuditEventResponse> rows = readRepository.search(
                query.getActor(),
                resourcePrefix,
                query.getFrom(),
                query.getTo(),
                query.getOutcome(),
                toRepoDirection(query.getSort()),
                cursor != null ? cursor.ts() : null,
                cursor != null ? cursor.id() : null,
                query.getLimit() + 1);

        String nextCursor = null;
        if (rows.size() > query.getLimit()) {
            rows = rows.subList(0, query.getLimit());
            AuditEventResponse last = rows.get(rows.size() - 1);
            nextCursor = cursorCodec.encode(new Cursor(last.getTimestamp(), last.getId(), query.getSort(), filterHash));
        }

        return AuditEventPage.builder().items(rows).nextCursor(nextCursor).build();
    }

    private static AuditEventReadRepository.SortDirection toRepoDirection(SortDirection s) {
        return s == SortDirection.ASC
                ? AuditEventReadRepository.SortDirection.ASC
                : AuditEventReadRepository.SortDirection.DESC;
    }
}

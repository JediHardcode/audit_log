package kirill.ked.auditlog.domain.query;

/**
 * Escapes client input for use as a SQL {@code LIKE 'prefix%'} pattern. The escape
 * character is {@code \\} (matching the repository's {@code ESCAPE '\\'} clause), so
 * the literal sequence {@code \\}, {@code %}, and {@code _} are all replaced with
 * their escaped forms.
 *
 * <p>Without this, a client could send {@code resource=order/_} to broaden matching
 * (AC-X2/X3).
 */
public final class LikePrefix {

    private LikePrefix() {}

    public static String escape(String input) {
        if (input == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(input.length() + 8);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\\' || c == '%' || c == '_') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}

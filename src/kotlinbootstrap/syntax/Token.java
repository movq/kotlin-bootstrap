package kotlinbootstrap.syntax;

/**
 * A significant token. Whitespace and comments are not tokens; instead each
 * token records what preceded it, which is all the parser needs.
 *
 * @param text           the source text of the token
 * @param value          decoded value: identifier name without backticks,
 *                       string part contents, character literal contents
 * @param spaceBefore    whitespace or a comment directly precedes this token
 * @param newlineBefore  the whitespace between this token and the previous
 *                       one contains a newline (newlines inside block
 *                       comments do not count, as in the reference parser)
 */
public record Token(TokenKind kind, int start, int end, String text, String value,
                    boolean spaceBefore, boolean newlineBefore) {
    @Override
    public String toString() {
        return kind + "(" + text + ")@" + start;
    }
}

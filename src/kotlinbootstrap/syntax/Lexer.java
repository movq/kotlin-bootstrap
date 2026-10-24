package kotlinbootstrap.syntax;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written equivalent of the reference lexer (compiler/psi/parser/.../Kotlin.flex).
 *
 * <p>String templates are lexed with a stack of states, like the reference:
 * a string pushes a STRING or RAW_STRING state, "${" pushes a LONG_TEMPLATE
 * state that lexes ordinary code until its matching '}', and "$name" pushes a
 * SHORT_TEMPLATE state that lexes exactly one identifier.
 */
public final class Lexer {
    private enum Mode { CODE, LONG_TEMPLATE, STRING, RAW_STRING, SHORT_TEMPLATE }

    private static final class State {
        Mode mode;
        int lBraceCount;
        /** Number of '$' that start a template entry in this string, as set by "$$"-prefixed strings. */
        final int requiredPrefix;

        State(Mode mode, int requiredPrefix) {
            this.mode = mode;
            this.requiredPrefix = requiredPrefix;
        }
    }

    private static final Map<String, TokenKind> KEYWORDS = new HashMap<>();

    static {
        for (TokenKind kind : TokenKind.values()) {
            if (kind.isHardKeyword()) KEYWORDS.put(kind.text(), kind);
        }
    }

    private final SourceFile file;
    private final String s;
    private int pos;
    private final ArrayDeque<State> stack = new ArrayDeque<>();
    private State state = new State(Mode.CODE, -1);
    private boolean spaceBefore;
    private boolean newlineBefore;
    private final List<Token> tokens = new ArrayList<>();

    private Lexer(SourceFile file) {
        this.file = file;
        this.s = file.text();
    }

    public static List<Token> tokenize(SourceFile file) {
        Lexer lexer = new Lexer(file);
        lexer.run();
        return lexer.tokens;
    }

    private void run() {
        if (s.startsWith("#!")) {
            pos = lineEnd(0);
            spaceBefore = true;
        }
        while (true) {
            switch (state.mode) {
                case STRING, RAW_STRING -> lexStringContent();
                case SHORT_TEMPLATE -> lexShortTemplate();
                case CODE, LONG_TEMPLATE -> {
                    if (!lexCode()) {
                        if (state.mode != Mode.CODE) throw error(pos, "Unterminated string template");
                        tokens.add(new Token(TokenKind.EOF, pos, pos, "", "", spaceBefore, true));
                        return;
                    }
                }
            }
        }
    }

    private SyntaxError error(int offset, String message) {
        return new SyntaxError(file, offset, message);
    }

    private void emit(TokenKind kind, int start, int end) {
        emit(kind, start, end, null);
    }

    private void emit(TokenKind kind, int start, int end, String value) {
        String text = s.substring(start, end);
        tokens.add(new Token(kind, start, end, text, value != null ? value : text, spaceBefore, newlineBefore));
        pos = end;
        spaceBefore = false;
        newlineBefore = false;
    }

    private void push(Mode mode, int requiredPrefix) {
        stack.push(state);
        state = new State(mode, requiredPrefix);
    }

    private void pop() {
        state = stack.pop();
    }

    private char at(int i) {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private int lineEnd(int i) {
        int nl = s.indexOf('\n', i);
        return nl < 0 ? s.length() : nl;
    }

    // ---- Code ----

    /** Lexes one token of ordinary code. Returns false at end of input. */
    private boolean lexCode() {
        skipWhitespaceAndComments();
        if (pos >= s.length()) return false;
        int start = pos;
        char c = s.charAt(pos);

        if (c == '"' || c == '$') {
            int n = 0;
            while (at(pos + n) == '$') n++;
            if (at(pos + n) != '"') throw error(pos, "Unexpected '$'");
            if (n > 0) emit(TokenKind.INTERPOLATION_PREFIX, start, start + n);
            int quote = start + n;
            if (s.startsWith("\"\"\"", quote)) {
                push(Mode.RAW_STRING, Math.max(n, 1));
                emit(TokenKind.OPEN_QUOTE, quote, quote + 3);
            } else {
                push(Mode.STRING, Math.max(n, 1));
                emit(TokenKind.OPEN_QUOTE, quote, quote + 1);
            }
            return true;
        }
        if (isDigit(c) || (c == '.' && isDigit(at(pos + 1)))) {
            lexNumber();
            return true;
        }
        if (c == '\'') {
            lexCharacter();
            return true;
        }
        int identEnd = identifierEnd(pos);
        if (identEnd > 0) {
            lexIdentifierOrKeyword(identEnd);
            return true;
        }
        if (c == '`') throw error(pos, "Unmatched backtick");

        lexOperator(c);
        return true;
    }

    private void skipWhitespaceAndComments() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\f' || c == '\n') {
                if (c == '\n') newlineBefore = true;
                spaceBefore = true;
                pos++;
            } else if (c == '/' && at(pos + 1) == '/') {
                spaceBefore = true;
                pos = lineEnd(pos);
            } else if (c == '/' && at(pos + 1) == '*') {
                spaceBefore = true;
                skipBlockComment();
            } else {
                break;
            }
        }
    }

    private void skipBlockComment() {
        int start = pos;
        pos += 2;
        // "/**/" is a complete comment; otherwise comments nest.
        if (s.startsWith("*/", pos) && s.startsWith("/**/", start)) {
            pos += 2;
            return;
        }
        int depth = 0;
        while (pos < s.length()) {
            if (s.startsWith("/*", pos)) {
                depth++;
                pos += 2;
            } else if (s.startsWith("*/", pos)) {
                pos += 2;
                if (depth == 0) return;
                depth--;
            } else {
                pos++;
            }
        }
        throw error(start, "Unclosed comment");
    }

    private void lexOperator(char c) {
        int p = pos;
        TokenKind kind = switch (c) {
            case '=' -> s.startsWith("===", p) ? TokenKind.EQEQEQ
                    : s.startsWith("==", p) ? TokenKind.EQEQ
                    : s.startsWith("=>", p) ? TokenKind.DOUBLE_ARROW
                    : TokenKind.EQ;
            case '!' -> {
                if (s.startsWith("!==", p)) yield TokenKind.EXCLEQEQEQ;
                if (s.startsWith("!=", p)) yield TokenKind.EXCLEQ;
                // "!in"/"!is" followed by an identifier character is '!' then a name, e.g. "!isEmpty()".
                if (s.startsWith("!in", p) && !isIdentifierPart(codePointAt(p + 3))) yield TokenKind.NOT_IN;
                if (s.startsWith("!is", p) && !isIdentifierPart(codePointAt(p + 3))) yield TokenKind.NOT_IS;
                yield TokenKind.EXCL;
            }
            case '+' -> s.startsWith("++", p) ? TokenKind.PLUSPLUS
                    : s.startsWith("+=", p) ? TokenKind.PLUSEQ
                    : TokenKind.PLUS;
            case '-' -> s.startsWith("--", p) ? TokenKind.MINUSMINUS
                    : s.startsWith("-=", p) ? TokenKind.MINUSEQ
                    : s.startsWith("->", p) ? TokenKind.ARROW
                    : TokenKind.MINUS;
            case '*' -> s.startsWith("*=", p) ? TokenKind.MULTEQ : TokenKind.MUL;
            case '/' -> s.startsWith("/=", p) ? TokenKind.DIVEQ : TokenKind.DIV;
            case '%' -> s.startsWith("%=", p) ? TokenKind.PERCEQ : TokenKind.PERC;
            case '<' -> s.startsWith("<=", p) ? TokenKind.LTEQ : TokenKind.LT;
            case '>' -> s.startsWith(">=", p) ? TokenKind.GTEQ : TokenKind.GT;
            case '&' -> s.startsWith("&&", p) ? TokenKind.ANDAND : TokenKind.AND;
            case '|' -> {
                if (s.startsWith("||", p)) yield TokenKind.OROR;
                throw error(p, "Unexpected character '|'");
            }
            case '.' -> s.startsWith("...", p) ? TokenKind.RESERVED
                    : s.startsWith("..<", p) ? TokenKind.RANGE_UNTIL
                    : s.startsWith("..", p) ? TokenKind.RANGE
                    : TokenKind.DOT;
            case ':' -> s.startsWith("::", p) ? TokenKind.COLONCOLON : TokenKind.COLON;
            case ';' -> s.startsWith(";;", p) ? TokenKind.DOUBLE_SEMICOLON : TokenKind.SEMICOLON;
            case '?' -> TokenKind.QUEST;
            case '[' -> TokenKind.LBRACKET;
            case ']' -> TokenKind.RBRACKET;
            case '(' -> TokenKind.LPAR;
            case ')' -> TokenKind.RPAR;
            case ',' -> TokenKind.COMMA;
            case '#' -> TokenKind.HASH;
            case '@' -> TokenKind.AT;
            case '{' -> TokenKind.LBRACE;
            case '}' -> TokenKind.RBRACE;
            default -> throw error(p, "Unexpected character '" + c + "'");
        };

        if (state.mode == Mode.LONG_TEMPLATE) {
            if (kind == TokenKind.LBRACE) {
                state.lBraceCount++;
            } else if (kind == TokenKind.RBRACE) {
                if (state.lBraceCount == 0) {
                    pop();
                    emit(TokenKind.LONG_TEMPLATE_ENTRY_END, p, p + 1);
                    return;
                }
                state.lBraceCount--;
            }
        }
        emit(kind, p, p + kind.text().length());
    }

    // ---- Identifiers ----

    private int codePointAt(int i) {
        return i < s.length() ? s.codePointAt(i) : -1;
    }

    private static boolean isIdentifierStart(int cp) {
        return cp == '_' || (cp >= 0 && Character.isLetter(cp));
    }

    private static boolean isIdentifierPart(int cp) {
        return cp >= 0 && (isIdentifierStart(cp) || Character.isDigit(cp));
    }

    /** End offset of the (plain or backticked) identifier starting at {@code i}, or -1. */
    private int identifierEnd(int i) {
        int cp = codePointAt(i);
        if (cp == '`') {
            int j = i + 1;
            while (j < s.length() && s.charAt(j) != '`' && s.charAt(j) != '\n') j++;
            return (j < s.length() && s.charAt(j) == '`' && j > i + 1) ? j + 1 : -1;
        }
        if (!isIdentifierStart(cp)) return -1;
        int j = i + Character.charCount(cp);
        while (isIdentifierPart(codePointAt(j))) j += Character.charCount(codePointAt(j));
        return j;
    }

    private static String identifierValue(String text) {
        return text.startsWith("`") ? text.substring(1, text.length() - 1) : text;
    }

    private void lexIdentifierOrKeyword(int end) {
        String text = s.substring(pos, end);
        if (text.equals("as") && at(end) == '?') {
            emit(TokenKind.AS_SAFE, pos, end + 1);
            return;
        }
        TokenKind keyword = KEYWORDS.get(text);
        if (keyword != null) {
            emit(keyword, pos, end);
        } else {
            emit(TokenKind.IDENTIFIER, pos, end, identifierValue(text));
        }
    }

    // ---- Numbers and characters ----

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHexDigit(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private int skipDigits(int i) {
        while (isDigit(at(i)) || at(i) == '_') i++;
        return i;
    }

    private int skipIntegerSuffix(int i) {
        if (at(i) == 'u' || at(i) == 'U') i++;
        if (at(i) == 'l' || at(i) == 'L') i++;
        return i;
    }

    private void lexNumber() {
        int start = pos;
        char c0 = at(pos);
        char c1 = at(pos + 1);
        if (c0 == '0' && (c1 == 'x' || c1 == 'X')) {
            int i = pos + 2;
            while (isHexDigit(at(i)) || at(i) == '_') i++;
            emit(TokenKind.INTEGER_LITERAL, start, skipIntegerSuffix(i));
            return;
        }
        if (c0 == '0' && (c1 == 'b' || c1 == 'B')) {
            emit(TokenKind.INTEGER_LITERAL, start, skipIntegerSuffix(skipDigits(pos + 2)));
            return;
        }

        int i = c0 == '.' ? pos : skipDigits(pos);
        boolean isDouble = false;
        if (at(i) == '.' && isDigit(at(i + 1))) {
            isDouble = true;
            i = skipDigits(i + 1);
        }
        if (at(i) == 'e' || at(i) == 'E') {
            isDouble = true;
            i++;
            if (at(i) == '+' || at(i) == '-') i++;
            i = skipDigits(i);
        }
        if (at(i) == 'f' || at(i) == 'F') {
            isDouble = true;
            i++;
        }
        if (isDouble) {
            emit(TokenKind.FLOAT_LITERAL, start, i);
        } else {
            emit(TokenKind.INTEGER_LITERAL, start, skipIntegerSuffix(i));
        }
    }

    private void lexCharacter() {
        int start = pos;
        int i = pos + 1;
        StringBuilder value = new StringBuilder();
        while (i < s.length() && s.charAt(i) != '\'' && s.charAt(i) != '\n') {
            if (s.charAt(i) == '\\') {
                i = decodeEscape(i, value);
            } else {
                value.append(s.charAt(i++));
            }
        }
        if (at(i) != '\'') throw error(start, "Unterminated character literal");
        if (value.length() != 1) throw error(start, "Character literal must contain exactly one character");
        emit(TokenKind.CHARACTER_LITERAL, start, i + 1, value.toString());
    }

    /** Decodes the escape sequence at {@code i} into {@code out}; returns the offset after it. */
    private int decodeEscape(int i, StringBuilder out) {
        char c = at(i + 1);
        switch (c) {
            case 't' -> out.append('\t');
            case 'b' -> out.append('\b');
            case 'n' -> out.append('\n');
            case 'r' -> out.append('\r');
            case '\'' -> out.append('\'');
            case '"' -> out.append('"');
            case '\\' -> out.append('\\');
            case '$' -> out.append('$');
            case 'u' -> {
                for (int k = 2; k < 6; k++) {
                    if (!isHexDigit(at(i + k))) throw error(i, "Illegal escape: " + s.substring(i, Math.min(i + k + 1, s.length())));
                }
                out.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                return i + 6;
            }
            default -> throw error(i, "Illegal escape: \\" + c);
        }
        return i + 2;
    }

    // ---- Strings ----

    private void lexStringContent() {
        if (pos >= s.length()) throw error(pos, "Unterminated string literal");
        boolean raw = state.mode == Mode.RAW_STRING;
        int start = pos;
        char c = s.charAt(pos);

        if (c == '"') {
            if (!raw) {
                pop();
                emit(TokenKind.CLOSING_QUOTE, start, start + 1);
                return;
            }
            int q = 0;
            while (at(pos + q) == '"') q++;
            if (q == 3) {
                pop();
                emit(TokenKind.CLOSING_QUOTE, start, start + 3);
            } else if (q > 3) {
                // Extra quotes before the closing """ are part of the string.
                emit(TokenKind.STRING_PART, start, start + q - 3);
            } else {
                emit(TokenKind.STRING_PART, start, start + q);
            }
            return;
        }
        if (c == '\n' && !raw) throw error(pos, "Unterminated string literal");
        if (c == '\\' && !raw) {
            StringBuilder value = new StringBuilder();
            int end = decodeEscape(pos, value);
            emit(TokenKind.STRING_PART, start, end, value.toString());
            return;
        }
        if (c == '$') {
            lexDollars();
            return;
        }

        int i = pos;
        while (i < s.length()) {
            char d = s.charAt(i);
            if (d == '"' || d == '$' || (!raw && (d == '\\' || d == '\n'))) break;
            i++;
        }
        emit(TokenKind.STRING_PART, start, i);
    }

    /** Handles a run of '$' inside a string: template entry start or literal dollars. */
    private void lexDollars() {
        int start = pos;
        int n = 0;
        while (at(pos + n) == '$') n++;
        int after = pos + n;
        int required = state.requiredPrefix;
        boolean shortEntry = identifierEnd(after) > 0;
        boolean longEntry = at(after) == '{';

        if (!shortEntry && !longEntry) {
            emit(TokenKind.STRING_PART, start, after);
        } else if (n < required) {
            emit(TokenKind.STRING_PART, start, after);
        } else if (n > required) {
            // Leading surplus dollars are literal; the last `required` of them start the entry.
            emit(TokenKind.STRING_PART, start, start + n - required);
        } else if (shortEntry) {
            push(Mode.SHORT_TEMPLATE, -1);
            emit(TokenKind.SHORT_TEMPLATE_ENTRY_START, start, after);
        } else {
            push(Mode.LONG_TEMPLATE, -1);
            emit(TokenKind.LONG_TEMPLATE_ENTRY_START, start, after + 1);
        }
    }

    private void lexShortTemplate() {
        int end = identifierEnd(pos);
        String text = s.substring(pos, end);
        pop();
        if (text.equals("this")) {
            emit(TokenKind.THIS_KEYWORD, pos, end);
        } else {
            emit(TokenKind.IDENTIFIER, pos, end, identifierValue(text));
        }
    }
}

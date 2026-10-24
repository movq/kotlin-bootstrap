package kotlinbootstrap.syntax;

/**
 * Token kinds, following KtTokens. Soft keywords and modifiers are not token
 * kinds: they lex as IDENTIFIER and the parser checks their text.
 */
public enum TokenKind {
    IDENTIFIER,
    INTEGER_LITERAL,
    FLOAT_LITERAL,
    CHARACTER_LITERAL,

    // String templates.
    INTERPOLATION_PREFIX,
    OPEN_QUOTE,
    CLOSING_QUOTE,
    /** Literal text or an escape sequence; {@link Token#value()} holds the decoded text. */
    STRING_PART,
    SHORT_TEMPLATE_ENTRY_START,
    LONG_TEMPLATE_ENTRY_START,
    LONG_TEMPLATE_ENTRY_END,

    // Hard keywords.
    PACKAGE_KEYWORD("package"),
    AS_KEYWORD("as"),
    TYPEALIAS_KEYWORD("typealias"),
    CLASS_KEYWORD("class"),
    INTERFACE_KEYWORD("interface"),
    THIS_KEYWORD("this"),
    SUPER_KEYWORD("super"),
    VAL_KEYWORD("val"),
    VAR_KEYWORD("var"),
    FUN_KEYWORD("fun"),
    FOR_KEYWORD("for"),
    NULL_KEYWORD("null"),
    TRUE_KEYWORD("true"),
    FALSE_KEYWORD("false"),
    IS_KEYWORD("is"),
    IN_KEYWORD("in"),
    THROW_KEYWORD("throw"),
    RETURN_KEYWORD("return"),
    BREAK_KEYWORD("break"),
    CONTINUE_KEYWORD("continue"),
    OBJECT_KEYWORD("object"),
    IF_KEYWORD("if"),
    ELSE_KEYWORD("else"),
    WHILE_KEYWORD("while"),
    DO_KEYWORD("do"),
    TRY_KEYWORD("try"),
    WHEN_KEYWORD("when"),
    TYPEOF_KEYWORD("typeof"),
    NOT_IN("!in"),
    NOT_IS("!is"),
    AS_SAFE("as?"),

    // Punctuation and operators.
    RESERVED("..."),
    EQEQEQ("==="),
    EXCLEQEQEQ("!=="),
    PLUSPLUS("++"),
    MINUSMINUS("--"),
    LTEQ("<="),
    GTEQ(">="),
    EQEQ("=="),
    EXCLEQ("!="),
    ANDAND("&&"),
    AND("&"),
    OROR("||"),
    MULTEQ("*="),
    DIVEQ("/="),
    PERCEQ("%="),
    PLUSEQ("+="),
    MINUSEQ("-="),
    ARROW("->"),
    DOUBLE_ARROW("=>"),
    RANGE(".."),
    RANGE_UNTIL("..<"),
    COLONCOLON("::"),
    LBRACKET("["),
    RBRACKET("]"),
    LBRACE("{"),
    RBRACE("}"),
    LPAR("("),
    RPAR(")"),
    DOT("."),
    MUL("*"),
    PLUS("+"),
    MINUS("-"),
    EXCL("!"),
    DIV("/"),
    PERC("%"),
    LT("<"),
    GT(">"),
    QUEST("?"),
    COLON(":"),
    DOUBLE_SEMICOLON(";;"),
    SEMICOLON(";"),
    EQ("="),
    COMMA(","),
    HASH("#"),
    AT("@"),

    // Never produced by the lexer: the parser joins adjacent QUEST/DOT,
    // QUEST/COLON and EXCL/EXCL into these, as the reference parser does.
    SAFE_ACCESS("?."),
    ELVIS("?:"),
    EXCLEXCL("!!"),

    EOF;

    private final String text;

    TokenKind() {
        this(null);
    }

    TokenKind(String text) {
        this.text = text;
    }

    /** Fixed text of keyword and punctuation tokens, otherwise null. */
    public String text() {
        return text;
    }

    public boolean isHardKeyword() {
        return compareTo(PACKAGE_KEYWORD) >= 0 && compareTo(TYPEOF_KEYWORD) <= 0;
    }

    @Override
    public String toString() {
        return text != null ? "'" + text + "'" : name();
    }
}

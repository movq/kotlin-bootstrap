package kotlinbootstrap.ast;

import java.util.HashMap;
import java.util.Map;

/** Modifier keywords, in the reference's canonical order (KtTokens.MODIFIER_KEYWORDS_ARRAY). */
public enum Modifier {
    PUBLIC, PROTECTED, PRIVATE, INTERNAL,
    EXPECT, ACTUAL,
    FINAL, OPEN, ABSTRACT, SEALED,
    CONST,
    EXTERNAL,
    OVERRIDE,
    LATEINIT,
    TAILREC,
    VARARG,
    SUSPEND,
    INNER,
    ENUM, ANNOTATION, FUN,
    COMPANION,
    INLINE,
    VALUE,
    INFIX,
    OPERATOR,
    DATA,
    OUT, IN,
    REIFIED,
    NOINLINE,
    CROSSINLINE;

    private static final Map<String, Modifier> BY_KEYWORD = new HashMap<>();

    static {
        for (Modifier m : values()) BY_KEYWORD.put(m.keyword(), m);
    }

    public String keyword() {
        return name().toLowerCase();
    }

    /** The modifier spelled by {@code keyword}, or null. */
    public static Modifier fromKeyword(String keyword) {
        return BY_KEYWORD.get(keyword);
    }
}

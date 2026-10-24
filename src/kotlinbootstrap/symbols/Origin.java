package kotlinbootstrap.symbols;

/** Where a symbol was declared. */
public enum Origin {
    KOTLIN_SOURCE,
    JAVA_SOURCE,
    /** A compiled Java class, i.e. from the JDK. */
    JAVA_BINARY,
    /** Made up by the compiler, like {@code kotlin.Function1}. */
    SYNTHETIC
}

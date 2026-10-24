package kotlinbootstrap.types;

public enum Variance {
    INVARIANT(""), IN("in "), OUT("out ");

    private final String prefix;

    Variance(String prefix) {
        this.prefix = prefix;
    }

    /** "in ", "out " or "", for rendering. */
    public String prefix() {
        return prefix;
    }
}

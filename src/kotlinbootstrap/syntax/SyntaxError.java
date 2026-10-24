package kotlinbootstrap.syntax;

/** A lexical or syntactic error. The bootstrap front end stops at the first one. */
public final class SyntaxError extends RuntimeException {
    private final SourceFile file;
    private final int offset;

    public SyntaxError(SourceFile file, int offset, String message) {
        super(message);
        this.file = file;
        this.offset = offset;
    }

    public SourceFile file() {
        return file;
    }

    public int offset() {
        return offset;
    }

    @Override
    public String toString() {
        return file.describe(offset) + ": " + getMessage();
    }
}

package kotlinbootstrap.resolve;

import kotlinbootstrap.syntax.SourceFile;

/** A name or type that cannot be resolved. Like syntax errors, the first one stops compilation. */
public final class ResolveException extends RuntimeException {
    private final SourceFile file;
    private final int offset;

    /** @param file nullable, for errors in declarations that did not come from Kotlin source */
    public ResolveException(SourceFile file, int offset, String message) {
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
        return (file == null ? "" : file.describe(offset) + ": ") + getMessage();
    }
}

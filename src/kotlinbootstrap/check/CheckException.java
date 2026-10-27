package kotlinbootstrap.check;

import kotlinbootstrap.syntax.SourceFile;

/**
 * An expression that cannot be checked: an unresolved name, a call with
 * no applicable function, a type that cannot be inferred. The first one
 * stops checking the declaration.
 */
public final class CheckException extends RuntimeException {
    private final SourceFile file;
    private final int offset;

    public CheckException(SourceFile file, int offset, String message) {
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

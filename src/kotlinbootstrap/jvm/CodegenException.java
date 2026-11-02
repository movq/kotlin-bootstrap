package kotlinbootstrap.jvm;

import kotlinbootstrap.syntax.SourceFile;

/** Code that cannot be generated: a construct not supported yet, or an inconsistency in the checked trees. */
public final class CodegenException extends RuntimeException {
    private final int offset;
    private SourceFile file;

    /** @param offset the position in the source, or -1 if not known */
    public CodegenException(int offset, String message) {
        super(message);
        this.offset = offset;
    }

    /** The exception with the file it happened in, if not known yet. */
    CodegenException in(SourceFile file) {
        if (this.file == null) this.file = file;
        return this;
    }

    public int offset() {
        return offset;
    }

    @Override
    public String toString() {
        return (file == null ? "" : offset >= 0 ? file.describe(offset) + ": " : file.path() + ": ") + getMessage();
    }
}

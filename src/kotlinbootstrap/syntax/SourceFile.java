package kotlinbootstrap.syntax;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** The text of one source file, plus offset to line/column mapping. */
public final class SourceFile {
    private final String path;
    private final String text;
    private final int[] lineStarts;

    public SourceFile(String path, String text) {
        this.path = path;
        this.text = text;
        this.lineStarts = computeLineStarts(text);
    }

    public static SourceFile read(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        // kotlinc (via IntelliJ's file loading) strips the BOM and normalizes line endings.
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        return new SourceFile(path.toString(), text);
    }

    public String path() {
        return path;
    }

    public String text() {
        return text;
    }

    /** 1-based line number of an offset. */
    public int line(int offset) {
        int i = Arrays.binarySearch(lineStarts, offset);
        return (i >= 0 ? i : -i - 2) + 1;
    }

    /** 1-based column number of an offset. */
    public int column(int offset) {
        return offset - lineStarts[line(offset) - 1] + 1;
    }

    public String describe(int offset) {
        return path + ":" + line(offset) + ":" + column(offset);
    }

    private static int[] computeLineStarts(String text) {
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') count++;
        }
        int[] starts = new int[count];
        int line = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') starts[line++] = i + 1;
        }
        return starts;
    }
}

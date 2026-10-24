package kotlinbootstrap.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import kotlinbootstrap.ast.AstPrinter;
import kotlinbootstrap.syntax.Parser;
import kotlinbootstrap.syntax.SourceFile;
import kotlinbootstrap.syntax.SyntaxError;

/**
 * Golden tests: each {@code foo.kt} in the directory is parsed and its AST
 * dump compared with {@code foo.txt}. A file that fails to parse dumps as
 * the error message, so rejection can be tested too.
 *
 * <p>With {@code --update}, the expectations are rewritten instead.
 */
public final class GoldenTests {
    public static void main(String[] args) throws IOException {
        boolean update = args[0].equals("--update");
        Path dir = Path.of(args[update ? 1 : 0]);
        List<Path> inputs;
        try (Stream<Path> files = Files.list(dir)) {
            inputs = files.filter(f -> f.toString().endsWith(".kt")).sorted().toList();
        }
        int failures = 0;
        for (Path input : inputs) {
            Path expected = Path.of(input.toString().replaceFirst("\\.kt$", ".txt"));
            String actual = dump(input);
            if (update) {
                Files.writeString(expected, actual, StandardCharsets.UTF_8);
            } else if (!Files.exists(expected)) {
                System.out.println("MISSING " + expected);
                failures++;
            } else if (!Files.readString(expected, StandardCharsets.UTF_8).equals(actual)) {
                System.out.println("FAIL " + input);
                failures++;
            }
        }
        System.out.println(inputs.size() + " golden tests, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static String dump(Path input) throws IOException {
        SourceFile file = SourceFile.read(input);
        try {
            return AstPrinter.print(Parser.parse(file));
        } catch (SyntaxError e) {
            return "error " + file.line(e.offset()) + ":" + file.column(e.offset()) + ": " + e.getMessage() + "\n";
        }
    }
}

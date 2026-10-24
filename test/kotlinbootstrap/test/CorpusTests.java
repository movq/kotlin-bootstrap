package kotlinbootstrap.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import kotlinbootstrap.syntax.Parser;
import kotlinbootstrap.syntax.SourceFile;

/**
 * Parses every {@code .kt} file in a source tree, except test data (which
 * is often deliberately malformed). All of them must parse.
 */
public final class CorpusTests {
    public static void main(String[] args) throws IOException {
        List<Path> inputs;
        try (Stream<Path> files = Files.walk(Path.of(args[0]))) {
            inputs = files.filter(f -> f.toString().endsWith(".kt"))
                    .filter(f -> !isTestData(f))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        }
        long start = System.nanoTime();
        long bytes = 0;
        int failures = 0;
        for (Path input : inputs) {
            try {
                SourceFile file = SourceFile.read(input);
                bytes += file.text().length();
                Parser.parse(file);
            } catch (RuntimeException | StackOverflowError e) {
                System.out.println("FAIL " + (e.getMessage() == null ? input + ": " + e : e.toString()));
                failures++;
            }
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("%d files (%d MB) in %d ms, %d failures%n", inputs.size(), bytes >> 20, millis, failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    private static boolean isTestData(Path path) {
        for (Path part : path) {
            String name = part.toString().toLowerCase();
            if (name.equals("testdata") || name.equals("build") || name.equals("out")) return true;
        }
        return false;
    }
}

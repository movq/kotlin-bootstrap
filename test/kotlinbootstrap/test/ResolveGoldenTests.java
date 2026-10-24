package kotlinbootstrap.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import kotlinbootstrap.Frontend;
import kotlinbootstrap.Stdlib;
import kotlinbootstrap.resolve.FileContext;
import kotlinbootstrap.resolve.Resolver;
import kotlinbootstrap.resolve.SymbolPrinter;

/**
 * Golden tests for declaration resolution: the files in the directory are
 * resolved together with the stdlib sources, and the declarations of each
 * {@code foo.kt} printed and compared with {@code foo.txt}. Each test file
 * should have a package of its own. Java files in the directory are
 * available to the Kotlin ones.
 *
 * <p>Arguments: [--update] kotlin-source-root test-directory.
 */
public final class ResolveGoldenTests {
    public static void main(String[] args) throws IOException {
        boolean update = args[0].equals("--update");
        Path kotlinSource = Path.of(args[update ? 1 : 0]);
        Path dir = Path.of(args[update ? 2 : 1]);
        List<Path> inputs;
        try (Stream<Path> files = Files.list(dir)) {
            inputs = files.filter(f -> f.toString().endsWith(".kt")).sorted().toList();
        }
        List<Path> paths = new ArrayList<>(Stdlib.sourceRoots(kotlinSource));
        paths.add(dir);
        Resolver resolver = Frontend.load(paths, "8");

        int failures = 0;
        for (Path input : inputs) {
            FileContext file = resolver.files().stream()
                    .filter(f -> f.file().source().path().equals(input.toString())).findFirst().orElseThrow();
            Path expected = Path.of(input.toString().replaceFirst("\\.kt$", ".txt"));
            String actual = SymbolPrinter.print(resolver, file);
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
        System.out.println(inputs.size() + " resolve golden tests, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }
}

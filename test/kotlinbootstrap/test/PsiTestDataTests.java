package kotlinbootstrap.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import kotlinbootstrap.syntax.Parser;
import kotlinbootstrap.syntax.SourceFile;

/**
 * Runs the parser over the reference parser's test data. Each {@code foo.kt}
 * has a PSI dump {@code foo.txt}; we must accept the file iff the dump has
 * no error elements (other than in KDoc, which we do not parse).
 *
 * <p>Files marked {@code COMPILATION_ERRORS} whose dump has no syntax errors
 * are semantically invalid. Some of that is syntax to us, because we reject
 * what the reference parses but later diagnoses (e.g. {@code a.{b}}), so
 * rejecting those is tolerated.
 */
public final class PsiTestDataTests {
    /** Files where we knowingly deviate from the reference, relative to the test data root. */
    private static final Set<String> KNOWN_DEVIATIONS = Set.of(
            // The reference speculatively parses `x < (if ...` as type arguments and commits to
            // them, errors and all, because a '>' follows. We only commit to error-free type arguments.
            "complicateLTGT.kt");

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args[0]);
        List<Path> inputs;
        try (Stream<Path> files = Files.walk(root)) {
            inputs = files.filter(f -> f.toString().endsWith(".kt")).sorted().toList();
        }
        int failures = 0;
        int skipped = 0;
        int tolerated = 0;
        for (Path input : inputs) {
            Path dump = Path.of(input.toString().replaceFirst("\\.kt$", ".txt"));
            if (!Files.exists(dump)) {
                skipped++;
                continue;
            }
            boolean valid = !hasSyntaxErrors(Files.readString(dump, StandardCharsets.UTF_8));
            boolean compiles = !Files.readString(input, StandardCharsets.UTF_8).contains("COMPILATION_ERRORS");
            String error = null;
            try {
                Parser.parse(SourceFile.read(input));
            } catch (RuntimeException | StackOverflowError e) {
                error = e.toString();
            }
            if (KNOWN_DEVIATIONS.contains(root.relativize(input).toString())) {
                tolerated++;
            } else if (valid && error != null && !compiles) {
                tolerated++;
            } else if (valid && error != null) {
                System.out.println("REJECTED " + error);
                failures++;
            } else if (!valid && error == null) {
                System.out.println("ACCEPTED " + input);
                failures++;
            }
        }
        System.out.println(inputs.size() + " psi test files, " + skipped + " skipped, " + tolerated
                + " tolerated, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** Whether a PSI dump has error elements outside KDoc comments. */
    private static boolean hasSyntaxErrors(String dump) {
        // The dump is an indented tree. Track the ancestors of each line.
        Deque<String> ancestors = new ArrayDeque<>();
        Deque<Integer> indents = new ArrayDeque<>();
        for (String line : dump.split("\n")) {
            String content = line.stripLeading();
            int indent = line.length() - content.length();
            while (!indents.isEmpty() && indents.peek() >= indent) {
                indents.pop();
                ancestors.pop();
            }
            if (content.startsWith("PsiErrorElement") && ancestors.stream().noneMatch(a -> a.startsWith("KDoc"))) {
                return true;
            }
            indents.push(indent);
            ancestors.push(content);
        }
        return false;
    }
}

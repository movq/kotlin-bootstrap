package kotlinbootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import kotlinbootstrap.ast.AstPrinter;
import kotlinbootstrap.ast.KtFile;
import kotlinbootstrap.java.JavaSourceClasses;
import kotlinbootstrap.java.JdkClasses;
import kotlinbootstrap.resolve.ResolveException;
import kotlinbootstrap.resolve.Resolver;
import kotlinbootstrap.resolve.SignatureCompleter;
import kotlinbootstrap.symbols.SymbolTable;
import kotlinbootstrap.syntax.Parser;
import kotlinbootstrap.syntax.SourceFile;
import kotlinbootstrap.syntax.SyntaxError;

/** Command line entry point. */
public final class Main {
    private static final String USAGE = """
            usage: kbc parse <file.kt>...
                   kbc resolve [--jdk-release N] <file.kt, file.java or directory>...""";

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) usage();
        switch (args[0]) {
            case "parse" -> parse(List.of(args).subList(1, args.length));
            case "resolve" -> resolve(List.of(args).subList(1, args.length));
            default -> usage();
        }
    }

    private static void usage() {
        System.err.println(USAGE);
        System.exit(2);
    }

    private static void parse(List<String> paths) throws IOException {
        int failures = 0;
        for (String path : paths) {
            SourceFile file = SourceFile.read(Path.of(path));
            try {
                System.out.print(AstPrinter.print(Parser.parse(file)));
            } catch (SyntaxError e) {
                System.err.println(e);
                failures++;
            }
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    /** Resolves the declarations of the sources; for now, just reports errors. */
    private static void resolve(List<String> args) throws IOException {
        String release = "8";
        List<Path> paths = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equals("--jdk-release") && i + 1 < args.size()) {
                release = args.get(++i);
            } else {
                paths.add(Path.of(args.get(i)));
            }
        }
        try {
            SymbolTable table = new SymbolTable();
            Resolver resolver = new Resolver(table);
            table.addFinder(new JavaSourceClasses(table, sourceFiles(paths, ".java")));
            table.addFinder(new JdkClasses(table, release));
            resolver.enter(parseAll(sourceFiles(paths, ".kt")));
            int count = SignatureCompleter.completeAll(resolver);
            System.out.println(resolver.files().size() + " files, " + count + " classifiers resolved");
        } catch (SyntaxError | ResolveException e) {
            System.err.println(e);
            System.exit(1);
        }
    }

    /**
     * The files with the extension among the paths and under the directories
     * among them, in a stable order.
     */
    public static List<Path> sourceFiles(List<Path> paths, String extension) throws IOException {
        List<Path> result = new ArrayList<>();
        for (Path path : paths) {
            if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.walk(path)) {
                    files.filter(f -> f.toString().endsWith(extension)).sorted().forEach(result::add);
                }
            } else if (path.toString().endsWith(extension)) {
                result.add(path);
            }
        }
        return result;
    }

    public static List<KtFile> parseAll(List<Path> files) throws IOException {
        List<KtFile> result = new ArrayList<>();
        for (Path f : files) result.add(Parser.parse(SourceFile.read(f)));
        return result;
    }
}

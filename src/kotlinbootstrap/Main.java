package kotlinbootstrap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import kotlinbootstrap.ast.AstPrinter;
import kotlinbootstrap.check.CheckException;
import kotlinbootstrap.check.Checker;
import kotlinbootstrap.resolve.FileContext;
import kotlinbootstrap.resolve.ResolveException;
import kotlinbootstrap.resolve.Resolver;
import kotlinbootstrap.resolve.SignatureCompleter;
import kotlinbootstrap.resolve.SymbolPrinter;
import kotlinbootstrap.syntax.Parser;
import kotlinbootstrap.syntax.SourceFile;
import kotlinbootstrap.syntax.SyntaxError;

/** Command line entry point. */
public final class Main {
    private static final String USAGE = """
            usage: kbc parse <file.kt>...
                   kbc resolve [--jdk-release N] [--dump] <file.kt, file.java or directory>...
                   kbc check [--jdk-release N] [--errors] <file.kt, file.java or directory>...""";

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) usage();
        switch (args[0]) {
            case "parse" -> parse(List.of(args).subList(1, args.length));
            case "resolve" -> resolve(List.of(args).subList(1, args.length));
            case "check" -> check(List.of(args).subList(1, args.length));
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

    /** Resolves the declarations of the sources and reports errors; with --dump, prints them. */
    private static void resolve(List<String> args) throws IOException {
        String release = "8";
        boolean dump = false;
        List<Path> paths = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equals("--jdk-release") && i + 1 < args.size()) {
                release = args.get(++i);
            } else if (args.get(i).equals("--dump")) {
                dump = true;
            } else {
                paths.add(Path.of(args.get(i)));
            }
        }
        try {
            Resolver resolver = Frontend.load(paths, release);
            if (dump) {
                for (FileContext file : resolver.files()) {
                    System.out.println("// " + file.file().source().path());
                    System.out.print(SymbolPrinter.print(resolver, file));
                }
                return;
            }
            int count = SignatureCompleter.completeAll(resolver);
            System.out.println(resolver.files().size() + " files, " + count + " classifiers resolved");
        } catch (SyntaxError | ResolveException e) {
            System.err.println(e);
            System.exit(1);
        }
    }

    /**
     * Checks the bodies of the declarations of the sources, and prints how
     * many failed, by their first error's message; with --errors, each error.
     */
    private static void check(List<String> args) throws IOException {
        String release = "8";
        boolean printErrors = false;
        List<Path> paths = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equals("--jdk-release") && i + 1 < args.size()) {
                release = args.get(++i);
            } else if (args.get(i).equals("--errors")) {
                printErrors = true;
            } else {
                paths.add(Path.of(args.get(i)));
            }
        }
        Checker.Report report;
        try {
            report = new Checker(Frontend.load(paths, release)).checkAll();
        } catch (SyntaxError | ResolveException e) {
            System.err.println(e);
            System.exit(1);
            return;
        }
        Map<String, Integer> histogram = new HashMap<>();
        for (RuntimeException e : report.failures()) {
            if (printErrors) System.out.println(e instanceof CheckException || e instanceof ResolveException ? e : e.toString());
            histogram.merge(errorKind(e), 1, Integer::sum);
        }
        histogram.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(en -> System.out.printf("%7d  %s%n", en.getValue(), en.getKey()));
        System.out.println(report.declarations() + " declarations, " + report.failures().size() + " failed");
        System.exit(report.failures().isEmpty() ? 0 : 1);
    }

    /** An error message without the names in it, to group errors by. */
    private static String errorKind(RuntimeException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        String kind = e instanceof CheckException || e instanceof ResolveException ? "" : e.getClass().getSimpleName() + ": ";
        int colon = message.indexOf(": ");
        if (colon >= 0 && message.indexOf(' ', colon + 2) >= 0) return kind + message.substring(0, colon);
        return kind + (colon >= 0 ? message : message.replaceAll(" [^ ]+$", " ..."));
    }
}

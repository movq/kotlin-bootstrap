package kotlinbootstrap;

import java.io.IOException;
import java.lang.classfile.ClassHierarchyResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import kotlinbootstrap.ast.AstPrinter;
import kotlinbootstrap.check.CheckException;
import kotlinbootstrap.check.Checker;
import kotlinbootstrap.check.LanguageSettings;
import kotlinbootstrap.jvm.CodegenException;
import kotlinbootstrap.jvm.JvmBackend;
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
                   kbc resolve [--jdk-release N] [--dump] <file.kt, file.java, file.jar or directory>...
                   kbc check [--jdk-release N] [--errors]
                             [-Xname-based-destructuring={complete|only-syntax}] <file.kt, file.java, file.jar or directory>...
                   kbc compile -d <directory> [--module-name name] [--jdk-release N] [-cp <path>]...
                               <file.kt or directory>...""";

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) usage();
        switch (args[0]) {
            case "parse" -> parse(List.of(args).subList(1, args.length));
            case "resolve" -> resolve(List.of(args).subList(1, args.length));
            case "check" -> check(List.of(args).subList(1, args.length));
            case "compile" -> compile(List.of(args).subList(1, args.length));
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
        // A language setting applies to the paths after it, as the modules they are in are built.
        LanguageSettings settings = LanguageSettings.DEFAULT;
        List<Path> paths = new ArrayList<>();
        Map<Path, LanguageSettings> pathSettings = new HashMap<>();
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equals("--jdk-release") && i + 1 < args.size()) {
                release = args.get(++i);
            } else if (args.get(i).equals("--errors")) {
                printErrors = true;
            } else if (args.get(i).startsWith("-Xname-based-destructuring=")) {
                settings = new LanguageSettings(args.get(i).endsWith("=complete"));
            } else if (args.get(i).startsWith("-X")) {
                // Other features, like context parameters, are always on.
            } else {
                paths.add(Path.of(args.get(i)));
                pathSettings.put(Path.of(args.get(i)), settings);
            }
        }
        Map<String, LanguageSettings> fileSettings = new HashMap<>();
        for (Map.Entry<Path, LanguageSettings> e : pathSettings.entrySet()) {
            for (Path f : Frontend.sourceFiles(List.of(e.getKey()), ".kt")) fileSettings.put(f.toString(), e.getValue());
        }
        Checker.Report report;
        try {
            report = new Checker(Frontend.load(paths, release),
                    file -> fileSettings.getOrDefault(file.path(), LanguageSettings.DEFAULT)).checkAll();
        } catch (SyntaxError | ResolveException e) {
            System.err.println(e);
            System.exit(1);
            return;
        }
        Map<String, Integer> histogram = new HashMap<>();
        for (Checker.Failure f : report.failures()) {
            RuntimeException e = f.error();
            if (printErrors) {
                System.out.println(e);
                // An internal error: where it happened.
                if (!(e instanceof CheckException || e instanceof ResolveException)) {
                    System.out.println("\tin " + f.declaration());
                    StackTraceElement[] trace = e.getStackTrace();
                    for (int i = 0; i < Math.min(trace.length, 8); i++) System.out.println("\tat " + trace[i]);
                }
            }
            histogram.merge(errorKind(e), 1, Integer::sum);
        }
        histogram.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(en -> System.out.printf("%7d  %s%n", en.getValue(), en.getKey()));
        System.out.println(report.declarations() + " declarations, " + report.failures().size() + " failed");
        System.exit(report.failures().isEmpty() ? 0 : 1);
    }

    /**
     * Compiles the sources to class files in a directory, against those
     * given with -cp: sources, such as the stdlib's, and jars.
     */
    private static void compile(List<String> args) throws IOException {
        String release = "8";
        String moduleName = "main";
        Path out = null;
        List<Path> classPath = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--jdk-release" -> release = args.get(++i);
                case "--module-name" -> moduleName = args.get(++i);
                case "-d" -> out = Path.of(args.get(++i));
                case "-cp" -> classPath.add(Path.of(args.get(++i)));
                default -> sources.add(Path.of(args.get(i)));
            }
        }
        if (out == null) usage();
        Set<String> compiled = new HashSet<>();
        for (Path f : Frontend.sourceFiles(sources, ".kt")) compiled.add(f.toString());
        List<Path> paths = new ArrayList<>(classPath);
        paths.addAll(sources);
        String module = moduleName;
        try {
            Resolver resolver = Frontend.load(paths, release);
            List<FileContext> files = resolver.files().stream()
                    .filter(f -> compiled.contains(f.file().source().path())).toList();
            JvmBackend backend = new JvmBackend(resolver, new Checker(resolver), file -> module,
                    ClassHierarchyResolver.defaultResolver());
            for (Map.Entry<String, byte[]> e : backend.compile(files).entrySet()) {
                Path file = out.resolve(e.getKey() + ".class");
                Files.createDirectories(file.getParent());
                Files.write(file, e.getValue());
            }
        } catch (SyntaxError | ResolveException | CheckException | CodegenException e) {
            System.err.println(e);
            System.exit(1);
        }
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

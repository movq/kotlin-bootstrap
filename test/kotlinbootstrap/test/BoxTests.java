package kotlinbootstrap.test;

import java.io.IOException;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import kotlinbootstrap.Frontend;
import kotlinbootstrap.Stdlib;
import kotlinbootstrap.ast.KtFile;
import kotlinbootstrap.check.Checker;
import kotlinbootstrap.check.LanguageSettings;
import kotlinbootstrap.jvm.JvmBackend;
import kotlinbootstrap.resolve.FileContext;
import kotlinbootstrap.resolve.Resolver;
import kotlinbootstrap.syntax.Parser;
import kotlinbootstrap.syntax.SourceFile;

/**
 * Kotlin's codegen box tests: each file is compiled against the stdlib
 * sources, loaded with the stdlib jar, and its {@code box()} must return
 * "OK". Tests with several files or modules, or not for the JVM, are
 * skipped.
 *
 * <p>Arguments: [--verbose] [--failures file] kotlin-source-root test-file-or-directory...
 */
public final class BoxTests {
    private static final long TIMEOUT_MILLIS = 10_000;

    public static void main(String[] args) throws IOException {
        boolean verbose = false;
        Path failuresFile = null;
        List<Path> inputs = new ArrayList<>();
        Path kotlinSource = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--verbose")) verbose = true;
            else if (args[i].equals("--failures")) failuresFile = Path.of(args[++i]);
            else if (kotlinSource == null) kotlinSource = Path.of(args[i]);
            else inputs.add(Path.of(args[i]));
        }
        List<Path> tests = new ArrayList<>();
        for (Path input : inputs) {
            if (Files.isDirectory(input)) {
                try (Stream<Path> files = Files.walk(input)) {
                    files.filter(f -> f.toString().endsWith(".kt")).sorted().forEach(tests::add);
                }
            } else {
                tests.add(input);
            }
        }
        // The tests have kotlin.test too.
        List<Path> stdlibRoots = new ArrayList<>(Stdlib.sourceRoots(kotlinSource));
        Path kotlinTest = kotlinSource.resolve("libraries/kotlin.test");
        for (String root : List.of("common/src/main", "annotations-common/src/main", "jvm/src/main")) {
            stdlibRoots.add(kotlinTest.resolve(root));
        }
        List<KtFile> stdlib = Frontend.parseAll(Frontend.sourceFiles(stdlibRoots, ".kt"));

        int passed = 0;
        int skipped = 0;
        Map<String, Integer> histogram = new HashMap<>();
        StringBuilder failures = new StringBuilder();
        for (Path test : tests) {
            String text = Files.readString(test, StandardCharsets.UTF_8);
            if (skip(text)) {
                skipped++;
                continue;
            }
            String result = run(test, preprocess(text), stdlib, stdlibRoots);
            if (result.equals("OK")) {
                passed++;
                if (verbose) System.out.println("PASS " + test);
                continue;
            }
            if (verbose) System.out.println("FAIL " + test + ": " + result);
            failures.append(test).append(": ").append(result.replace('\n', ' ')).append('\n');
            histogram.merge(kind(result), 1, Integer::sum);
        }
        histogram.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(60)
                .forEach(e -> System.out.printf("%7d  %s%n", e.getValue(), e.getKey()));
        if (failuresFile != null) Files.writeString(failuresFile, failures);
        int failed = tests.size() - passed - skipped;
        System.out.println(tests.size() + " box tests, " + passed + " passed, " + failed + " failed, "
                + skipped + " skipped");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** Tests of several files or modules, needing reflection or coroutine helpers, or not run on the JVM. */
    private static boolean skip(String text) {
        for (String line : text.lines().toList()) {
            if (!line.startsWith("//")) continue;
            String d = line.substring(2).trim();
            if (d.startsWith("FILE:") || d.startsWith("MODULE:") || d.startsWith("WITH_REFLECT")
                    || d.startsWith("WITH_COROUTINES")) {
                return true;
            }
            if (d.startsWith("TARGET_BACKEND:") && !d.contains("JVM")) return true;
            if (d.startsWith("DONT_TARGET_EXACT_BACKEND:") && d.contains("JVM_IR")) return true;
            if ((d.startsWith("IGNORE_BACKEND:") || d.startsWith("IGNORE_BACKEND_K2:"))
                    && (d.contains("JVM_IR") || d.contains("ANY"))) {
                return true;
            }
            if (d.startsWith("TARGET_PLATFORM:") && !d.contains("JVM")) return true;
        }
        return false;
    }

    /** The test as for the JVM: value classes annotated, without diagnostic markup, as Kotlin's test runner. */
    private static String preprocess(String text) {
        return text.replace("OPTIONAL_JVM_INLINE_ANNOTATION", "@JvmInline")
                .replaceAll("<!>|<![A-Z_]+(\\([^!]*\\))?(, [A-Z_]+(\\([^!]*\\))?)*!>", "");
    }

    /** Compiles and runs a test: "OK", or what went wrong. */
    private static String run(Path test, String text, List<KtFile> stdlib, List<Path> stdlibRoots) {
        Map<String, byte[]> classes;
        try {
            KtFile file = Parser.parse(new SourceFile(test.toString(), text));
            List<KtFile> files = new ArrayList<>(stdlib);
            files.add(file);
            Resolver resolver = Frontend.load(List.of(), files, "8");
            FileContext context = resolver.files().stream().filter(f -> f.file() == file).findFirst().orElseThrow();
            LanguageSettings settings = new LanguageSettings(text.contains("+EnableNameBasedDestructuringShortForm"));
            JvmBackend backend = new JvmBackend(resolver, new Checker(resolver, settings), f -> "main",
                    ClassHierarchyResolver.defaultResolver());
            classes = backend.compile(List.of(context));
        } catch (RuntimeException | IOException | StackOverflowError e) {
            return "compile: " + e.toString().replace(test + ":", "") + where(e);
        }
        BoxLoader loader = new BoxLoader(classes);
        AtomicReference<String> result = new AtomicReference<>("timeout");
        Thread thread = new Thread(null, () -> result.set(invokeBox(loader, classes)), "box", 64L << 20);
        thread.setDaemon(true);
        thread.start();
        try {
            thread.join(TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    private static String invokeBox(BoxLoader loader, Map<String, byte[]> classes) {
        try {
            for (String name : new TreeMap<>(classes).keySet()) {
                if (name.contains("$")) continue;
                Class<?> c = loader.loadClass(name.replace('/', '.'));
                Method box;
                try {
                    box = c.getDeclaredMethod("box");
                } catch (NoSuchMethodException e) {
                    continue;
                }
                if (!Modifier.isStatic(box.getModifiers())) continue;
                box.setAccessible(true);
                Object value = box.invoke(null);
                return value instanceof String s ? (s.equals("OK") ? s : "result: " + s) : "result: " + value;
            }
            return "no box()";
        } catch (InvocationTargetException e) {
            return "exception: " + e.getCause();
        } catch (Throwable e) {
            return "load: " + e;
        }
    }

    /** Where an internal error happened: the top frames of its cause, after " @ ". */
    private static String where(Throwable e) {
        while (e.getCause() != null) e = e.getCause();
        if (e instanceof kotlinbootstrap.check.CheckException || e instanceof kotlinbootstrap.resolve.ResolveException) {
            return "";
        }
        StringBuilder out = new StringBuilder(" @ ");
        StackTraceElement[] trace = e.getStackTrace();
        for (int i = 0; i < Math.min(trace.length, Integer.getInteger("frames", 4)); i++) {
            out.append(i == 0 ? "" : " < ").append(trace[i].getClassName().replaceAll(".*\\.", ""))
                    .append('.').append(trace[i].getMethodName()).append(':').append(trace[i].getLineNumber());
        }
        return out.toString();
    }

    /** Groups a failure by its kind: the message up to the names in it. */
    private static String kind(String result) {
        if (result.startsWith("result: ")) return "wrong result";
        String s = result.replaceAll(" @ .*", "").replaceAll("\\s+", " ");
        s = s.replaceAll("'[^']*'", "'…'").replaceAll("\"[^\"]*\"", "\"…\"");
        return s.length() > 110 ? s.substring(0, 110) : s;
    }

    /** Defines the compiled classes, delegating the rest, the stdlib's, to the runner's loader. */
    private static final class BoxLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        BoxLoader(Map<String, byte[]> classes) {
            super(BoxTests.class.getClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name.replace('.', '/'));
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}

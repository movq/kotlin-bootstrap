package kotlinbootstrap.test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.function.Supplier;
import java.util.stream.Stream;

import kotlinbootstrap.Frontend;
import kotlinbootstrap.Stdlib;
import kotlinbootstrap.ast.Decl;
import kotlinbootstrap.check.CheckedDelegate;
import kotlinbootstrap.check.CheckedFunction;
import kotlinbootstrap.check.Checker;
import kotlinbootstrap.check.TExpr;
import kotlinbootstrap.resolve.FileContext;
import kotlinbootstrap.resolve.Resolver;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.MemberScope;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeRenderer;

/**
 * Golden tests for the checker. For each top-level function of a test
 * file, the outline of its checked body, and for each top-level delegated
 * property, of its delegate and accessors: local variables with their types,
 * and calls, property accesses, references, lambdas and SAM conversions
 * with their targets and types, nested as in the tree; or the first error.
 * The outlines for {@code foo.kt} are compared with {@code foo.txt}.
 *
 * <p>Arguments: [--update] kotlin-source-root test-directory.
 */
public final class CheckGoldenTests {
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
        Checker checker = new Checker(resolver);

        int failures = 0;
        for (Path input : inputs) {
            FileContext file = resolver.files().stream()
                    .filter(f -> f.file().source().path().equals(input.toString())).findFirst().orElseThrow();
            Path expected = Path.of(input.toString().replaceFirst("\\.kt$", ".txt"));
            String actual = outlines(resolver, checker, file);
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
        System.out.println(inputs.size() + " checker golden tests, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static String outlines(Resolver resolver, Checker checker, FileContext file) {
        StringBuilder out = new StringBuilder();
        MemberScope scope = resolver.table().packageScope(file.packageName());
        List<FunctionSymbol> functions = scope.functions().values().stream().flatMap(List::stream).toList();
        List<PropertySymbol> properties = scope.properties().values().stream().flatMap(List::stream).toList();
        for (Decl decl : file.file().declarations()) {
            for (FunctionSymbol f : functions) {
                if (f.source() != decl) continue;
                out.append("fun ").append(f.name()).append('\n');
                outline(() -> checker.checkedFunction(f), out);
            }
            for (PropertySymbol p : properties) {
                if (p.source() != decl || !(decl instanceof Decl.Property d) || d.delegate() == null) continue;
                out.append(p.isVar() ? "var " : "val ").append(p.name()).append('\n');
                outline(() -> {
                    CheckedDelegate delegate = checker.checkedDelegate(p);
                    out.append("  type: ").append(type(p.returnType())).append('\n');
                    return delegate;
                }, out);
            }
        }
        return out.toString();
    }

    /** The outline of what a check gives, or its error. */
    private static void outline(Supplier<Object> check, StringBuilder out) {
        try {
            outline(check.get(), 1, out);
        } catch (RuntimeException e) {
            // Not the candidates of an inapplicable call.
            String message = e.getMessage().replaceFirst(": \\[.*", "");
            out.append("  error: ").append(message).append('\n');
            if (System.getenv("KBC_TRACE") != null) e.printStackTrace();
        }
    }

    // ==== Outlines ====

    private static void outline(Object node, int depth, StringBuilder out) {
        switch (node) {
            case null -> {
            }
            case List<?> list -> list.forEach(n -> outline(n, depth, out));
            case CheckedFunction f -> outline(f.body(), depth, out);
            case CheckedDelegate d -> {
                outline(d.delegate(), depth, out);
                out.append("  ".repeat(depth)).append("get\n");
                outline(d.getter(), depth + 1, out);
                if (d.setter() != null) {
                    out.append("  ".repeat(depth)).append("set\n");
                    outline(d.setter(), depth + 1, out);
                }
            }
            case KType t -> {
            }
            case TExpr e -> {
                String line = line(e);
                if (line != null) out.append("  ".repeat(depth)).append(line).append('\n');
                children((Record) e, line != null ? depth + 1 : depth, out);
            }
            case Record r -> children(r, depth, out);
            default -> {
            }
        }
    }

    /** The outline of the record components that are expressions or contain them. */
    private static void children(Record r, int depth, StringBuilder out) {
        for (RecordComponent c : r.getClass().getRecordComponents()) {
            Object value;
            try {
                value = c.getAccessor().invoke(r);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            // Only functions declared here; a return or this refers to one it is inside.
            if (value instanceof TExpr || value instanceof Record || value instanceof List<?>
                    || value instanceof CheckedFunction && (r instanceof TExpr.Lambda || r instanceof TExpr.LocalFunctionDecl)) {
                outline(value, depth, out);
            }
        }
    }

    /** The line for an expression, or null if it is only shown through its children. */
    private static String line(TExpr e) {
        return switch (e) {
            case TExpr.LocalVarDecl d -> "val " + d.variable().name() + ": " + type(d.variable().type());
            case TExpr.LocalDelegatedVarDecl d -> "val " + d.variable().name() + " by: " + type(d.variable().type());
            case TExpr.Call c when c.function().owner() != null ->
                    "call " + c.function() + typeArgs(c.typeArgs()) + ": " + type(c.type());
            case TExpr.PropertyGet p -> "get " + p.property() + typeArgs(p.typeArgs()) + ": " + type(p.type());
            case TExpr.CallableReference r -> "ref " + r.target() + typeArgs(r.typeArgs()) + ": " + type(r.type())
                    + (r.adaptation() != null ? " (adapted)" : "");
            case TExpr.Lambda l -> "lambda: " + type(l.type());
            case TExpr.SamConversion s -> "sam: " + type(s.type());
            default -> null;
        };
    }

    private static String typeArgs(List<KType> types) {
        if (types.isEmpty()) return "";
        StringJoiner j = new StringJoiner(", ", "<", ">");
        for (KType t : types) j.add(type(t));
        return j.toString();
    }

    private static String type(KType t) {
        return TypeRenderer.render(t);
    }
}

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
import kotlinbootstrap.ast.Annotation;
import kotlinbootstrap.ast.Decl;
import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.check.CheckedDelegate;
import kotlinbootstrap.check.CheckedFunction;
import kotlinbootstrap.check.Checker;
import kotlinbootstrap.check.ConstantValue;
import kotlinbootstrap.check.LanguageSettings;
import kotlinbootstrap.check.TExpr;
import kotlinbootstrap.resolve.FileContext;
import kotlinbootstrap.resolve.KotlinClassSymbol;
import kotlinbootstrap.resolve.Resolver;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.MemberScope;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.symbols.ValueParamSymbol;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeRenderer;

/**
 * Golden tests for the checker. For each top-level function of a test
 * file, the outline of its checked body; for each top-level delegated
 * property, of its delegate and accessors; and for each class, of its
 * constructors, init blocks, supertype delegates and enum entries, and of
 * its nested classes': local variables with their types,
 * and calls, property accesses, references, lambdas and SAM conversions
 * with their targets and types, nested as in the tree; or the first error.
 * The outlines for {@code foo.kt} are compared with {@code foo.txt}.
 *
 * <p>Arguments: [--update] [-Xname-based-destructuring=complete] kotlin-source-root test-directory.
 */
public final class CheckGoldenTests {
    public static void main(String[] args) throws IOException {
        List<String> options = List.of(args).subList(0, args.length - 2);
        boolean update = options.contains("--update");
        boolean nameBasedDestructuring = options.contains("-Xname-based-destructuring=complete");
        Path kotlinSource = Path.of(args[args.length - 2]);
        Path dir = Path.of(args[args.length - 1]);
        List<Path> inputs;
        try (Stream<Path> files = Files.list(dir)) {
            inputs = files.filter(f -> f.toString().endsWith(".kt")).sorted().toList();
        }
        List<Path> paths = new ArrayList<>(Stdlib.sourceRoots(kotlinSource));
        paths.add(dir);
        Resolver resolver = Frontend.load(paths, "8");
        CheckGoldenTests tests = new CheckGoldenTests(new Checker(resolver, new LanguageSettings(nameBasedDestructuring)));

        int failures = 0;
        for (Path input : inputs) {
            FileContext file = resolver.files().stream()
                    .filter(f -> f.file().source().path().equals(input.toString())).findFirst().orElseThrow();
            Path expected = Path.of(input.toString().replaceFirst("\\.kt$", ".txt"));
            String actual = tests.outlines(resolver, file);
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

    private final Checker checker;

    private CheckGoldenTests(Checker checker) {
        this.checker = checker;
    }

    private String outlines(Resolver resolver, FileContext file) {
        StringBuilder out = new StringBuilder();
        MemberScope scope = resolver.table().packageScope(file.packageName());
        List<FunctionSymbol> functions = scope.functions().values().stream().flatMap(List::stream).toList();
        List<PropertySymbol> properties = scope.properties().values().stream().flatMap(List::stream).toList();
        for (Decl decl : file.file().declarations()) {
            for (FunctionSymbol f : functions) {
                if (f.source() != decl) continue;
                out.append("fun ").append(f.name()).append('\n');
                annotations(() -> checker.check(f), annotationsOf((Decl.Function) decl), 1, out);
                outline(() -> checker.checkedFunction(f), 1, out);
            }
            for (PropertySymbol p : properties) {
                if (p.source() != decl || !p.has(Modifier.CONST)) continue;
                out.append("const val ").append(p.name()).append(" = ");
                try {
                    out.append(constant(checker.constValue(p))).append('\n');
                } catch (RuntimeException e) {
                    out.append("error: ").append(e.getMessage()).append('\n');
                }
            }
            for (PropertySymbol p : properties) {
                if (p.source() != decl || !(decl instanceof Decl.Property d) || d.delegate() == null) continue;
                out.append(p.isVar() ? "var " : "val ").append(p.name()).append('\n');
                outline(() -> {
                    CheckedDelegate delegate = checker.checkedDelegate(p);
                    out.append("  type: ").append(type(p.returnType())).append('\n');
                    return delegate;
                }, 1, out);
            }
            if (decl instanceof Decl.Class d
                    && scope.classifier(d.name()) instanceof KotlinClassSymbol cls && cls.decl() == d) {
                classOutline(cls, 0, out);
            }
        }
        return out.toString();
    }

    /** The bodies of a class, its functions', then of its nested classes. */
    private void classOutline(ClassSymbol c, int depth, StringBuilder out) {
        KotlinClassSymbol cls = (KotlinClassSymbol) c;
        String indent = "  ".repeat(depth);
        out.append(indent).append("class ").append(cls.name() != null ? cls.name() : "<anonymous>").append('\n');
        List<Annotation> classAnnotations = new ArrayList<>(cls.decl().modifiers().annotations());
        if (cls.decl().primaryConstructor() != null) {
            classAnnotations.addAll(cls.decl().primaryConstructor().modifiers().annotations());
            for (Decl.Param p : cls.decl().primaryConstructor().params()) classAnnotations.addAll(p.modifiers().annotations());
        }
        annotations(() -> checker.checkClass(cls), classAnnotations, depth + 1, out);
        for (FunctionSymbol k : cls.members().constructors()) {
            out.append(indent).append("  constructor")
                    .append(k.source() instanceof Decl.SecondaryConstructor ? "\n" : " (primary)\n");
            outline(() -> checker.checkedConstructor(k), depth + 2, out);
        }
        if (cls.isObject()) {
            out.append(indent).append("  super\n");
            outline(() -> checker.checkedSuperCall(cls), depth + 2, out);
        }
        for (Decl member : cls.decl().members()) {
            if (!(member instanceof Decl.Initializer i)) continue;
            out.append(indent).append("  init\n");
            outline(() -> checker.checkedInitBlock(cls, i), depth + 2, out);
        }
        for (Decl.SuperTypeEntry e : cls.decl().superTypes()) {
            if (e.delegate() == null) continue;
            out.append(indent).append("  by\n");
            outline(() -> checker.checkedSupertypeDelegate(cls, e), depth + 2, out);
        }
        for (Decl.EnumEntry e : cls.decl().enumEntries()) {
            out.append(indent).append("  entry ").append(e.name()).append('\n');
            outline(() -> checker.checkedEnumEntry(cls, e), depth + 2, out);
        }
        for (Decl member : cls.decl().members()) {
            if (!(member instanceof Decl.Function d) || d.body() == null) continue;
            for (FunctionSymbol f : cls.members().functions(d.name())) {
                if (f.source() != d) continue;
                out.append(indent).append("  fun ").append(f.name()).append('\n');
                annotations(() -> checker.check(f), annotationsOf(d), depth + 2, out);
                outline(() -> checker.checkedFunction(f), depth + 2, out);
            }
        }
        for (Decl member : cls.decl().members()) {
            if (member instanceof Decl.Class n && cls.members().classifier(n.name()) instanceof KotlinClassSymbol nested) {
                classOutline(nested, depth + 1, out);
            }
        }
    }

    private static List<Annotation> annotationsOf(Decl.Function d) {
        List<Annotation> result = new ArrayList<>(d.modifiers().annotations());
        for (Decl.Param p : d.params()) result.addAll(p.modifiers().annotations());
        return result;
    }

    /** The checked annotations of a declaration, once checked, or the error checking it. */
    private void annotations(Runnable check, List<Annotation> annotations, int depth, StringBuilder out) {
        if (annotations.isEmpty()) return;
        try {
            check.run();
            for (Annotation a : annotations) {
                out.append("  ".repeat(depth)).append("annotation ").append(constant(checker.checkedAnnotation(a)))
                        .append('\n');
            }
        } catch (RuntimeException e) {
            out.append("  ".repeat(depth)).append("error: ").append(e.getMessage()).append('\n');
        }
    }

    private static String constant(ConstantValue value) {
        return switch (value) {
            case ConstantValue.Primitive p when p.value() instanceof String s -> '"' + s + '"';
            case ConstantValue.Primitive p when p.value() instanceof Character c -> "'" + c + "'";
            case ConstantValue.Primitive p -> p.value() + ": " + type(p.type());
            case ConstantValue.EnumEntry e -> e.entry().toString();
            case ConstantValue.ClassLiteral c -> type(c.classType()) + "::class";
            case ConstantValue.Array a -> {
                StringJoiner j = new StringJoiner(", ", "[", "]");
                for (ConstantValue e : a.elements()) j.add(constant(e));
                yield j.toString();
            }
            case ConstantValue.Annotation a -> {
                StringJoiner j = new StringJoiner(", ", "(", ")");
                List<ValueParamSymbol> params = a.constructor().signature().params();
                a.args().forEach((i, v) -> j.add(params.get(i).name() + " = " + constant(v)));
                yield type(a.type()) + j;
            }
        };
    }

    /** The outline of what a check gives, or its error. */
    private void outline(Supplier<Object> check, int depth, StringBuilder out) {
        try {
            outline(check.get(), depth, out);
        } catch (RuntimeException e) {
            if (System.getenv("KBC_TRACE") != null) e.printStackTrace();
            // Not the candidates of an inapplicable call.
            String message = e.getMessage().replaceFirst(": \\[.*", "");
            out.append("  ".repeat(depth)).append("error: ").append(message).append('\n');
        }
    }

    // ==== Outlines ====

    private void outline(Object node, int depth, StringBuilder out) {
        switch (node) {
            case null -> {
            }
            case List<?> list -> list.forEach(n -> outline(n, depth, out));
            case CheckedFunction f -> {
                for (int i = 0; i < f.defaultValues().size(); i++) {
                    if (f.defaultValues().get(i) == null) continue;
                    out.append("  ".repeat(depth)).append("default ").append(f.params().get(i).name()).append('\n');
                    outline(f.defaultValues().get(i), depth + 1, out);
                }
                outline(f.body(), depth, out);
            }
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
            case TExpr.LocalClassDecl d -> classOutline(d.cls(), depth, out);
            case TExpr.ObjectLiteral o -> classOutline(o.cls(), depth, out);
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
    private void children(Record r, int depth, StringBuilder out) {
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
                    "call " + c.function() + typeArgs(c.typeArgs()) + ": " + type(c.type())
                            + contextArgs(c.contextArgs());
            case TExpr.PropertyGet p -> "get " + p.property() + typeArgs(p.typeArgs()) + ": " + type(p.type())
                    + contextArgs(p.contextArgs());
            case TExpr.CallableReference r -> "ref " + r.target() + typeArgs(r.typeArgs()) + ": " + type(r.type())
                    + (r.adaptation() != null ? " (adapted)" : "");
            case TExpr.DelegatingConstructorCall d -> "delegate " + d.constructor() + typeArgs(d.typeArgs())
                    + (d.outerReceiver() != null ? " on " + type(d.outerReceiver().type()) : "");
            case TExpr.ArrayLiteral a -> "array: " + type(a.type());
            case TExpr.Lambda l -> "lambda: " + type(l.type());
            case TExpr.SamConversion s -> "sam: " + type(s.type());
            default -> null;
        };
    }

    /** The context arguments, which are implicit values: {@code this} or context parameters. */
    private static String contextArgs(List<TExpr> args) {
        if (args.isEmpty()) return "";
        StringJoiner j = new StringJoiner(", ", " context(", ")");
        for (TExpr a : args) {
            TExpr value = a instanceof TExpr.SmartCast s ? s.expr() : a;
            j.add((value instanceof TExpr.LocalGet g ? g.variable().name() : "this") + ": " + type(a.type()));
        }
        return j.toString();
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

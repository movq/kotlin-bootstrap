package kotlinbootstrap.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Stream;

import kotlinbootstrap.Frontend;
import kotlinbootstrap.Stdlib;
import kotlinbootstrap.ast.Decl;
import kotlinbootstrap.infer.CompletionMode;
import kotlinbootstrap.infer.ConstraintPosition;
import kotlinbootstrap.infer.ConstraintSystem;
import kotlinbootstrap.infer.ConstraintSystemCompleter;
import kotlinbootstrap.infer.LambdaWithTypeVariableAsExpectedType;
import kotlinbootstrap.infer.PostponedAtom;
import kotlinbootstrap.resolve.FileContext;
import kotlinbootstrap.resolve.Resolver;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.TypeParamSymbol;
import kotlinbootstrap.symbols.ValueParamSymbol;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.Substitution;
import kotlinbootstrap.types.TypeApproximator;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.TypeRenderer;
import kotlinbootstrap.types.TypeSystem;
import kotlinbootstrap.types.TypeVariable;

/**
 * Golden tests for the type system. Each top-level function in a test
 * file is a question about the types of its parameters, by its name:
 * {@code subtype(a: A, b: B)}, {@code equal(a: A, b: B)},
 * {@code cst(a: A, b: B, ...)}, {@code intersect(a: A, b: B, ...)},
 * {@code localCst(...)} and {@code publicCst(...)} (approximated as the type
 * of a local or public declaration), {@code capture(a: A)},
 * {@code captureApproximate(a: A)}, {@code asSupertype(a: A, b: C<*>)}.
 * {@code infer} is a call of itself: its parameters are pairs of a
 * parameter type and an argument type (captured), and one named {@code expected} is
 * the expected type of the call; the answer is the inferred type arguments
 * and return type. The answers for {@code foo.kt} are compared with
 * {@code foo.txt}.
 *
 * <p>Arguments: [--update] kotlin-source-root test-directory.
 */
public final class TypeSystemGoldenTests {
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
        TypeSystem ts = new TypeSystem(resolver.table());

        int failures = 0;
        for (Path input : inputs) {
            FileContext file = resolver.files().stream()
                    .filter(f -> f.file().source().path().equals(input.toString())).findFirst().orElseThrow();
            Path expected = Path.of(input.toString().replaceFirst("\\.kt$", ".txt"));
            String actual = answers(resolver, ts, file);
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
        System.out.println(inputs.size() + " type system golden tests, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static String answers(Resolver resolver, TypeSystem ts, FileContext file) {
        StringBuilder out = new StringBuilder();
        List<FunctionSymbol> functions = resolver.table().packageScope(file.packageName()).functions().values()
                .stream().flatMap(List::stream).toList();
        for (Decl decl : file.file().declarations()) {
            for (FunctionSymbol f : functions) {
                if (f.source() != decl) continue;
                List<KType> types = f.signature().params().stream().map(ValueParamSymbol::type).toList();
                out.append(f.name()).append(render(types)).append(" = ");
                try {
                    out.append(f.name().equals("infer") ? infer(ts, f) : answer(ts, f.name(), types));
                } catch (RuntimeException e) {
                    out.append("error: ").append(e);
                    if (System.getenv("KBC_TRACE") != null) e.printStackTrace();
                }
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static String answer(TypeSystem ts, String question, List<KType> types) {
        return switch (question) {
            case "subtype" -> String.valueOf(ts.isSubtype(types.get(0), types.get(1)));
            case "equal" -> String.valueOf(ts.equalTypes(types.get(0), types.get(1)));
            case "cst" -> TypeRenderer.render(ts.commonSuperType(types));
            case "localCst" -> TypeRenderer.render(TypeApproximator.approximateToSuperTypeOrSelf(ts,
                    ts.commonSuperType(types), TypeApproximator.Config.LOCAL_DECLARATION));
            case "publicCst" -> TypeRenderer.render(TypeApproximator.approximateToSuperTypeOrSelf(ts,
                    ts.commonSuperType(types), TypeApproximator.Config.PUBLIC_DECLARATION_SAVE_ANONYMOUS));
            case "intersect" -> TypeRenderer.render(ts.intersect(types));
            case "capture" -> {
                KType captured = ts.captureFromExpression(types.getFirst());
                yield captured == null ? "null" : TypeRenderer.render(captured);
            }
            case "captureApproximate" -> {
                KType captured = ts.captureFromExpression(types.getFirst());
                if (captured == null) yield "null";
                KType sup = TypeApproximator.approximateToSuperType(ts, captured,
                        TypeApproximator.Config.FINAL_APPROXIMATION_AFTER_RESOLUTION);
                KType sub = TypeApproximator.approximateToSubType(ts, captured,
                        TypeApproximator.Config.FINAL_APPROXIMATION_AFTER_RESOLUTION);
                yield TypeRenderer.render(sup) + " :> " + TypeRenderer.render(captured) + " :> " + TypeRenderer.render(sub);
            }
            case "asSupertype" -> {
                KType sup = ts.asSupertype(types.get(0), ((KType.ClassType) types.get(1)).symbol());
                yield sup == null ? "null" : TypeRenderer.render(sup);
            }
            default -> throw new IllegalArgumentException("Unknown question " + question);
        };
    }

    private static String infer(TypeSystem ts, FunctionSymbol f) {
        ConstraintSystem system = new ConstraintSystem(ts);
        List<TypeVariable> variables = new ArrayList<>();
        Map<TypeParamSymbol, TypeArg> map = new HashMap<>();
        for (TypeParamSymbol tp : f.typeParams()) {
            TypeVariable v = new TypeVariable(tp.name(), tp);
            variables.add(v);
            map.put(tp, TypeArg.of(v.type()));
            system.registerVariable(v);
        }
        Substitution toVariables = new Substitution(map);
        for (TypeVariable v : variables) {
            for (KType bound : v.typeParam().bounds()) {
                system.addSubtypeConstraint(v.type(), toVariables.apply(bound),
                        ConstraintPosition.of(ConstraintPosition.Kind.DECLARED_UPPER_BOUND));
            }
        }
        KType returnType = toVariables.apply(f.returnType());
        List<ValueParamSymbol> params = f.signature().params();
        for (int i = 0; i < params.size(); i++) {
            if (params.get(i).name().equals("expected")) {
                system.addSubtypeConstraint(returnType, params.get(i).type(),
                        ConstraintPosition.of(ConstraintPosition.Kind.EXPECTED_TYPE));
            } else {
                // As kotlinc, argument types are captured.
                KType argument = params.get(i + 1).type();
                KType captured = ts.captureFromExpression(argument);
                system.addSubtypeConstraint(captured != null ? captured : argument, toVariables.apply(params.get(i).type()),
                        ConstraintPosition.of(ConstraintPosition.Kind.ARGUMENT));
                i++;
            }
        }
        system.startCompletion();
        ConstraintSystemCompleter.complete(system, CompletionMode.FULL, returnType, new ConstraintSystemCompleter.Context() {
            @Override
            public List<TypeVariable> orderedTypeVariables() {
                return variables;
            }

            @Override
            public List<PostponedAtom> postponedArguments() {
                return List.of();
            }

            @Override
            public void analyze(PostponedAtom argument) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void transformToResolvedLambda(LambdaWithTypeVariableAsExpectedType argument, KType expectedType) {
                throw new UnsupportedOperationException();
            }
        });
        StringJoiner j = new StringJoiner(", ");
        for (TypeVariable v : variables) j.add(v + " := " + TypeRenderer.render(system.fixedTypeVariables().get(v)));
        String result = j + "; " + TypeRenderer.render(system.buildCurrentSubstitutor().apply(returnType));
        if (!system.errors().isEmpty()) result += "; errors: " + system.errors();
        return result;
    }

    private static String render(List<KType> types) {
        StringJoiner j = new StringJoiner(", ", "(", ")");
        for (KType t : types) j.add(TypeRenderer.render(t));
        return j.toString();
    }
}

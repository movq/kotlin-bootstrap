package kotlinbootstrap.resolve;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import kotlinbootstrap.ast.Annotation;
import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.ast.NamedType;
import kotlinbootstrap.ast.TypeRef;
import kotlinbootstrap.symbols.ClassId;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.ClassifierSymbol;
import kotlinbootstrap.symbols.TypeAliasSymbol;
import kotlinbootstrap.symbols.TypeParamSymbol;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.Substitution;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.Variance;

/** Resolves types as written ({@link TypeRef}) to {@link KType}s. */
public final class TypeResolver {
    private final Resolver resolver;

    TypeResolver(Resolver resolver) {
        this.resolver = resolver;
    }

    public KType resolve(TypeRef ref, Scope scope, FileContext file) {
        return switch (ref) {
            case TypeRef.Simple s -> resolveSimple(s, scope, file);
            case TypeRef.Nullable n -> resolve(n.inner(), scope, file).withNullable(true);
            case TypeRef.Function f -> functionType(f, false, scope, file);
            case TypeRef.Modified m -> {
                boolean extension = false;
                for (Annotation a : m.modifiers().annotations()) {
                    if (a.type().segments().getLast().name().equals("ExtensionFunctionType")) extension = true;
                }
                if (m.modifiers().has(Modifier.SUSPEND)) {
                    if (!(m.inner() instanceof TypeRef.Function f)) throw error(file, ref, "Only function types can be suspend");
                    yield functionType(f, true, scope, file);
                }
                KType inner = resolve(m.inner(), scope, file);
                if (extension && inner instanceof KType.ClassType c) {
                    yield new KType.ClassType(c.symbol(), c.args(), c.nullable(), true, c.contextParams());
                }
                yield inner;
            }
            case TypeRef.Intersection i -> {
                KType left = resolve(i.left(), scope, file);
                KType right = resolve(i.right(), scope, file);
                if (!(left instanceof KType.TypeParamType tp) || !right.equals(any())) {
                    throw error(file, ref, "Only definitely non-null types 'T & Any' are supported");
                }
                yield new KType.DefinitelyNotNull(tp.withNullable(false));
            }
            case TypeRef.Dynamic d -> throw error(file, ref, "dynamic is not supported on the JVM");
        };
    }

    private KType resolveSimple(TypeRef.Simple ref, Scope scope, FileContext file) {
        List<TypeRef.Segment> segments = ref.segments();
        // Find the classifier named by the first segments: a name in scope, or a qualified name.
        ClassifierSymbol c = scope.findClassifier(segments.getFirst().name());
        int used = 1;
        if (c == null) {
            List<String> names = segments.stream().map(TypeRef.Segment::name).toList();
            for (int k = names.size() - 1; k >= 1 && c == null; k--) {
                String pkg = String.join(".", names.subList(0, k));
                if (resolver.table().packageExists(pkg)) {
                    c = resolver.table().findClassifier(new ClassId(pkg, names.get(k)));
                    used = k + 1;
                }
            }
            if (c == null) throw error(file, ref, "Unresolved type " + String.join(".", names));
        }
        // Each remaining segment names a nested class. Remember which segment named each class.
        List<ClassifierSymbol> chain = new ArrayList<>();
        chain.add(c);
        for (TypeRef.Segment s : segments.subList(used, segments.size())) {
            c = resolver.nestedClassifier(c, s.name());
            if (c == null) throw error(file, ref, "Unresolved nested type " + s.name());
            chain.add(c);
        }
        List<TypeRef.TypeArg> written = segments.getLast().args();

        return switch (c) {
            case TypeParamSymbol tp -> {
                if (!written.isEmpty()) throw error(file, ref, "Type parameter with type arguments");
                yield new KType.TypeParamType(tp, false);
            }
            case TypeAliasSymbol alias -> {
                List<TypeArg> args = typeArgs(written, scope, file);
                if (args.size() != alias.typeParams().size()) throw error(file, ref, "Wrong number of type arguments");
                yield Substitution.of(alias.typeParams(), args).apply(alias.expansion());
            }
            case ClassSymbol cls -> {
                if (written.size() != cls.typeParams().size()) {
                    throw error(file, ref, "Wrong number of type arguments for " + cls);
                }
                List<TypeArg> args = new ArrayList<>(typeArgs(written, scope, file));
                // Arguments for outer classes of inner classes: written on the qualifier, or implicit.
                // chain.get(j) was named by segments.get(used - 1 + j).
                int j = chain.size() - 1;
                for (ClassSymbol inner = cls; inner.isInner(); inner = inner.outer()) {
                    j--;
                    ClassSymbol outer = inner.outer();
                    List<TypeRef.TypeArg> outerArgs = j >= 0 && chain.get(j) == outer
                            ? segments.get(used - 1 + j).args() : List.of();
                    if (!outerArgs.isEmpty()) {
                        args.addAll(typeArgs(outerArgs, scope, file));
                    } else {
                        for (TypeParamSymbol tp : outer.typeParams()) {
                            args.add(TypeArg.of(new KType.TypeParamType(tp, false)));
                        }
                    }
                }
                yield new KType.ClassType(cls, args, false);
            }
        };
    }

    private List<TypeArg> typeArgs(List<TypeRef.TypeArg> written, Scope scope, FileContext file) {
        List<TypeArg> result = new ArrayList<>(written.size());
        for (TypeRef.TypeArg a : written) {
            if (a.type() == null) {
                result.add(TypeArg.STAR);
                continue;
            }
            Variance v = switch (a.variance()) {
                case INVARIANT -> Variance.INVARIANT;
                case IN -> Variance.IN;
                case OUT -> Variance.OUT;
            };
            result.add(new TypeArg(v, resolve(a.type(), scope, file)));
        }
        return result;
    }

    private KType functionType(TypeRef.Function f, boolean suspend, Scope scope, FileContext file) {
        List<TypeArg> args = new ArrayList<>();
        for (NamedType t : f.contextParams()) args.add(TypeArg.of(resolve(t.type(), scope, file)));
        if (f.receiver() != null) args.add(TypeArg.of(resolve(f.receiver(), scope, file)));
        for (NamedType t : f.params()) args.add(TypeArg.of(resolve(t.type(), scope, file)));
        args.add(TypeArg.of(resolve(f.returnType(), scope, file)));
        ClassSymbol cls = functionClass(args.size() - 1, suspend);
        return new KType.ClassType(cls, args, false, f.receiver() != null, f.contextParams().size());
    }

    // ==== Well-known types ====

    public ClassSymbol functionClass(int arity, boolean suspend) {
        return resolver.table().requireClass((suspend ? "kotlin.coroutines.SuspendFunction" : "kotlin.Function") + arity);
    }

    public KType.ClassType classType(String fqName, TypeArg... args) {
        return new KType.ClassType(resolver.table().requireClass(fqName), List.of(args), false);
    }

    public KType.ClassType any() {
        return classType("kotlin.Any");
    }

    public KType nullableAny() {
        return any().withNullable(true);
    }

    public KType.ClassType unit() {
        return classType("kotlin.Unit");
    }

    /** {@code kotlin.Enum<E>} for enum class E. */
    KType enumSupertype(ClassSymbol cls) {
        return classType("kotlin.Enum", TypeArg.of(defaultType(cls)));
    }

    /**
     * The type of a vararg parameter seen from inside the function: a
     * primitive or unsigned array for a non-null primitive or unsigned
     * element type, {@code Array<out T>} otherwise.
     */
    public KType.ClassType varargArrayType(KType element) {
        if (element instanceof KType.ClassType c && !c.nullable() && c.args().isEmpty()
                && c.symbol().id().packageName().equals("kotlin")
                && ARRAY_ELEMENTS.contains(c.symbol().name())) {
            return classType("kotlin." + c.symbol().name() + "Array");
        }
        return classType("kotlin.Array", new TypeArg(Variance.OUT, element));
    }

    private static final Set<String> ARRAY_ELEMENTS = Set.of("Boolean", "Byte", "Short", "Int", "Long", "Char",
            "Float", "Double", "UByte", "UShort", "UInt", "ULong");

    /** The type of {@code this} in a class: the class applied to its own type parameters. */
    public KType.ClassType defaultType(ClassSymbol cls) {
        List<TypeArg> args = new ArrayList<>();
        for (ClassSymbol c = cls; c != null; c = c.isInner() ? c.outer() : null) {
            for (TypeParamSymbol tp : c.typeParams()) args.add(TypeArg.of(new KType.TypeParamType(tp, false)));
        }
        return new KType.ClassType(cls, args, false);
    }

    private static ResolveException error(FileContext file, TypeRef ref, String message) {
        return new ResolveException(file.file().source(), ref.pos(), message);
    }
}

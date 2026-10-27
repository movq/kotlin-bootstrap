package kotlinbootstrap.types;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.SymbolTable;

/**
 * Function types, as FIR's FunctionalTypeUtils: {@code kotlin.FunctionN},
 * {@code SuspendFunctionN} and their reflective {@code KFunctionN} and
 * {@code KSuspendFunctionN}. Their arguments are the context parameters,
 * the receiver, the value parameters and the return type, in that order.
 */
public final class FunctionTypes {
    public enum Kind {
        FUNCTION("kotlin", "Function"),
        SUSPEND_FUNCTION("kotlin.coroutines", "SuspendFunction"),
        K_FUNCTION("kotlin.reflect", "KFunction"),
        K_SUSPEND_FUNCTION("kotlin.reflect", "KSuspendFunction");

        final String packageName;
        final String prefix;

        Kind(String packageName, String prefix) {
            this.packageName = packageName;
            this.prefix = prefix;
        }

        public boolean isReflect() {
            return this == K_FUNCTION || this == K_SUSPEND_FUNCTION;
        }

        public boolean isSuspend() {
            return this == SUSPEND_FUNCTION || this == K_SUSPEND_FUNCTION;
        }

        public Kind nonReflect() {
            return switch (this) {
                case K_FUNCTION -> FUNCTION;
                case K_SUSPEND_FUNCTION -> SUSPEND_FUNCTION;
                default -> this;
            };
        }

        public Kind reflect() {
            return switch (this) {
                case FUNCTION -> K_FUNCTION;
                case SUSPEND_FUNCTION -> K_SUSPEND_FUNCTION;
                default -> this;
            };
        }
    }

    private FunctionTypes() {
    }

    /** The kind of a function class, or null if it is not one. */
    public static Kind kind(ClassSymbol cls) {
        String pkg = cls.id().packageName();
        String name = cls.id().relativeName();
        for (Kind k : Kind.values()) {
            if (!pkg.equals(k.packageName) || !name.startsWith(k.prefix)) continue;
            String digits = name.substring(k.prefix.length());
            if (!digits.isEmpty() && digits.chars().allMatch(Character::isDigit)) return k;
        }
        return null;
    }

    /** The kind of a function type (or flexible one), or null if it is not one. */
    public static Kind kind(KType t) {
        return Types.lowerBound(t) instanceof KType.ClassType c ? kind(c.symbol()) : null;
    }

    /** Any function type, reflective or not, suspend or not. */
    public static boolean isFunctionType(KType t) {
        return kind(t) != null;
    }

    public static ClassSymbol functionClass(SymbolTable table, Kind kind, int arity) {
        return table.requireClass(kind.packageName + "." + kind.prefix + arity);
    }

    /**
     * The function type for the parts: {@code context(C) R.(P) -> T}.
     *
     * @param receiver nullable
     */
    public static KType.ClassType create(SymbolTable table, Kind kind, List<KType> contextParams, KType receiver,
                                         List<KType> params, KType returnType) {
        List<TypeArg> args = new ArrayList<>();
        for (KType t : contextParams) args.add(TypeArg.of(t));
        if (receiver != null) args.add(TypeArg.of(receiver));
        for (KType t : params) args.add(TypeArg.of(t));
        args.add(TypeArg.of(returnType));
        return new KType.ClassType(functionClass(table, kind, args.size() - 1), args, false, receiver != null,
                contextParams.size());
    }

    // ==== Parts ====

    /** The receiver of an extension function type, or null. */
    public static KType receiverType(TypeSystem ts, KType.ClassType t) {
        if (kind(t.symbol()) == null || !t.extensionFunction()) return null;
        return typeOrDefault(t.args().get(t.contextParams()), ts.nothing());
    }

    public static KType returnType(TypeSystem ts, KType.ClassType t) {
        return typeOrDefault(t.args().getLast(), ts.nullableAny());
    }

    /** All arguments but the return type: context parameters, receiver and value parameters. */
    public static List<KType> valueParameterTypesIncludingReceiver(TypeSystem ts, KType.ClassType t) {
        List<KType> result = new ArrayList<>();
        for (TypeArg a : t.args().subList(0, t.args().size() - 1)) result.add(typeOrDefault(a, ts.nothing()));
        return result;
    }

    private static KType typeOrDefault(TypeArg a, KType defaultType) {
        return a.isStar() ? defaultType : a.type();
    }

    // ==== Subtypes ====

    /** A function type, or a type with a function type among its supertypes. */
    public static boolean isFunctionTypeOrSubtype(TypeSystem ts, KType t) {
        return functionTypeFromSupertypes(ts, t) != null;
    }

    /**
     * The type itself if a function type, else its function supertype,
     * as kotlinc's getFunctionTypeFromSupertypes; null if none.
     */
    public static KType functionTypeFromSupertypes(TypeSystem ts, KType t) {
        if (isFunctionType(t)) return t;
        KType lower = Types.lowerBound(t);
        return switch (lower) {
            case KType.ClassType c -> {
                ClassSymbol functionClass = functionSuperclass(ts, c.symbol());
                yield functionClass == null ? null : ts.asSupertype(c, functionClass);
            }
            case KType.DefinitelyNotNull d -> functionTypeFromSupertypes(ts, d.inner());
            default -> {
                for (KType s : ts.supertypes(lower)) {
                    KType found = functionTypeFromSupertypes(ts, s);
                    if (found != null) yield found;
                }
                yield null;
            }
        };
    }

    private static ClassSymbol functionSuperclass(TypeSystem ts, ClassSymbol cls) {
        ArrayDeque<ClassSymbol> queue = new ArrayDeque<>(List.of(cls));
        Set<ClassSymbol> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            ClassSymbol c = queue.poll();
            if (!seen.add(c)) continue;
            if (kind(c) != null) return c;
            for (KType s : c.supertypes()) {
                if (Types.lowerBound(s) instanceof KType.ClassType sc) queue.add(sc.symbol());
            }
        }
        return null;
    }
}

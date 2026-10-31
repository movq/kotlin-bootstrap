package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javax.lang.model.element.VariableElement;

import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;

import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.symbols.ClassKind;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.PackageSymbol;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.Types;

/**
 * Evaluates constant expressions, as FIR's FirExpressionEvaluator without
 * IntrinsicConstEvaluation (which is off by default): literals, string
 * templates, the operators and conversions of primitives and strings,
 * const vals and Java constants, enum entries, class literals, arrays and
 * annotations.
 */
final class ConstantEvaluator {
    /** Why an expression is not constant, as FIR's FirEvaluatorResult, with kotlinc's messages for annotation arguments. */
    enum Failure {
        NOT_CONST("An annotation argument must be a compile-time constant"),
        ENUM_NOT_CONST("An enum annotation argument must be an enum constant"),
        NOT_KCLASS_LITERAL("An annotation argument must be a class literal (T::class)"),
        KCLASS_OF_TYPE_PARAMETER("A type parameter cannot be used as a class literal in an annotation argument"),
        NON_CONST_VAL("Only 'const val' can be used in constant expressions"),
        RECURSION("A const val's initializer refers to itself");

        final String message;

        Failure(String message) {
            this.message = message;
        }
    }

    static final class NotConstant extends RuntimeException {
        final Failure failure;
        final TExpr expr;

        NotConstant(Failure failure, TExpr expr) {
            super(failure.message, null, false, false);
            this.failure = failure;
            this.expr = expr;
        }
    }

    /** The compile-time types, as CompileTimeType. */
    private enum Kind { BOOLEAN, CHAR, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, STRING, UBYTE, USHORT, UINT, ULONG }

    private static final Set<String> COMPILE_TIME_FUNCTIONS = Set.of("plus", "minus", "times", "div", "rem",
            "unaryPlus", "unaryMinus", "not", "inv", "and", "or", "xor", "shl", "shr", "ushr", "compareTo",
            "floorDiv", "mod", "code", "toString", "toByte", "toShort", "toInt", "toLong", "toFloat", "toDouble",
            "toChar");

    private static final Set<String> ARRAY_OF = Set.of("arrayOf", "emptyArray", "booleanArrayOf", "byteArrayOf",
            "shortArrayOf", "intArrayOf", "longArrayOf", "charArrayOf", "floatArrayOf", "doubleArrayOf",
            "ubyteArrayOf", "ushortArrayOf", "uintArrayOf", "ulongArrayOf");

    /** The checked initializer of a const val; null if it has none. */
    private final Function<PropertySymbol, TExpr> constInitializer;
    /** The const vals being evaluated, against cycles, as FIR's visitedCallables. */
    private final Set<PropertySymbol> visiting = new HashSet<>();

    ConstantEvaluator(Function<PropertySymbol, TExpr> constInitializer) {
        this.constInitializer = constInitializer;
    }

    /** The value of a constant expression; throws {@link NotConstant} otherwise. */
    ConstantValue evaluate(TExpr e) {
        return switch (e) {
            case TExpr.Const c -> new ConstantValue.Primitive(c.type(), c.value() == null ? null
                    : convert(c.value(), kind(c.type()), e));
            case TExpr.StringTemplate s -> {
                StringBuilder b = new StringBuilder();
                for (TExpr part : s.parts()) {
                    Object value = primitive(part, true);
                    b.append(string(value, kind(part.type())));
                }
                yield new ConstantValue.Primitive(s.type(), b.toString());
            }
            case TExpr.PropertyGet g -> property(g);
            case TExpr.Call c -> call(c);
            case TExpr.Vararg v -> {
                List<ConstantValue> elements = new ArrayList<>();
                for (TExpr.VarargElement el : v.elements()) {
                    ConstantValue value = evaluate(el.value());
                    if (el.spread() && value instanceof ConstantValue.Array a) {
                        elements.addAll(a.elements());
                    } else {
                        elements.add(value);
                    }
                }
                yield new ConstantValue.Array(v.type(), elements);
            }
            case TExpr.ArrayLiteral a -> {
                List<ConstantValue> elements = new ArrayList<>();
                for (TExpr el : a.elements()) elements.add(evaluate(el));
                yield new ConstantValue.Array(a.type(), elements);
            }
            case TExpr.ClassReference r -> classLiteral(r);
            case TExpr.Comparison c -> {
                int result = (Integer) primitive(c.compareTo(), false);
                yield bool(c.type(), switch (c.op()) {
                    case LT -> result < 0;
                    case GT -> result > 0;
                    case LE -> result <= 0;
                    case GE -> result >= 0;
                });
            }
            case TExpr.Equality q -> {
                if (q.op() == TExpr.EqualityOp.IDENTITY_EQ || q.op() == TExpr.EqualityOp.IDENTITY_NE) {
                    throw new NotConstant(Failure.NOT_CONST, e);
                }
                Object left = primitive(q.left(), false);
                Object right = primitive(q.right(), false);
                if (isUnsigned(q.left().type()) || isUnsigned(q.right().type())) throw new NotConstant(Failure.NOT_CONST, e);
                yield bool(q.type(), left.equals(right) == (q.op() == TExpr.EqualityOp.EQ));
            }
            case TExpr.Logical l -> {
                boolean left = (Boolean) primitive(l.left(), false);
                boolean right = (Boolean) primitive(l.right(), false);
                yield bool(l.type(), l.and() ? left && right : left || right);
            }
            case TExpr.Cast c when !c.safe() -> evaluate(c.expr());
            case TExpr.SmartCast s -> evaluate(s.expr());
            default -> throw new NotConstant(Failure.NOT_CONST, e);
        };
    }

    /** The value of an operand of an operator, which must have a compile-time type, as FIR's hasAllowedCompileTimeType. */
    private Object primitive(TExpr e, boolean nullAllowed) {
        if (nullAllowed && e instanceof TExpr.Const c && c.value() == null) return null;
        if (kind(e.type()) == null || Types.lowerBound(e.type()).nullable()) throw new NotConstant(Failure.NOT_CONST, e);
        if (!(evaluate(e) instanceof ConstantValue.Primitive p) || p.value() == null) {
            throw new NotConstant(Failure.NOT_CONST, e);
        }
        return p.value();
    }

    private static ConstantValue bool(KType type, boolean value) {
        return new ConstantValue.Primitive(type, value);
    }

    // ==== Properties ====

    private ConstantValue property(TExpr.PropertyGet g) {
        PropertySymbol p = g.property();
        if (p.isEnumEntry()) return new ConstantValue.EnumEntry(g.type(), p);
        if (Types.lowerBound(p.returnType()) instanceof KType.ClassType t && t.symbol().kind() == ClassKind.ENUM_CLASS) {
            throw new NotConstant(Failure.ENUM_NOT_CONST, g);
        }
        if (isKClass(g.type())) throw new NotConstant(Failure.NOT_KCLASS_LITERAL, g);
        TExpr receiver = g.dispatchReceiver() != null ? g.dispatchReceiver() : g.extensionReceiver();
        // As isCompileTimeBuiltinProperty, by the receiver's type.
        Kind receiverKind = receiver == null ? null : kind(receiver.type());
        if (receiverKind == Kind.STRING && p.name().equals("length") || receiverKind == Kind.CHAR && p.name().equals("code")) {
            Object value = primitive(receiver, false);
            return new ConstantValue.Primitive(g.type(), value instanceof String s ? s.length() : (int) (Character) value);
        }
        if (p.has(Modifier.CONST)) {
            TExpr initializer = constInitializer.apply(p);
            if (initializer == null) throw new NotConstant(Failure.NOT_CONST, g);
            if (!visiting.add(p)) throw new NotConstant(Failure.RECURSION, g);
            try {
                ConstantValue value = evaluate(initializer);
                return value instanceof ConstantValue.Primitive v
                        ? new ConstantValue.Primitive(g.type(), v.value() == null ? null : convert(v.value(), kind(g.type()), g))
                        : value;
            } finally {
                visiting.remove(p);
            }
        }
        if (p.isJavaField() && p.isStatic() && !p.isVar()) {
            Object value = javaConstant(p.source());
            if (value != null) return new ConstantValue.Primitive(g.type(), convert(value, kind(g.type()), g));
        }
        throw new NotConstant(p.isVar() || p.isJavaField() ? Failure.NOT_CONST : Failure.NON_CONST_VAL, g);
    }

    /** The value of a Java constant field: of the JDK's, or a literal initializer in Java source; null if none. */
    private static Object javaConstant(Object source) {
        return switch (source) {
            case VariableElement v -> v.getConstantValue();
            case VariableTree v -> javaLiteral(v.getInitializer());
            case null, default -> null;
        };
    }

    private static Object javaLiteral(Tree t) {
        return switch (t) {
            case LiteralTree l -> l.getValue();
            case UnaryTree u when u.getKind() == Tree.Kind.UNARY_MINUS -> switch (javaLiteral(u.getExpression())) {
                case Integer i -> -i;
                case Long l -> -l;
                case Float f -> -f;
                case Double d -> -d;
                case null, default -> null;
            };
            case null, default -> null;
        };
    }

    // ==== Calls ====

    private ConstantValue call(TExpr.Call c) {
        FunctionSymbol f = c.function();
        if (isKClass(c.type())) throw new NotConstant(Failure.NOT_KCLASS_LITERAL, c);
        if (f.isConstructor()) {
            ClassSymbol cls = (ClassSymbol) f.owner();
            if (isUnsigned(c.type()) && c.args().size() == 1) {
                Object value = primitive(c.args().getFirst().value(), false);
                return new ConstantValue.Primitive(c.type(), convert(value, kind(c.type()), c));
            }
            if (cls.kind() != ClassKind.ANNOTATION_CLASS) throw new NotConstant(Failure.NOT_CONST, c);
            return annotation(c.type(), f, c.args());
        }
        if (f.owner() instanceof PackageSymbol pkg && pkg.name().equals("kotlin") && ARRAY_OF.contains(f.name())) {
            List<ConstantValue> elements = new ArrayList<>();
            for (TExpr.Argument a : c.args()) {
                if (evaluate(a.value()) instanceof ConstantValue.Array array) elements.addAll(array.elements());
            }
            return new ConstantValue.Array(c.type(), elements);
        }
        if (!isCompileTimeBuiltinCall(c)) throw new NotConstant(Failure.NOT_CONST, c);
        List<TExpr> operands = new ArrayList<>();
        if (c.dispatchReceiver() != null) operands.add(c.dispatchReceiver());
        if (c.extensionReceiver() != null) operands.add(c.extensionReceiver());
        for (TExpr.Argument a : c.args()) operands.add(a.value());
        List<Object> values = new ArrayList<>();
        for (TExpr o : operands) values.add(primitive(o, false));
        Object result = switch (values.size()) {
            case 1 -> unary(f.name(), kind(operands.getFirst().type()), values.getFirst());
            case 2 -> binary(f.name(), kind(operands.getFirst().type()), values.getFirst(),
                    kind(operands.get(1).type()), values.get(1));
            default -> null;
        };
        if (result == null) throw new NotConstant(Failure.NOT_CONST, c);
        return new ConstantValue.Primitive(c.type(), convert(result, kind(c.type()), c));
    }

    /** An annotation with its arguments evaluated. */
    ConstantValue.Annotation annotation(KType type, FunctionSymbol constructor, List<TExpr.Argument> args) {
        Map<Integer, ConstantValue> values = new LinkedHashMap<>();
        for (TExpr.Argument a : args) values.put(a.param(), evaluate(a.value()));
        return new ConstantValue.Annotation(type, constructor, values);
    }

    /** As FIR's isCompileTimeBuiltinCall: an operator or conversion of a builtin from the kotlin package, but unsigned ones. */
    private static boolean isCompileTimeBuiltinCall(TExpr.Call c) {
        FunctionSymbol f = c.function();
        String pkg = switch (f.owner()) {
            case ClassSymbol cls -> cls.id().packageName();
            case PackageSymbol p -> p.name();
            case null, default -> null;
        };
        if (!"kotlin".equals(pkg)) return false;
        if (c.dispatchReceiver() != null && isUnsigned(c.dispatchReceiver().type())) return false;
        if (COMPILE_TIME_FUNCTIONS.contains(f.name())) return true;
        return f.name().equals("get") && "kotlin.String".equals(ownerName(f));
    }

    // ==== Class literals ====

    private static ConstantValue classLiteral(TExpr.ClassReference r) {
        KType t = r.classType();
        while (Types.lowerBound(t) instanceof KType.ClassType c && c.symbol().id().fqName().equals("kotlin.Array")
                && !c.args().getFirst().isStar()) {
            t = c.args().getFirst().type();
        }
        if (Types.lowerBound(t) instanceof KType.TypeParamType) throw new NotConstant(Failure.KCLASS_OF_TYPE_PARAMETER, r);
        if (r.receiver() != null) throw new NotConstant(Failure.NOT_KCLASS_LITERAL, r);
        return new ConstantValue.ClassLiteral(r.type(), r.classType());
    }

    // ==== Operations, as OperationsMapGenerated ====

    private static Object unary(String name, Kind kind, Object v) {
        if (kind == null) return null;
        if (name.equals("toString")) return string(v, kind);
        switch (kind) {
            case BOOLEAN -> {
                return name.equals("not") ? !(Boolean) v : null;
            }
            case STRING -> {
                return null;
            }
            case CHAR -> {
                char ch = (Character) v;
                return name.equals("code") ? (int) ch : conversion(name, ch);
            }
            default -> {
            }
        }
        Number n = (Number) v;
        return switch (name) {
            case "unaryMinus" -> switch (kind) {
                case DOUBLE -> -n.doubleValue();
                case FLOAT -> -n.floatValue();
                case LONG -> -n.longValue();
                default -> -n.intValue();
            };
            case "unaryPlus" -> switch (kind) {
                case DOUBLE, FLOAT, LONG -> n;
                default -> n.intValue();
            };
            case "inv" -> switch (kind) {
                case LONG -> ~n.longValue();
                case INT -> ~n.intValue();
                default -> null;
            };
            default -> conversion(name, kind == Kind.DOUBLE || kind == Kind.FLOAT ? n.doubleValue() : n.longValue());
        };
    }

    /** toInt and the like: the value converted to the call's type, which {@link #convert} then does. */
    private static Object conversion(String name, Object v) {
        return switch (name) {
            case "toByte", "toShort", "toInt", "toLong", "toFloat", "toDouble" ->
                    v instanceof Character c ? (long) c : v;
            case "toChar" -> switch (v) {
                case Character c -> c;
                case Double d -> (char) d.intValue();
                default -> (char) ((Long) v).intValue();
            };
            default -> null;
        };
    }

    private static Object binary(String name, Kind left, Object a, Kind right, Object b) {
        if (left == null || right == null) return null;
        if (left == Kind.STRING) {
            return switch (name) {
                case "plus" -> a + string(b, right);
                case "compareTo" -> right == Kind.STRING ? Integer.signum(((String) a).compareTo((String) b)) : null;
                case "get" -> right == Kind.INT ? ((String) a).charAt((Integer) b) : null;
                case "equals" -> a.equals(b);
                default -> null;
            };
        }
        if (left == Kind.BOOLEAN) {
            if (right != Kind.BOOLEAN) return null;
            boolean x = (Boolean) a;
            boolean y = (Boolean) b;
            return switch (name) {
                case "and" -> x & y;
                case "or" -> x | y;
                case "xor" -> x ^ y;
                case "compareTo" -> Boolean.compare(x, y);
                case "equals" -> x == y;
                default -> null;
            };
        }
        if (left == Kind.CHAR) {
            char x = (Character) a;
            return switch (name) {
                case "plus" -> right == Kind.INT ? (char) (x + (Integer) b) : null;
                case "minus" -> right == Kind.CHAR ? x - (Character) b : right == Kind.INT ? (char) (x - (Integer) b) : null;
                case "compareTo" -> right == Kind.CHAR ? Character.compare(x, (Character) b) : null;
                case "equals" -> a.equals(b);
                default -> null;
            };
        }
        if (right == Kind.CHAR || right == Kind.STRING || right == Kind.BOOLEAN) return null;
        Number x = (Number) a;
        Number y = (Number) b;
        boolean isDouble = left == Kind.DOUBLE || right == Kind.DOUBLE;
        boolean isFloat = !isDouble && (left == Kind.FLOAT || right == Kind.FLOAT);
        boolean isLong = !isDouble && !isFloat && (left == Kind.LONG || right == Kind.LONG);
        switch (name) {
            case "shl", "shr", "ushr" -> {
                if (right != Kind.INT) return null;
                int s = y.intValue();
                if (left == Kind.LONG) {
                    long v = x.longValue();
                    return name.equals("shl") ? v << s : name.equals("shr") ? v >> s : v >>> s;
                }
                if (left != Kind.INT) return null;
                int v = x.intValue();
                return name.equals("shl") ? v << s : name.equals("shr") ? v >> s : v >>> s;
            }
            case "and", "or", "xor" -> {
                if (left != right || left != Kind.INT && left != Kind.LONG) return null;
                long v = x.longValue();
                long w = y.longValue();
                long r = name.equals("and") ? v & w : name.equals("or") ? v | w : v ^ w;
                return left == Kind.INT ? (Object) (int) r : (Object) r;
            }
            case "compareTo" -> {
                if (isDouble || isFloat) return Double.compare(x.doubleValue(), y.doubleValue());
                return Long.compare(x.longValue(), y.longValue());
            }
            case "equals" -> {
                return x.equals(y);
            }
            default -> {
            }
        }
        if (isDouble || isFloat) {
            double v = isFloat ? x.floatValue() : x.doubleValue();
            double w = isFloat ? y.floatValue() : y.doubleValue();
            double r = switch (name) {
                case "plus" -> v + w;
                case "minus" -> v - w;
                case "times" -> v * w;
                case "div" -> v / w;
                case "rem" -> v % w;
                case "mod" -> {
                    double m = v % w;
                    yield m != 0 && Math.signum(m) != Math.signum(w) ? m + w : m;
                }
                default -> Double.NaN;
            };
            if (!List.of("plus", "minus", "times", "div", "rem", "mod").contains(name)) return null;
            return isFloat ? (Object) (float) r : (Object) r;
        }
        long v = x.longValue();
        long w = y.longValue();
        if (w == 0 && List.of("div", "rem", "floorDiv", "mod").contains(name)) return null;
        if (isLong) {
            return switch (name) {
                case "plus" -> v + w;
                case "minus" -> v - w;
                case "times" -> v * w;
                case "div" -> v / w;
                case "rem" -> v % w;
                case "floorDiv" -> Math.floorDiv(v, w);
                case "mod" -> Math.floorMod(v, w);
                default -> null;
            };
        }
        int i = (int) v;
        int j = (int) w;
        return switch (name) {
            case "plus" -> i + j;
            case "minus" -> i - j;
            case "times" -> i * j;
            case "div" -> i / j;
            case "rem" -> i % j;
            case "floorDiv" -> Math.floorDiv(i, j);
            case "mod" -> Math.floorMod(i, j);
            default -> null;
        };
    }

    // ==== Kinds and values ====

    private static Kind kind(KType type) {
        if (!(Types.lowerBound(type) instanceof KType.ClassType c) || !c.symbol().id().packageName().equals("kotlin")) {
            return null;
        }
        return switch (c.symbol().id().relativeName()) {
            case "Boolean" -> Kind.BOOLEAN;
            case "Char" -> Kind.CHAR;
            case "Byte" -> Kind.BYTE;
            case "Short" -> Kind.SHORT;
            case "Int" -> Kind.INT;
            case "Long" -> Kind.LONG;
            case "Float" -> Kind.FLOAT;
            case "Double" -> Kind.DOUBLE;
            case "String" -> Kind.STRING;
            case "UByte" -> Kind.UBYTE;
            case "UShort" -> Kind.USHORT;
            case "UInt" -> Kind.UINT;
            case "ULong" -> Kind.ULONG;
            default -> null;
        };
    }

    private static boolean isUnsigned(KType type) {
        Kind k = kind(type);
        return k == Kind.UBYTE || k == Kind.USHORT || k == Kind.UINT || k == Kind.ULONG;
    }

    private static boolean isKClass(KType type) {
        return Types.lowerBound(type) instanceof KType.ClassType c && c.symbol().id().fqName().equals("kotlin.reflect.KClass");
    }

    /** A value as the Java value for the kind, as ConstantValueKind.convertToGivenKind. */
    private static Object convert(Object value, Kind kind, TExpr e) {
        if (kind == null) throw new NotConstant(Failure.NOT_CONST, e);
        Object result = switch (kind) {
            case BOOLEAN -> value instanceof Boolean ? value : null;
            case CHAR -> value instanceof Character ? value : null;
            case STRING -> value instanceof String ? value : null;
            case BYTE, UBYTE -> value instanceof Number n ? (Object) n.byteValue() : null;
            case SHORT, USHORT -> value instanceof Number n ? (Object) n.shortValue() : null;
            case INT, UINT -> value instanceof Number n ? (Object) n.intValue() : null;
            case LONG, ULONG -> value instanceof Number n ? (Object) n.longValue() : null;
            case FLOAT -> value instanceof Number n ? (Object) n.floatValue() : null;
            case DOUBLE -> value instanceof Number n ? (Object) n.doubleValue() : null;
        };
        if (result == null) throw new NotConstant(Failure.NOT_CONST, e);
        return result;
    }

    /** A value as Kotlin's toString gives it. */
    private static String string(Object value, Kind kind) {
        if (value == null) return "null";
        return switch (kind) {
            case UBYTE -> Integer.toString(((Number) value).intValue() & 0xFF);
            case USHORT -> Integer.toString(((Number) value).intValue() & 0xFFFF);
            case UINT -> Integer.toUnsignedString(((Number) value).intValue());
            case ULONG -> Long.toUnsignedString(((Number) value).longValue());
            case null, default -> value.toString();
        };
    }

    private static String ownerName(kotlinbootstrap.symbols.CallableSymbol s) {
        return s.owner() instanceof ClassSymbol c ? c.id().fqName() : null;
    }
}

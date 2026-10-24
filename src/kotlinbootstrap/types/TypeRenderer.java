package kotlinbootstrap.types;

import java.util.List;
import java.util.StringJoiner;

import kotlinbootstrap.symbols.ClassSymbol;

/**
 * Renders types as kotlinc does in diagnostics and kotlin-reflect, with
 * fully qualified names: {@code kotlin.collections.List<kotlin.String>?},
 * {@code suspend R.(T) -> R}, {@code kotlin.String!}.
 */
public final class TypeRenderer {
    private TypeRenderer() {
    }

    public static String render(KType type) {
        return switch (type) {
            case KType.ClassType c -> renderClassType(c);
            case KType.TypeParamType t -> t.nullable() ? t.symbol().name() + "?" : t.symbol().name();
            case KType.DefinitelyNotNull d -> d.inner().symbol().name() + " & Any";
            case KType.Flexible f -> renderFlexible(f);
        };
    }

    public static String render(TypeArg arg) {
        return arg.isStar() ? "*" : arg.variance().prefix() + render(arg.type());
    }

    private static String renderClassType(KType.ClassType c) {
        String s;
        if (isFunctionType(c)) {
            s = renderFunctionType(c);
            return c.nullable() ? "(" + s + ")?" : s;
        }
        s = renderClassName(c.symbol(), c.args());
        return c.nullable() ? s + "?" : s;
    }

    /** The class's qualified name with its arguments, and those of outer classes for an inner class. */
    private static String renderClassName(ClassSymbol cls, List<TypeArg> args) {
        int own = cls.typeParams().size();
        String s = cls.isInner()
                ? renderClassName(cls.outer(), args.subList(own, args.size())) + "." + cls.name()
                : cls.id().fqName();
        return own > 0 ? s + renderArgs(args.subList(0, own)) : s;
    }

    private static String renderArgs(List<TypeArg> args) {
        StringJoiner j = new StringJoiner(", ", "<", ">");
        for (TypeArg a : args) j.add(render(a));
        return j.toString();
    }

    public static boolean isFunctionType(KType.ClassType c) {
        ClassSymbol cls = c.symbol();
        String pkg = cls.id().packageName();
        return (pkg.equals("kotlin") && cls.name().matches("Function\\d+"))
                || (pkg.equals("kotlin.coroutines") && cls.name().matches("SuspendFunction\\d+"));
    }

    private static String renderFunctionType(KType.ClassType c) {
        StringBuilder b = new StringBuilder();
        if (c.symbol().id().packageName().equals("kotlin.coroutines")) b.append("suspend ");
        List<TypeArg> args = c.args();
        int i = 0;
        if (c.contextParams() > 0) {
            StringJoiner j = new StringJoiner(", ", "context(", ") ");
            for (; i < c.contextParams(); i++) j.add(render(args.get(i)));
            b.append(j);
        }
        if (c.extensionFunction()) {
            KType receiver = args.get(i++).type();
            String r = render(receiver);
            boolean parens = receiver instanceof KType.ClassType rc && isFunctionType(rc) && !rc.nullable();
            b.append(parens ? "(" + r + ")" : r).append('.');
        }
        StringJoiner params = new StringJoiner(", ", "(", ")");
        for (; i < args.size() - 1; i++) params.add(render(args.get(i)));
        return b.append(params).append(" -> ").append(render(args.getLast())).toString();
    }

    /**
     * {@code T!} when the bounds differ only in nullability; otherwise a
     * simplified version of kotlinc's notation, e.g. {@code (Mutable)List<T>!}.
     */
    private static String renderFlexible(KType.Flexible f) {
        if (f.lower().withNullable(true).equals(f.upper())) return render(f.lower()) + "!";
        if (f.lower() instanceof KType.ClassType l && f.upper() instanceof KType.ClassType u
                && l.symbol().name().equals("Mutable" + u.symbol().name())) {
            String upper = render(u.withNullable(false));
            int dot = u.symbol().id().packageName().length() + 1;
            return upper.substring(0, dot) + "(Mutable)" + upper.substring(dot) + "!";
        }
        return "(" + render(f.lower()) + ".." + render(f.upper()) + ")";
    }
}

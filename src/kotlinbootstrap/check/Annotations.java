package kotlinbootstrap.check;

import java.util.List;

import kotlinbootstrap.ast.Annotation;
import kotlinbootstrap.ast.Decl;
import kotlinbootstrap.ast.Expr;
import kotlinbootstrap.ast.ValueArg;
import kotlinbootstrap.resolve.KotlinClassSymbol;
import kotlinbootstrap.symbols.Origin;
import kotlinbootstrap.symbols.Symbol;
import kotlinbootstrap.symbols.TypeAliasSymbol;

/**
 * The annotations of Kotlin declarations that matter to resolution, found
 * by their short names: deprecation that hides a declaration, low priority
 * in overload resolution, and the extensions that hide members.
 */
final class Annotations {
    /** The API version declarations deprecated with {@code hiddenSince} are compared with. */
    private static final int[] API_VERSION = {2, 4};

    private Annotations() {
    }

    /** The annotations of a declaration from Kotlin source; none for others. */
    static List<Annotation> of(Symbol symbol) {
        if (symbol.origin() != Origin.KOTLIN_SOURCE) return List.of();
        Object source = switch (symbol) {
            case KotlinClassSymbol cls -> cls.decl();
            case TypeAliasSymbol alias -> alias.source();
            case kotlinbootstrap.symbols.CallableSymbol c -> c.source();
            default -> null;
        };
        return switch (source) {
            case Decl.Function f -> f.modifiers().annotations();
            case Decl.Property p -> p.modifiers().annotations();
            case Decl.Param p -> p.modifiers().annotations();
            case Decl.SecondaryConstructor c -> c.modifiers().annotations();
            case Decl.PrimaryConstructor c -> c.modifiers().annotations();
            case Decl.Class c when symbol instanceof KotlinClassSymbol -> c.modifiers().annotations();
            case Decl.TypeAlias a -> a.modifiers().annotations();
            case Decl.EnumEntry e -> e.modifiers().annotations();
            case null, default -> List.of();
        };
    }

    static boolean has(Symbol symbol, String shortName) {
        for (Annotation a : of(symbol)) {
            if (a.type().segments().getLast().name().equals(shortName)) return true;
        }
        return false;
    }

    /**
     * Whether the declaration is deprecated with level HIDDEN, or hidden
     * since an API version not after the current one.
     */
    static boolean isDeprecatedHidden(Symbol symbol) {
        for (Annotation a : of(symbol)) {
            String name = a.type().segments().getLast().name();
            if (name.equals("Deprecated")) {
                ValueArg level = argument(a, "level", 2);
                if (level != null && lastName(level.value()).equals("HIDDEN")) return true;
            } else if (name.equals("DeprecatedSinceKotlin")) {
                ValueArg hiddenSince = argument(a, "hiddenSince", 2);
                if (hiddenSince != null && hiddenSince.value() instanceof Expr.StringTemplate s
                        && s.parts().size() == 1 && s.parts().getFirst() instanceof Expr.TextPart t
                        && notAfterApiVersion(t.text())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The argument for the parameter, given by name or by position. */
    private static ValueArg argument(Annotation a, String name, int position) {
        for (ValueArg arg : a.args()) {
            if (name.equals(arg.name())) return arg;
        }
        if (a.args().size() > position && a.args().get(position).name() == null) return a.args().get(position);
        return null;
    }

    private static String lastName(Expr e) {
        return switch (e) {
            case Expr.Name n -> n.name();
            case Expr.Member m -> m.name();
            default -> "";
        };
    }

    private static boolean notAfterApiVersion(String version) {
        String[] parts = version.split("\\.");
        try {
            for (int i = 0; i < API_VERSION.length; i++) {
                int v = i < parts.length ? Integer.parseInt(parts[i]) : 0;
                if (v != API_VERSION[i]) return v < API_VERSION[i];
            }
        } catch (NumberFormatException e) {
            return false;
        }
        return true;
    }
}

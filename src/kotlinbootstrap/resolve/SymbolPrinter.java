package kotlinbootstrap.resolve;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;

import kotlinbootstrap.ast.Decl;
import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassId;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.ClassifierSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.MemberScope;
import kotlinbootstrap.symbols.Origin;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.symbols.TypeAliasSymbol;
import kotlinbootstrap.symbols.TypeParamSymbol;
import kotlinbootstrap.symbols.ValueParamSymbol;
import kotlinbootstrap.syntax.SourceFile;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeRenderer;

/**
 * Prints the resolved declarations of a file in a Kotlin-like notation,
 * with explicit visibility, modality and fully qualified types:
 *
 * <pre>
 * public final class p.C&lt;T : kotlin.Any?&gt; : kotlin.Any {
 *     public constructor(x: T)
 *     public final val x: T
 * }
 * </pre>
 *
 * Synthesized members are marked, types that would have to be inferred
 * are shown as {@code <inferred>}, and a declaration whose resolution fails
 * shows the error instead.
 */
public final class SymbolPrinter {
    private final Resolver resolver;
    private final FileContext file;
    private final StringBuilder out = new StringBuilder();

    private SymbolPrinter(Resolver resolver, FileContext file) {
        this.resolver = resolver;
        this.file = file;
    }

    public static String print(Resolver resolver, FileContext file) {
        SymbolPrinter p = new SymbolPrinter(resolver, file);
        p.printFile();
        return p.out.toString();
    }

    private void printFile() {
        MemberScope pkg = resolver.table().packageScope(file.packageName());
        for (Decl decl : file.file().declarations()) {
            switch (decl) {
                case Decl.Class c -> printClassifier(resolver.table().kotlinClassifiers()
                        .get(new ClassId(file.packageName(), c.name())), c, "");
                case Decl.TypeAlias a -> printClassifier(resolver.table().kotlinClassifiers()
                        .get(new ClassId(file.packageName(), a.name())), a, "");
                default -> {
                    for (CallableSymbol c : callables(pkg)) {
                        if (c.source() == decl) printCallable(c, "");
                    }
                }
            }
        }
    }

    /** @param decl the declaration in this file; the classifier may come from elsewhere (an actual class) */
    private void printClassifier(ClassifierSymbol c, Decl decl, String indent) {
        Object source = c instanceof KotlinClassSymbol k ? k.decl() : c instanceof TypeAliasSymbol a ? a.source() : null;
        if (source != decl) {
            out.append(indent).append("// replaced by an actual declaration\n");
            return;
        }
        try {
            switch (c) {
                case KotlinClassSymbol cls -> printClass(cls, indent);
                case TypeAliasSymbol alias -> out.append(indent).append(alias.visibility().name().toLowerCase(Locale.ROOT))
                        .append(" typealias ").append(alias.id().fqName()).append(typeParams(alias.typeParams()))
                        .append(" = ").append(TypeRenderer.render(alias.expansion())).append('\n');
                default -> throw new IllegalStateException("Unexpected classifier " + c);
            }
        } catch (ResolveException e) {
            error(e, indent);
        }
    }

    private void printClass(KotlinClassSymbol cls, String indent) {
        String header = indent + cls.visibility().name().toLowerCase(Locale.ROOT) + " "
                + cls.modality().name().toLowerCase(Locale.ROOT) + " " + keywords(classKeywords(cls))
                + switch (cls.kind()) {
                    case CLASS -> "class ";
                    case INTERFACE -> "interface ";
                    case OBJECT -> "object ";
                    case ENUM_CLASS -> "enum class ";
                    case ENUM_ENTRY -> "enum entry ";
                    case ANNOTATION_CLASS -> "annotation class ";
                }
                + cls.id().fqName() + typeParams(cls.typeParams());
        List<String> supertypes = cls.supertypes().stream().map(TypeRenderer::render).toList();
        out.append(header);
        if (!supertypes.isEmpty()) out.append(" : ").append(String.join(", ", supertypes));
        out.append(" {\n");
        String inner = indent + "    ";
        MemberScope members = cls.members();
        for (FunctionSymbol ctor : members.constructors()) printCallable(ctor, inner);
        for (CallableSymbol c : callables(members)) printCallable(c, inner);
        for (Decl member : cls.decl().members()) {
            if (member instanceof Decl.Class nested) {
                printClassifier(members.classifier(Resolver.className(nested)), nested, inner);
            } else if (member instanceof Decl.TypeAlias a) {
                printClassifier(members.classifier(a.name()), a, inner);
            }
        }
        out.append(indent).append("}\n");
    }

    /** The enum and annotation keywords are shown as part of the kind. */
    private static Set<Modifier> classKeywords(ClassSymbol cls) {
        Set<Modifier> result = EnumSet.noneOf(Modifier.class);
        result.addAll(cls.modifiers());
        result.removeAll(EnumSet.of(Modifier.ENUM, Modifier.ANNOTATION));
        return result;
    }

    private static List<CallableSymbol> callables(MemberScope scope) {
        List<CallableSymbol> result = new ArrayList<>();
        scope.properties().values().forEach(result::addAll);
        scope.functions().values().forEach(result::addAll);
        return result;
    }

    private void printCallable(CallableSymbol c, String indent) {
        try {
            StringBuilder b = new StringBuilder(indent);
            if (c.origin() == Origin.SYNTHETIC) b.append("synthesized ");
            b.append(c.visibility().name().toLowerCase(Locale.ROOT)).append(' ');
            boolean isConstructor = c instanceof FunctionSymbol f && f.isConstructor();
            if (!isConstructor) b.append(c.modality().name().toLowerCase(Locale.ROOT)).append(' ');
            if (c.isStatic()) b.append("static ");
            b.append(keywords(c.modifiers()));
            if (c instanceof PropertySymbol p) {
                b.append(p.isVar() ? "var " : "val ");
            } else {
                b.append(isConstructor ? "constructor" : "fun ");
            }
            if (!isConstructor) b.append(typeParams(c.typeParams()));
            if (!c.typeParams().isEmpty() && !isConstructor) b.append(' ');
            CallableSymbol.Signature sig = c.signature();
            if (!sig.contextParams().isEmpty()) {
                StringJoiner j = new StringJoiner(", ", "context(", ") ");
                for (KType t : sig.contextParams()) j.add(TypeRenderer.render(t));
                b.append(j);
            }
            if (sig.receiver() != null) b.append(receiver(sig.receiver())).append('.');
            if (!isConstructor) b.append(c.name());
            if (c instanceof FunctionSymbol) {
                StringJoiner params = new StringJoiner(", ", "(", ")");
                for (ValueParamSymbol p : sig.params()) {
                    params.add(keywords(p.modifiers()) + p.name() + ": " + TypeRenderer.render(p.type())
                            + (p.hasDefault() ? " = ..." : ""));
                }
                b.append(params);
            }
            if (!isConstructor) b.append(": ").append(Resolver.hasDeclaredType(c) ? TypeRenderer.render(c.returnType()) : "<inferred>");
            if (c instanceof PropertySymbol p && p.isVar() && p.setterVisibility() != p.visibility()) {
                b.append(" { ").append(p.setterVisibility().name().toLowerCase(Locale.ROOT)).append(" set }");
            }
            out.append(b).append('\n');
        } catch (ResolveException e) {
            error(e, indent);
        }
    }

    /** A function type as receiver needs parentheses: {@code (() -> Unit).f()}. */
    private static String receiver(KType t) {
        String s = TypeRenderer.render(t);
        return t instanceof KType.ClassType c && TypeRenderer.isFunctionType(c) && !c.nullable() ? "(" + s + ")" : s;
    }

    private static String typeParams(List<TypeParamSymbol> tps) {
        if (tps.isEmpty()) return "";
        StringJoiner j = new StringJoiner(", ", "<", ">");
        for (TypeParamSymbol tp : tps) {
            String bounds = String.join(" & ", tp.bounds().stream().map(TypeRenderer::render).toList());
            j.add((tp.reified() ? "reified " : "") + tp.variance().prefix() + tp.name() + " : " + bounds);
        }
        return j.toString();
    }

    private static String keywords(Set<Modifier> modifiers) {
        StringBuilder b = new StringBuilder();
        for (Modifier m : Modifier.values()) {
            if (modifiers.contains(m)) b.append(m.name().toLowerCase(Locale.ROOT)).append(' ');
        }
        return b.toString();
    }

    private void error(ResolveException e, String indent) {
        SourceFile source = e.file() != null ? e.file() : file.file().source();
        out.append(indent).append("error ").append(source.line(e.offset())).append(':')
                .append(source.column(e.offset())).append(": ").append(e.getMessage()).append('\n');
    }
}

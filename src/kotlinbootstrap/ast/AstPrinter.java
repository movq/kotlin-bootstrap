package kotlinbootstrap.ast;

import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

import kotlinbootstrap.syntax.SourceFile;

/**
 * Prints a syntax tree as an indented S-expression, for golden tests and
 * debugging. Works reflectively on the records, so new node types need no
 * changes here. Subtrees that fit on a line are printed on one line.
 *
 * <p>Positions are omitted so that dumps are stable under edits. So are
 * components with "absent" values (null, false, empty lists, empty
 * modifiers), which keeps the dumps readable.
 */
public final class AstPrinter {
    /** Subtrees that fit in this many columns are printed on one line. */
    private static final int WIDTH = 100;

    private final StringBuilder out = new StringBuilder();

    private AstPrinter() {
    }

    public static String print(Object node) {
        AstPrinter p = new AstPrinter();
        p.value(node, 0);
        p.out.append('\n');
        return p.out.toString();
    }

    private void value(Object v, int indent) {
        switch (v) {
            case Record r when !fits(r, indent) -> record(r, indent);
            case Collection<?> c when !(c instanceof Set) && !fits(c, indent) -> list(c, indent);
            default -> out.append(flat(v));
        }
    }

    private static boolean fits(Object v, int indent) {
        return 2 * indent + flat(v).length() <= WIDTH;
    }

    private void record(Record r, int indent) {
        out.append('(').append(name(r.getClass()));
        for (RecordComponent c : shownComponents(r)) {
            newline(indent + 1);
            out.append(c.getName()).append('=');
            value(get(r, c), indent + 1);
        }
        out.append(')');
    }

    private void list(Collection<?> items, int indent) {
        out.append('[');
        for (Object item : items) {
            newline(indent + 1);
            value(item, indent + 1);
        }
        out.append(']');
    }

    /** The single-line form of a value. */
    private static String flat(Object v) {
        return switch (v) {
            case null -> "null";
            case String s -> quote(s);
            case Character c -> quote(String.valueOf(c));
            case Set<?> s -> modifierSet(s);
            case Collection<?> c -> {
                StringJoiner j = new StringJoiner(" ", "[", "]");
                for (Object item : c) j.add(flat(item));
                yield j.toString();
            }
            case Record r -> {
                StringBuilder b = new StringBuilder("(").append(name(r.getClass()));
                for (RecordComponent c : shownComponents(r)) {
                    b.append(' ').append(c.getName()).append('=').append(flat(get(r, c)));
                }
                yield b.append(')').toString();
            }
            default -> v.toString();
        };
    }

    /** Modifier sets print in enum order, whatever the set implementation. */
    private static String modifierSet(Set<?> set) {
        EnumSet<Modifier> sorted = EnumSet.noneOf(Modifier.class);
        for (Object m : set) sorted.add((Modifier) m);
        StringJoiner j = new StringJoiner(" ", "[", "]");
        for (Modifier m : sorted) j.add(m.keyword());
        return j.toString();
    }

    private static String quote(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20 || c >= 0x7f) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private void newline(int indent) {
        out.append('\n');
        out.append("  ".repeat(indent));
    }

    private static List<RecordComponent> shownComponents(Record r) {
        return List.of(r.getClass().getRecordComponents()).stream()
                .filter(c -> !c.getName().equals("pos"))
                .filter(c -> !isAbsent(get(r, c)))
                .toList();
    }

    private static boolean isAbsent(Object v) {
        return switch (v) {
            case null -> true;
            case Boolean b -> !b;
            case Collection<?> c -> c.isEmpty();
            case Modifiers m -> m.isEmpty();
            case SourceFile f -> true;
            default -> false;
        };
    }

    private static Object get(Record r, RecordComponent c) {
        try {
            return c.getAccessor().invoke(r);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Nested record names are qualified by their enclosing type: Expr.Call, Decl.Class. */
    private static String name(Class<?> type) {
        Class<?> outer = type.getEnclosingClass();
        return outer == null ? type.getSimpleName() : name(outer) + "." + type.getSimpleName();
    }
}

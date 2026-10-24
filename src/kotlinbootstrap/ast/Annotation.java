package kotlinbootstrap.ast;

import java.util.List;

/**
 * An annotation entry, e.g. {@code @get:JvmName("x")}. An annotation list
 * {@code @[A B]} is expanded into one entry per annotation.
 *
 * @param target use-site target ("get", "file", ...), nullable
 * @param args   arguments, empty if there is no argument list
 */
public record Annotation(int pos, String target, TypeRef.Simple type, List<ValueArg> args) implements Node {
}

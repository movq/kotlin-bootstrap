package kotlinbootstrap.ast;

import java.util.List;
import java.util.Set;

/**
 * A modifier list: annotations, modifier keywords and context parameters,
 * e.g. {@code @Ann context(x: Foo) private inline}.
 */
public record Modifiers(List<Annotation> annotations, Set<Modifier> keywords, List<NamedType> contextParams) {
    public static final Modifiers EMPTY = new Modifiers(List.of(), Set.of(), List.of());

    public boolean isEmpty() {
        return annotations.isEmpty() && keywords.isEmpty() && contextParams.isEmpty();
    }

    public boolean has(Modifier m) {
        return keywords.contains(m);
    }
}

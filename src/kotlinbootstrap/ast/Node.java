package kotlinbootstrap.ast;

/**
 * Any syntax tree node. Every node records the source offset where it starts.
 *
 * <p>Nodes are immutable records. Fields documented as nullable use null for
 * "absent"; list fields are never null unless documented otherwise.
 */
public interface Node {
    int pos();
}

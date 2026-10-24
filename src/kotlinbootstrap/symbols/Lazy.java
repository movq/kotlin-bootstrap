package kotlinbootstrap.symbols;

import java.util.function.Supplier;

/**
 * A value computed on first use. Symbols complete their parts lazily, so
 * that declarations can refer to each other in any order; a cycle (such as
 * a class that is its own supertype) is reported rather than overflowing
 * the stack.
 */
public final class Lazy<T> {
    private Supplier<T> compute;
    private T value;
    private boolean computing;

    public Lazy(Supplier<T> compute) {
        this.compute = compute;
    }

    public static <T> Lazy<T> of(T value) {
        Lazy<T> lazy = new Lazy<>(null);
        lazy.value = value;
        return lazy;
    }

    public boolean isComputing() {
        return computing;
    }

    public T get() {
        if (compute != null) {
            if (computing) throw new CyclicDependencyException();
            computing = true;
            try {
                value = compute.get();
                compute = null;
            } finally {
                computing = false;
            }
        }
        return value;
    }

    public static final class CyclicDependencyException extends RuntimeException {
        CyclicDependencyException() {
            super("Cyclic dependency");
        }
    }
}

package kotlinbootstrap.symbols;

import java.util.List;
import java.util.function.Supplier;

import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.Variance;

/**
 * A type parameter of a class, function, property or type alias.
 *
 * @see #bounds() upper bounds; never empty, {@code Any?} if none was declared
 */
public final class TypeParamSymbol implements ClassifierSymbol {
    private final String name;
    private final Origin origin;
    private final Variance variance;
    private final boolean reified;
    private final Lazy<List<KType>> bounds;

    public TypeParamSymbol(String name, Origin origin, Variance variance, boolean reified,
                           Supplier<List<KType>> bounds) {
        this.name = name;
        this.origin = origin;
        this.variance = variance;
        this.reified = reified;
        this.bounds = new Lazy<>(bounds);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Origin origin() {
        return origin;
    }

    /** Declaration-site variance: {@code in}, {@code out} or INVARIANT. */
    public Variance variance() {
        return variance;
    }

    public boolean reified() {
        return reified;
    }

    public List<KType> bounds() {
        return bounds.get();
    }

    @Override
    public String toString() {
        return name;
    }
}

package kotlinbootstrap.resolve;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassFinder;
import kotlinbootstrap.symbols.ClassId;
import kotlinbootstrap.symbols.ClassKind;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.Lazy;
import kotlinbootstrap.symbols.MemberScope;
import kotlinbootstrap.symbols.Modality;
import kotlinbootstrap.symbols.Origin;
import kotlinbootstrap.symbols.TypeParamSymbol;
import kotlinbootstrap.symbols.ValueParamSymbol;
import kotlinbootstrap.symbols.Visibility;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.Variance;

/**
 * The function interfaces, which the stdlib does not declare:
 * <pre>
 * interface FunctionN&lt;in P1, ..., in PN, out R&gt; : Function&lt;R&gt; {
 *     operator fun invoke(p1: P1, ..., pN: PN): R
 * }
 * </pre>
 * and likewise {@code kotlin.coroutines.SuspendFunctionN}, with a suspend {@code invoke}.
 */
final class FunctionClasses implements ClassFinder {
    private final Resolver resolver;

    FunctionClasses(Resolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public ClassSymbol findClass(ClassId id) {
        String prefix = switch (id.packageName()) {
            case "kotlin" -> "Function";
            case "kotlin.coroutines" -> "SuspendFunction";
            default -> null;
        };
        if (prefix == null || !id.relativeName().startsWith(prefix)) return null;
        String digits = id.relativeName().substring(prefix.length());
        if (digits.isEmpty() || digits.length() > 3 || !digits.chars().allMatch(Character::isDigit)) return null;
        return new FunctionClass(id, Integer.parseInt(digits), prefix.equals("SuspendFunction"));
    }

    @Override
    public boolean packageExists(String packageName) {
        return false;
    }

    private final class FunctionClass extends ClassSymbol {
        private final List<TypeParamSymbol> typeParams = new ArrayList<>();
        private final Lazy<MemberScope> members;

        FunctionClass(ClassId id, int arity, boolean suspend) {
            super(id, null, ClassKind.INTERFACE, Origin.SYNTHETIC, Visibility.PUBLIC, Modality.ABSTRACT,
                    Set.of(Modifier.FUN));
            for (int i = 1; i <= arity; i++) {
                typeParams.add(new TypeParamSymbol("P" + i, Origin.SYNTHETIC, Variance.IN, false,
                        () -> List.of(resolver.types().nullableAny())));
            }
            typeParams.add(new TypeParamSymbol("R", Origin.SYNTHETIC, Variance.OUT, false,
                    () -> List.of(resolver.types().nullableAny())));
            this.members = new Lazy<>(() -> {
                MemberScope scope = new MemberScope();
                Set<Modifier> modifiers = EnumSet.of(Modifier.OPERATOR);
                if (suspend) modifiers.add(Modifier.SUSPEND);
                scope.add(new FunctionSymbol("invoke", Origin.SYNTHETIC, this, Visibility.PUBLIC, Modality.ABSTRACT,
                        modifiers, false, null, List::of, () -> {
                    List<ValueParamSymbol> params = new ArrayList<>();
                    for (int i = 0; i < arity; i++) {
                        params.add(new ValueParamSymbol("p" + (i + 1), type(typeParams.get(i)), false, Set.of(), null));
                    }
                    return new CallableSymbol.Signature(null, List.of(), params);
                }, () -> type(typeParams.getLast())));
                return scope;
            });
        }

        private static KType type(TypeParamSymbol tp) {
            return new KType.TypeParamType(tp, false);
        }

        @Override
        public List<TypeParamSymbol> typeParams() {
            return typeParams;
        }

        @Override
        public List<KType> supertypes() {
            return List.of(resolver.types().classType("kotlin.Function", TypeArg.of(type(typeParams.getLast()))));
        }

        @Override
        public MemberScope members() {
            return members.get();
        }
    }
}

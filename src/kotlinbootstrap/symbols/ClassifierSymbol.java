package kotlinbootstrap.symbols;

/** Something a type name can refer to: a class, a type alias or a type parameter. */
public sealed interface ClassifierSymbol extends Symbol permits ClassSymbol, TypeAliasSymbol, TypeParamSymbol {
}

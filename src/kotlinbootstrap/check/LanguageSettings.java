package kotlinbootstrap.check;

/**
 * The language features that are not on by default, as kotlinc's
 * LanguageVersionSettings.
 *
 * @param nameBasedDestructuringShortForm {@code val (a, b) = x} destructures by name, as {@code x.a} and {@code x.b},
 *                                        as with kotlinc's {@code -Xname-based-destructuring=complete}
 */
public record LanguageSettings(boolean nameBasedDestructuringShortForm) {
    public static final LanguageSettings DEFAULT = new LanguageSettings(false);
}

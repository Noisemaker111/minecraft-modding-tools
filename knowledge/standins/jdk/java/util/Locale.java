package mmt.standin.java.util;

/** Java 19's Locale.of(language[, country]), which "obtains a locale from language and country" as the constructors do. */
public final class Locale {

    private Locale() {}

    public static java.util.Locale of(String language) {
        return new java.util.Locale(language);
    }

    public static java.util.Locale of(String language, String country) {
        return new java.util.Locale(language, country);
    }
}

package mmt.standin.java.lang;

/** Java 11's Character.toString(codePoint): "a String object representing the specified character (Unicode code point)", IllegalArgumentException for an invalid one, as String.valueOf(Character.toChars(codePoint)). */
public final class Character {

    private Character() {}

    public static java.lang.String toString(int codePoint) {
        return java.lang.String.valueOf(java.lang.Character.toChars(codePoint));
    }
}

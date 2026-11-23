package mmt.standin.java.lang;

/** Java 15's CharSequence.isEmpty(), as its default method is written: "this.length() == 0" (StringBuilder's included). */
public final class CharSequence {

    private CharSequence() {}

    public static boolean isEmpty(java.lang.CharSequence self) {
        return self.length() == 0;
    }
}

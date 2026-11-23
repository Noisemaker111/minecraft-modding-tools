package mmt.standin.java.lang;

/** Java 9's Byte.compareUnsigned(x, y), as the JDK writes it: "Byte.toUnsignedInt(x) - Byte.toUnsignedInt(y)". */
public final class Byte {

    private Byte() {}

    public static int compareUnsigned(byte x, byte y) {
        return java.lang.Byte.toUnsignedInt(x) - java.lang.Byte.toUnsignedInt(y);
    }
}

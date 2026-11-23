package mmt.standin.java.util;

/**
 * Java 19's HashMap.newHashMap(numMappings), as the JDK writes it: IllegalArgumentException for a negative count, else a
 * HashMap whose capacity holds that many mappings at the default load factor, (int) Math.ceil(numMappings / 0.75).
 */
public final class HashMap {

    private HashMap() {}

    public static <K, V> java.util.HashMap<K, V> newHashMap(int numMappings) {
        if (numMappings < 0) {
            throw new IllegalArgumentException("Negative number of mappings: " + numMappings);
        }
        return new java.util.HashMap<>((int) Math.ceil(numMappings / 0.75));
    }
}

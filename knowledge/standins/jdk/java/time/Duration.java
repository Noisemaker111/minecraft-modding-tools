package mmt.standin.java.time;

/** java.time.Duration members older Javas lack: toSeconds() (Java 9) "the number of seconds in this duration", getSeconds(); isPositive() (Java 18) "true if this duration is greater than zero". */
public final class Duration {

    private Duration() {}

    public static long toSeconds(java.time.Duration self) {
        return self.getSeconds();
    }

    public static boolean isPositive(java.time.Duration self) {
        return !self.isNegative() && !self.isZero();
    }
}

package mmt.standin.net.minecraft.util;

/**
 * 26.1.2's Tuple, which 26.2 removed.
 *
 * Read from both releases: 26.1.2's Tuple held two values a and b, set by its constructor and setA/setB and read by
 * getA/getB; nothing else. 26.2 drops the class, its game code using records and Pair in its place. So the stand-in is the
 * class, as 26.1.2 wrote it.
 *
 * Agrees with NeoForge's 26.1.2 -> 26.2 primer (https://github.com/neoforged/.github/blob/main/primers/26.2/index.md, CC BY 4.0), whose list of removals names "net.minecraft.util.Tuple".
 */
public class Tuple {

    private Object a;
    private Object b;

    public Tuple(Object a, Object b) {
        this.a = a;
        this.b = b;
    }

    public Object getA() {
        return a;
    }

    public void setA(Object a) {
        this.a = a;
    }

    public Object getB() {
        return b;
    }

    public void setB(Object b) {
        this.b = b;
    }
}

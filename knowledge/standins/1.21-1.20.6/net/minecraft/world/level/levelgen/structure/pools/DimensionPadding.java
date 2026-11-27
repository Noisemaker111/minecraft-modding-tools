package mmt.standin.net.minecraft.world.level.levelgen.structure.pools;

/**
 * 1.21's DimensionPadding on 1.20.6, for a port back: the class itself, which 1.20.6 does not have yet, as 1.21 writes
 * it (bottom, top; one number for both; CODEC either the number or {bottom, top}, each non-negative, 0 when left out).
 *
 * Read from both releases: 1.21's jigsaw placement keeps a structure's pieces that far inside the level's floor and
 * ceiling (JigsawPlacement.addPieces, JigsawStructure's dimension_padding, default ZERO). 1.20.6 keeps them inside the
 * level with no padding, as ZERO does: what is handed a DimensionPadding here (the stand-ins for JigsawStructure and
 * JigsawPlacement) places as ZERO whatever it holds.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`net.minecraft.world.level.levelgen.structure.pools.DimensionPadding` - The padding to apply above and below a structure when attempting to generate".
 */
public final class DimensionPadding {

    static final com.mojang.serialization.Codec RECORD_CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.create(i -> ((com.mojang.serialization.codecs.RecordCodecBuilder.Instance) i).group(
            net.minecraft.util.ExtraCodecs.NON_NEGATIVE_INT.lenientOptionalFieldOf("bottom", 0).forGetter(p -> ((DimensionPadding) p).bottom),
            net.minecraft.util.ExtraCodecs.NON_NEGATIVE_INT.lenientOptionalFieldOf("top", 0).forGetter(p -> ((DimensionPadding) p).top))
        .apply((com.mojang.datafixers.kinds.Applicative) i, (b, t) -> new DimensionPadding((Integer) b, (Integer) t)));

    public static final com.mojang.serialization.Codec CODEC = com.mojang.serialization.Codec.either(net.minecraft.util.ExtraCodecs.NON_NEGATIVE_INT, RECORD_CODEC).xmap(
        e -> ((com.mojang.datafixers.util.Either) e).map(n -> new DimensionPadding((Integer) n), java.util.function.Function.identity()),
        p -> ((DimensionPadding) p).hasEqualTopAndBottom() ? com.mojang.datafixers.util.Either.left(((DimensionPadding) p).bottom) : com.mojang.datafixers.util.Either.right(p));

    public static final DimensionPadding ZERO = new DimensionPadding(0);

    private final int bottom;
    private final int top;

    public DimensionPadding(int bottom, int top) {
        this.bottom = bottom;
        this.top = top;
    }

    public DimensionPadding(int both) {
        this(both, both);
    }

    public int bottom() {
        return bottom;
    }

    public int top() {
        return top;
    }

    public boolean hasEqualTopAndBottom() {
        return top == bottom;
    }

    public boolean equals(Object o) {
        return o instanceof DimensionPadding p && p.bottom == bottom && p.top == top;
    }

    public int hashCode() {
        return 31 * bottom + top;
    }
}

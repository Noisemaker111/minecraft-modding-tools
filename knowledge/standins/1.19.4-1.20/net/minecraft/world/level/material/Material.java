package mmt.standin.net.minecraft.world.level.material;

import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/**
 * 1.19.4's Material on 1.20.
 *
 * Read from both releases: 1.19.4's Material was a value of a map color, a push reaction and six flags (liquid, solid,
 * blocks motion, solid blocking, flammable, replaceable), each constant built by Material$Builder from its color (every
 * builder started solid, blocking motion and solid blocking, NORMAL to push; noCollider, nonSolid, notSolidBlocking,
 * flammable, replaceable, liquid, destroyOnPush and notPushable changed one each). 1.20 removed the class: those flags
 * are block properties now, and MaterialColor is MapColor under the same names. The class is gone, so this is the class,
 * with every 1.19.4 constant built from the same builder calls (read from its static initializer), its colors as
 * MapColor. What a block made with one gets is in the BlockBehaviour$Properties stand-in.
 *
 * Agrees with NeoForge's 1.19.4 -> 1.20 primer (https://github.com/neoforged/.github/blob/main/primers/1.20/index.md, CC BY 4.0): "Materials have been completely removed from Block Properties", and "MaterialColors have now been replaced with MapColors".
 */
public final class Material {

    public static final Material AIR = new Material(MapColor.NONE, false, false, false, false, false, true, PushReaction.NORMAL);
    public static final Material STRUCTURAL_AIR = new Material(MapColor.NONE, false, false, false, false, false, true, PushReaction.NORMAL);
    public static final Material PORTAL = new Material(MapColor.NONE, false, false, false, false, false, false, PushReaction.BLOCK);
    public static final Material CLOTH_DECORATION = new Material(MapColor.WOOL, false, false, false, false, true, false, PushReaction.NORMAL);
    public static final Material PLANT = new Material(MapColor.PLANT, false, false, false, false, false, false, PushReaction.DESTROY);
    public static final Material WATER_PLANT = new Material(MapColor.WATER, false, false, false, false, false, false, PushReaction.DESTROY);
    public static final Material REPLACEABLE_PLANT = new Material(MapColor.PLANT, false, false, false, false, true, true, PushReaction.DESTROY);
    public static final Material REPLACEABLE_FIREPROOF_PLANT = new Material(MapColor.PLANT, false, false, false, false, false, true, PushReaction.DESTROY);
    public static final Material REPLACEABLE_WATER_PLANT = new Material(MapColor.WATER, false, false, false, false, false, true, PushReaction.DESTROY);
    public static final Material WATER = new Material(MapColor.WATER, true, false, false, false, false, true, PushReaction.DESTROY);
    public static final Material BUBBLE_COLUMN = new Material(MapColor.WATER, true, false, false, false, false, true, PushReaction.DESTROY);
    public static final Material LAVA = new Material(MapColor.FIRE, true, false, false, false, false, true, PushReaction.DESTROY);
    public static final Material TOP_SNOW = new Material(MapColor.SNOW, false, false, false, false, false, true, PushReaction.DESTROY);
    public static final Material FIRE = new Material(MapColor.NONE, false, false, false, false, false, true, PushReaction.DESTROY);
    public static final Material DECORATION = new Material(MapColor.NONE, false, false, false, false, false, false, PushReaction.DESTROY);
    public static final Material WEB = new Material(MapColor.WOOL, false, true, false, false, false, false, PushReaction.DESTROY);
    public static final Material SCULK = new Material(MapColor.COLOR_BLACK, false, true, true, true, false, false, PushReaction.NORMAL);
    public static final Material BUILDABLE_GLASS = new Material(MapColor.NONE, false, true, true, true, false, false, PushReaction.NORMAL);
    public static final Material CLAY = new Material(MapColor.CLAY, false, true, true, true, false, false, PushReaction.NORMAL);
    public static final Material DIRT = new Material(MapColor.DIRT, false, true, true, true, false, false, PushReaction.NORMAL);
    public static final Material GRASS = new Material(MapColor.GRASS, false, true, true, true, false, false, PushReaction.NORMAL);
    public static final Material ICE_SOLID = new Material(MapColor.ICE, false, true, true, true, false, false, PushReaction.NORMAL);
    public static final Material SAND = new Material(MapColor.SAND, false, true, true, true, false, false, PushReaction.NORMAL);
    public static final Material SPONGE = new Material(MapColor.COLOR_YELLOW, false, true, true, true, false, false, PushReaction.NORMAL);
    public static final Material SHULKER_SHELL = new Material(MapColor.COLOR_PURPLE, false, true, true, true, false, false, PushReaction.NORMAL);
    public static final Material WOOD = new Material(MapColor.WOOD, false, true, true, true, true, false, PushReaction.NORMAL);
    public static final Material NETHER_WOOD = new Material(MapColor.WOOD, false, true, true, true, false, false, PushReaction.NORMAL);
    public static final Material BAMBOO_SAPLING = new Material(MapColor.WOOD, false, true, false, true, true, false, PushReaction.DESTROY);
    public static final Material BAMBOO = new Material(MapColor.WOOD, false, true, true, true, true, false, PushReaction.DESTROY);
    public static final Material WOOL = new Material(MapColor.WOOL, false, true, true, true, true, false, PushReaction.NORMAL);
    public static final Material EXPLOSIVE = new Material(MapColor.FIRE, false, true, true, false, true, false, PushReaction.NORMAL);
    public static final Material LEAVES = new Material(MapColor.PLANT, false, true, true, false, true, false, PushReaction.DESTROY);
    public static final Material GLASS = new Material(MapColor.NONE, false, true, true, false, false, false, PushReaction.NORMAL);
    public static final Material ICE = new Material(MapColor.ICE, false, true, true, false, false, false, PushReaction.NORMAL);
    public static final Material CACTUS = new Material(MapColor.PLANT, false, true, true, false, false, false, PushReaction.DESTROY);
    public static final Material STONE = new Material(MapColor.STONE, false, true, true, true, false, false, PushReaction.NORMAL);
    public static final Material METAL = new Material(MapColor.METAL, false, true, true, true, false, false, PushReaction.NORMAL);
    public static final Material SNOW = new Material(MapColor.SNOW, false, true, true, true, false, false, PushReaction.NORMAL);
    public static final Material HEAVY_METAL = new Material(MapColor.METAL, false, true, true, true, false, false, PushReaction.BLOCK);
    public static final Material BARRIER = new Material(MapColor.NONE, false, true, true, true, false, false, PushReaction.BLOCK);
    public static final Material PISTON = new Material(MapColor.STONE, false, true, true, true, false, false, PushReaction.BLOCK);
    public static final Material MOSS = new Material(MapColor.PLANT, false, true, true, true, false, false, PushReaction.DESTROY);
    public static final Material VEGETABLE = new Material(MapColor.PLANT, false, true, true, true, false, false, PushReaction.DESTROY);
    public static final Material EGG = new Material(MapColor.PLANT, false, true, true, true, false, false, PushReaction.DESTROY);
    public static final Material CAKE = new Material(MapColor.NONE, false, true, true, true, false, false, PushReaction.DESTROY);
    public static final Material AMETHYST = new Material(MapColor.COLOR_PURPLE, false, true, true, true, false, false, PushReaction.NORMAL);
    public static final Material POWDER_SNOW = new Material(MapColor.SNOW, false, false, false, true, false, false, PushReaction.NORMAL);
    public static final Material FROGSPAWN = new Material(MapColor.WATER, false, false, false, false, false, false, PushReaction.DESTROY);
    public static final Material FROGLIGHT = new Material(MapColor.NONE, false, true, true, true, false, false, PushReaction.NORMAL);
    public static final Material DECORATED_POT = new Material(MapColor.TERRACOTTA_RED, false, true, true, true, false, false, PushReaction.DESTROY);

    private final MapColor color;
    private final PushReaction pushReaction;
    private final boolean blocksMotion;
    private final boolean flammable;
    private final boolean liquid;
    private final boolean solidBlocking;
    private final boolean replaceable;
    private final boolean solid;

    public Material(MapColor color, boolean liquid, boolean solid, boolean blocksMotion, boolean solidBlocking, boolean flammable, boolean replaceable, PushReaction pushReaction) {
        this.color = color;
        this.liquid = liquid;
        this.solid = solid;
        this.blocksMotion = blocksMotion;
        this.solidBlocking = solidBlocking;
        this.flammable = flammable;
        this.replaceable = replaceable;
        this.pushReaction = pushReaction;
    }

    public boolean isLiquid() {
        return liquid;
    }

    public boolean isSolid() {
        return solid;
    }

    public boolean blocksMotion() {
        return blocksMotion;
    }

    public boolean isFlammable() {
        return flammable;
    }

    public boolean isReplaceable() {
        return replaceable;
    }

    public boolean isSolidBlocking() {
        return solidBlocking;
    }

    public PushReaction getPushReaction() {
        return pushReaction;
    }

    public MapColor getColor() {
        return color;
    }
}

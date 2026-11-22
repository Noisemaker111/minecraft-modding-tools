package mmt.standin.net.minecraft.world.level.block;

/**
 * 26.1.2's Blocks on 26.2: its sixteen-color constants.
 *
 * Read from both releases: 26.1.2's Blocks kept one static field per dye color for each colored set (WHITE_X,
 * ORANGE_X ... BLACK_X). 26.2 keeps each set as one ColorCollection field X, a record with one component per color
 * (white(), orange() ... black(), in DyeColor's order), made from the same sixteen. So each old field is its color's
 * component of the set's collection. Generated from both releases' fields: every old COLOR_X 26.2 dropped where 26.2 has
 * a ColorCollection X.
 *
 * Agrees with NeoForge's 26.1.2 -> 26.2 primer (https://github.com/neoforged/.github/blob/main/primers/26.2/index.md, CC BY 4.0): "ColorCollection - A collection of objects whose variants represent one of sixteen colors" and "picking a specific entry from its collection method".
 */
public final class Blocks {

    private Blocks() {}

    public static net.minecraft.world.level.block.Block BLACK_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BANNER.black();
    }

    public static net.minecraft.world.level.block.Block BLACK_BED() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BED.black();
    }

    public static net.minecraft.world.level.block.Block BLACK_CARPET() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CARPET.black();
    }

    public static net.minecraft.world.level.block.Block BLACK_CONCRETE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE.black();
    }

    public static net.minecraft.world.level.block.Block BLACK_CONCRETE_POWDER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE_POWDER.black();
    }

    public static net.minecraft.world.level.block.Block BLACK_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.GLAZED_TERRACOTTA.black();
    }

    public static net.minecraft.world.level.block.Block BLACK_STAINED_GLASS() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS.black();
    }

    public static net.minecraft.world.level.block.Block BLACK_STAINED_GLASS_PANE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS_PANE.black();
    }

    public static net.minecraft.world.level.block.Block BLACK_WALL_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WALL_BANNER.black();
    }

    public static net.minecraft.world.level.block.Block BLACK_WOOL() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WOOL.black();
    }

    public static net.minecraft.world.level.block.Block BLUE_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BANNER.blue();
    }

    public static net.minecraft.world.level.block.Block BLUE_BED() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BED.blue();
    }

    public static net.minecraft.world.level.block.Block BLUE_CARPET() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CARPET.blue();
    }

    public static net.minecraft.world.level.block.Block BLUE_CONCRETE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE.blue();
    }

    public static net.minecraft.world.level.block.Block BLUE_CONCRETE_POWDER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE_POWDER.blue();
    }

    public static net.minecraft.world.level.block.Block BLUE_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.GLAZED_TERRACOTTA.blue();
    }

    public static net.minecraft.world.level.block.Block BLUE_STAINED_GLASS() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS.blue();
    }

    public static net.minecraft.world.level.block.Block BLUE_STAINED_GLASS_PANE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS_PANE.blue();
    }

    public static net.minecraft.world.level.block.Block BLUE_WALL_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WALL_BANNER.blue();
    }

    public static net.minecraft.world.level.block.Block BLUE_WOOL() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WOOL.blue();
    }

    public static net.minecraft.world.level.block.Block BROWN_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BANNER.brown();
    }

    public static net.minecraft.world.level.block.Block BROWN_BED() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BED.brown();
    }

    public static net.minecraft.world.level.block.Block BROWN_CARPET() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CARPET.brown();
    }

    public static net.minecraft.world.level.block.Block BROWN_CONCRETE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE.brown();
    }

    public static net.minecraft.world.level.block.Block BROWN_CONCRETE_POWDER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE_POWDER.brown();
    }

    public static net.minecraft.world.level.block.Block BROWN_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.GLAZED_TERRACOTTA.brown();
    }

    public static net.minecraft.world.level.block.Block BROWN_STAINED_GLASS() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS.brown();
    }

    public static net.minecraft.world.level.block.Block BROWN_STAINED_GLASS_PANE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS_PANE.brown();
    }

    public static net.minecraft.world.level.block.Block BROWN_WALL_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WALL_BANNER.brown();
    }

    public static net.minecraft.world.level.block.Block BROWN_WOOL() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WOOL.brown();
    }

    public static net.minecraft.world.level.block.Block CYAN_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BANNER.cyan();
    }

    public static net.minecraft.world.level.block.Block CYAN_BED() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BED.cyan();
    }

    public static net.minecraft.world.level.block.Block CYAN_CARPET() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CARPET.cyan();
    }

    public static net.minecraft.world.level.block.Block CYAN_CONCRETE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE.cyan();
    }

    public static net.minecraft.world.level.block.Block CYAN_CONCRETE_POWDER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE_POWDER.cyan();
    }

    public static net.minecraft.world.level.block.Block CYAN_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.GLAZED_TERRACOTTA.cyan();
    }

    public static net.minecraft.world.level.block.Block CYAN_STAINED_GLASS() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS.cyan();
    }

    public static net.minecraft.world.level.block.Block CYAN_STAINED_GLASS_PANE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS_PANE.cyan();
    }

    public static net.minecraft.world.level.block.Block CYAN_WALL_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WALL_BANNER.cyan();
    }

    public static net.minecraft.world.level.block.Block CYAN_WOOL() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WOOL.cyan();
    }

    public static net.minecraft.world.level.block.Block GRAY_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BANNER.gray();
    }

    public static net.minecraft.world.level.block.Block GRAY_BED() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BED.gray();
    }

    public static net.minecraft.world.level.block.Block GRAY_CARPET() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CARPET.gray();
    }

    public static net.minecraft.world.level.block.Block GRAY_CONCRETE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE.gray();
    }

    public static net.minecraft.world.level.block.Block GRAY_CONCRETE_POWDER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE_POWDER.gray();
    }

    public static net.minecraft.world.level.block.Block GRAY_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.GLAZED_TERRACOTTA.gray();
    }

    public static net.minecraft.world.level.block.Block GRAY_STAINED_GLASS() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS.gray();
    }

    public static net.minecraft.world.level.block.Block GRAY_STAINED_GLASS_PANE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS_PANE.gray();
    }

    public static net.minecraft.world.level.block.Block GRAY_WALL_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WALL_BANNER.gray();
    }

    public static net.minecraft.world.level.block.Block GRAY_WOOL() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WOOL.gray();
    }

    public static net.minecraft.world.level.block.Block GREEN_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BANNER.green();
    }

    public static net.minecraft.world.level.block.Block GREEN_BED() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BED.green();
    }

    public static net.minecraft.world.level.block.Block GREEN_CARPET() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CARPET.green();
    }

    public static net.minecraft.world.level.block.Block GREEN_CONCRETE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE.green();
    }

    public static net.minecraft.world.level.block.Block GREEN_CONCRETE_POWDER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE_POWDER.green();
    }

    public static net.minecraft.world.level.block.Block GREEN_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.GLAZED_TERRACOTTA.green();
    }

    public static net.minecraft.world.level.block.Block GREEN_STAINED_GLASS() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS.green();
    }

    public static net.minecraft.world.level.block.Block GREEN_STAINED_GLASS_PANE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS_PANE.green();
    }

    public static net.minecraft.world.level.block.Block GREEN_WALL_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WALL_BANNER.green();
    }

    public static net.minecraft.world.level.block.Block GREEN_WOOL() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WOOL.green();
    }

    public static net.minecraft.world.level.block.Block LIGHT_BLUE_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BANNER.lightBlue();
    }

    public static net.minecraft.world.level.block.Block LIGHT_BLUE_BED() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BED.lightBlue();
    }

    public static net.minecraft.world.level.block.Block LIGHT_BLUE_CARPET() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CARPET.lightBlue();
    }

    public static net.minecraft.world.level.block.Block LIGHT_BLUE_CONCRETE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE.lightBlue();
    }

    public static net.minecraft.world.level.block.Block LIGHT_BLUE_CONCRETE_POWDER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE_POWDER.lightBlue();
    }

    public static net.minecraft.world.level.block.Block LIGHT_BLUE_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.GLAZED_TERRACOTTA.lightBlue();
    }

    public static net.minecraft.world.level.block.Block LIGHT_BLUE_STAINED_GLASS() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS.lightBlue();
    }

    public static net.minecraft.world.level.block.Block LIGHT_BLUE_STAINED_GLASS_PANE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS_PANE.lightBlue();
    }

    public static net.minecraft.world.level.block.Block LIGHT_BLUE_WALL_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WALL_BANNER.lightBlue();
    }

    public static net.minecraft.world.level.block.Block LIGHT_BLUE_WOOL() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WOOL.lightBlue();
    }

    public static net.minecraft.world.level.block.Block LIGHT_GRAY_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BANNER.lightGray();
    }

    public static net.minecraft.world.level.block.Block LIGHT_GRAY_BED() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BED.lightGray();
    }

    public static net.minecraft.world.level.block.Block LIGHT_GRAY_CARPET() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CARPET.lightGray();
    }

    public static net.minecraft.world.level.block.Block LIGHT_GRAY_CONCRETE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE.lightGray();
    }

    public static net.minecraft.world.level.block.Block LIGHT_GRAY_CONCRETE_POWDER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE_POWDER.lightGray();
    }

    public static net.minecraft.world.level.block.Block LIGHT_GRAY_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.GLAZED_TERRACOTTA.lightGray();
    }

    public static net.minecraft.world.level.block.Block LIGHT_GRAY_STAINED_GLASS() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS.lightGray();
    }

    public static net.minecraft.world.level.block.Block LIGHT_GRAY_STAINED_GLASS_PANE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS_PANE.lightGray();
    }

    public static net.minecraft.world.level.block.Block LIGHT_GRAY_WALL_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WALL_BANNER.lightGray();
    }

    public static net.minecraft.world.level.block.Block LIGHT_GRAY_WOOL() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WOOL.lightGray();
    }

    public static net.minecraft.world.level.block.Block LIME_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BANNER.lime();
    }

    public static net.minecraft.world.level.block.Block LIME_BED() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BED.lime();
    }

    public static net.minecraft.world.level.block.Block LIME_CARPET() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CARPET.lime();
    }

    public static net.minecraft.world.level.block.Block LIME_CONCRETE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE.lime();
    }

    public static net.minecraft.world.level.block.Block LIME_CONCRETE_POWDER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE_POWDER.lime();
    }

    public static net.minecraft.world.level.block.Block LIME_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.GLAZED_TERRACOTTA.lime();
    }

    public static net.minecraft.world.level.block.Block LIME_STAINED_GLASS() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS.lime();
    }

    public static net.minecraft.world.level.block.Block LIME_STAINED_GLASS_PANE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS_PANE.lime();
    }

    public static net.minecraft.world.level.block.Block LIME_WALL_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WALL_BANNER.lime();
    }

    public static net.minecraft.world.level.block.Block LIME_WOOL() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WOOL.lime();
    }

    public static net.minecraft.world.level.block.Block MAGENTA_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BANNER.magenta();
    }

    public static net.minecraft.world.level.block.Block MAGENTA_BED() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BED.magenta();
    }

    public static net.minecraft.world.level.block.Block MAGENTA_CARPET() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CARPET.magenta();
    }

    public static net.minecraft.world.level.block.Block MAGENTA_CONCRETE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE.magenta();
    }

    public static net.minecraft.world.level.block.Block MAGENTA_CONCRETE_POWDER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE_POWDER.magenta();
    }

    public static net.minecraft.world.level.block.Block MAGENTA_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.GLAZED_TERRACOTTA.magenta();
    }

    public static net.minecraft.world.level.block.Block MAGENTA_STAINED_GLASS() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS.magenta();
    }

    public static net.minecraft.world.level.block.Block MAGENTA_STAINED_GLASS_PANE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS_PANE.magenta();
    }

    public static net.minecraft.world.level.block.Block MAGENTA_WALL_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WALL_BANNER.magenta();
    }

    public static net.minecraft.world.level.block.Block MAGENTA_WOOL() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WOOL.magenta();
    }

    public static net.minecraft.world.level.block.Block ORANGE_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BANNER.orange();
    }

    public static net.minecraft.world.level.block.Block ORANGE_BED() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BED.orange();
    }

    public static net.minecraft.world.level.block.Block ORANGE_CARPET() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CARPET.orange();
    }

    public static net.minecraft.world.level.block.Block ORANGE_CONCRETE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE.orange();
    }

    public static net.minecraft.world.level.block.Block ORANGE_CONCRETE_POWDER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE_POWDER.orange();
    }

    public static net.minecraft.world.level.block.Block ORANGE_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.GLAZED_TERRACOTTA.orange();
    }

    public static net.minecraft.world.level.block.Block ORANGE_STAINED_GLASS() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS.orange();
    }

    public static net.minecraft.world.level.block.Block ORANGE_STAINED_GLASS_PANE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS_PANE.orange();
    }

    public static net.minecraft.world.level.block.Block ORANGE_WALL_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WALL_BANNER.orange();
    }

    public static net.minecraft.world.level.block.Block ORANGE_WOOL() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WOOL.orange();
    }

    public static net.minecraft.world.level.block.Block PINK_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BANNER.pink();
    }

    public static net.minecraft.world.level.block.Block PINK_BED() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BED.pink();
    }

    public static net.minecraft.world.level.block.Block PINK_CARPET() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CARPET.pink();
    }

    public static net.minecraft.world.level.block.Block PINK_CONCRETE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE.pink();
    }

    public static net.minecraft.world.level.block.Block PINK_CONCRETE_POWDER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE_POWDER.pink();
    }

    public static net.minecraft.world.level.block.Block PINK_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.GLAZED_TERRACOTTA.pink();
    }

    public static net.minecraft.world.level.block.Block PINK_STAINED_GLASS() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS.pink();
    }

    public static net.minecraft.world.level.block.Block PINK_STAINED_GLASS_PANE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS_PANE.pink();
    }

    public static net.minecraft.world.level.block.Block PINK_WALL_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WALL_BANNER.pink();
    }

    public static net.minecraft.world.level.block.Block PINK_WOOL() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WOOL.pink();
    }

    public static net.minecraft.world.level.block.Block PURPLE_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BANNER.purple();
    }

    public static net.minecraft.world.level.block.Block PURPLE_BED() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BED.purple();
    }

    public static net.minecraft.world.level.block.Block PURPLE_CARPET() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CARPET.purple();
    }

    public static net.minecraft.world.level.block.Block PURPLE_CONCRETE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE.purple();
    }

    public static net.minecraft.world.level.block.Block PURPLE_CONCRETE_POWDER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE_POWDER.purple();
    }

    public static net.minecraft.world.level.block.Block PURPLE_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.GLAZED_TERRACOTTA.purple();
    }

    public static net.minecraft.world.level.block.Block PURPLE_STAINED_GLASS() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS.purple();
    }

    public static net.minecraft.world.level.block.Block PURPLE_STAINED_GLASS_PANE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS_PANE.purple();
    }

    public static net.minecraft.world.level.block.Block PURPLE_WALL_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WALL_BANNER.purple();
    }

    public static net.minecraft.world.level.block.Block PURPLE_WOOL() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WOOL.purple();
    }

    public static net.minecraft.world.level.block.Block RED_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BANNER.red();
    }

    public static net.minecraft.world.level.block.Block RED_BED() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BED.red();
    }

    public static net.minecraft.world.level.block.Block RED_CARPET() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CARPET.red();
    }

    public static net.minecraft.world.level.block.Block RED_CONCRETE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE.red();
    }

    public static net.minecraft.world.level.block.Block RED_CONCRETE_POWDER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE_POWDER.red();
    }

    public static net.minecraft.world.level.block.Block RED_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.GLAZED_TERRACOTTA.red();
    }

    public static net.minecraft.world.level.block.Block RED_STAINED_GLASS() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS.red();
    }

    public static net.minecraft.world.level.block.Block RED_STAINED_GLASS_PANE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS_PANE.red();
    }

    public static net.minecraft.world.level.block.Block RED_WALL_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WALL_BANNER.red();
    }

    public static net.minecraft.world.level.block.Block RED_WOOL() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WOOL.red();
    }

    public static net.minecraft.world.level.block.Block WHITE_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BANNER.white();
    }

    public static net.minecraft.world.level.block.Block WHITE_BED() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BED.white();
    }

    public static net.minecraft.world.level.block.Block WHITE_CARPET() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CARPET.white();
    }

    public static net.minecraft.world.level.block.Block WHITE_CONCRETE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE.white();
    }

    public static net.minecraft.world.level.block.Block WHITE_CONCRETE_POWDER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE_POWDER.white();
    }

    public static net.minecraft.world.level.block.Block WHITE_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.GLAZED_TERRACOTTA.white();
    }

    public static net.minecraft.world.level.block.Block WHITE_STAINED_GLASS() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS.white();
    }

    public static net.minecraft.world.level.block.Block WHITE_STAINED_GLASS_PANE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS_PANE.white();
    }

    public static net.minecraft.world.level.block.Block WHITE_WALL_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WALL_BANNER.white();
    }

    public static net.minecraft.world.level.block.Block WHITE_WOOL() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WOOL.white();
    }

    public static net.minecraft.world.level.block.Block YELLOW_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BANNER.yellow();
    }

    public static net.minecraft.world.level.block.Block YELLOW_BED() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.BED.yellow();
    }

    public static net.minecraft.world.level.block.Block YELLOW_CARPET() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CARPET.yellow();
    }

    public static net.minecraft.world.level.block.Block YELLOW_CONCRETE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE.yellow();
    }

    public static net.minecraft.world.level.block.Block YELLOW_CONCRETE_POWDER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.CONCRETE_POWDER.yellow();
    }

    public static net.minecraft.world.level.block.Block YELLOW_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.GLAZED_TERRACOTTA.yellow();
    }

    public static net.minecraft.world.level.block.Block YELLOW_STAINED_GLASS() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS.yellow();
    }

    public static net.minecraft.world.level.block.Block YELLOW_STAINED_GLASS_PANE() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.STAINED_GLASS_PANE.yellow();
    }

    public static net.minecraft.world.level.block.Block YELLOW_WALL_BANNER() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WALL_BANNER.yellow();
    }

    public static net.minecraft.world.level.block.Block YELLOW_WOOL() {
        return (net.minecraft.world.level.block.Block) net.minecraft.world.level.block.Blocks.WOOL.yellow();
    }
}

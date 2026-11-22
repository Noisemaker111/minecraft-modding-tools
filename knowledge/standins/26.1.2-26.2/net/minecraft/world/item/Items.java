package mmt.standin.net.minecraft.world.item;

/**
 * 26.1.2's Items on 26.2: its sixteen-color constants.
 *
 * Read from both releases: 26.1.2's Items kept one static field per dye color for each colored set (WHITE_X,
 * ORANGE_X ... BLACK_X). 26.2 keeps each set as one ColorCollection field X, a record with one component per color
 * (white(), orange() ... black(), in DyeColor's order), made from the same sixteen. So each old field is its color's
 * component of the set's collection. Generated from both releases' fields: every old COLOR_X 26.2 dropped where 26.2 has
 * a ColorCollection X.
 *
 * Agrees with NeoForge's 26.1.2 -> 26.2 primer (https://github.com/neoforged/.github/blob/main/primers/26.2/index.md, CC BY 4.0): "ColorCollection - A collection of objects whose variants represent one of sixteen colors" and "picking a specific entry from its collection method".
 */
public final class Items {

    private Items() {}

    public static net.minecraft.world.item.Item BLACK_BANNER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BANNER.black();
    }

    public static net.minecraft.world.item.Item BLACK_BED() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BED.black();
    }

    public static net.minecraft.world.item.Item BLACK_CARPET() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CARPET.black();
    }

    public static net.minecraft.world.item.Item BLACK_CONCRETE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE.black();
    }

    public static net.minecraft.world.item.Item BLACK_CONCRETE_POWDER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE_POWDER.black();
    }

    public static net.minecraft.world.item.Item BLACK_DYE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.DYE.black();
    }

    public static net.minecraft.world.item.Item BLACK_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.GLAZED_TERRACOTTA.black();
    }

    public static net.minecraft.world.item.Item BLACK_HARNESS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.HARNESS.black();
    }

    public static net.minecraft.world.item.Item BLACK_STAINED_GLASS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS.black();
    }

    public static net.minecraft.world.item.Item BLACK_STAINED_GLASS_PANE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS_PANE.black();
    }

    public static net.minecraft.world.item.Item BLACK_WOOL() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.WOOL.black();
    }

    public static net.minecraft.world.item.Item BLUE_BANNER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BANNER.blue();
    }

    public static net.minecraft.world.item.Item BLUE_BED() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BED.blue();
    }

    public static net.minecraft.world.item.Item BLUE_CARPET() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CARPET.blue();
    }

    public static net.minecraft.world.item.Item BLUE_CONCRETE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE.blue();
    }

    public static net.minecraft.world.item.Item BLUE_CONCRETE_POWDER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE_POWDER.blue();
    }

    public static net.minecraft.world.item.Item BLUE_DYE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.DYE.blue();
    }

    public static net.minecraft.world.item.Item BLUE_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.GLAZED_TERRACOTTA.blue();
    }

    public static net.minecraft.world.item.Item BLUE_HARNESS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.HARNESS.blue();
    }

    public static net.minecraft.world.item.Item BLUE_STAINED_GLASS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS.blue();
    }

    public static net.minecraft.world.item.Item BLUE_STAINED_GLASS_PANE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS_PANE.blue();
    }

    public static net.minecraft.world.item.Item BLUE_WOOL() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.WOOL.blue();
    }

    public static net.minecraft.world.item.Item BROWN_BANNER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BANNER.brown();
    }

    public static net.minecraft.world.item.Item BROWN_BED() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BED.brown();
    }

    public static net.minecraft.world.item.Item BROWN_CARPET() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CARPET.brown();
    }

    public static net.minecraft.world.item.Item BROWN_CONCRETE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE.brown();
    }

    public static net.minecraft.world.item.Item BROWN_CONCRETE_POWDER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE_POWDER.brown();
    }

    public static net.minecraft.world.item.Item BROWN_DYE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.DYE.brown();
    }

    public static net.minecraft.world.item.Item BROWN_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.GLAZED_TERRACOTTA.brown();
    }

    public static net.minecraft.world.item.Item BROWN_HARNESS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.HARNESS.brown();
    }

    public static net.minecraft.world.item.Item BROWN_STAINED_GLASS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS.brown();
    }

    public static net.minecraft.world.item.Item BROWN_STAINED_GLASS_PANE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS_PANE.brown();
    }

    public static net.minecraft.world.item.Item BROWN_WOOL() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.WOOL.brown();
    }

    public static net.minecraft.world.item.Item CYAN_BANNER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BANNER.cyan();
    }

    public static net.minecraft.world.item.Item CYAN_BED() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BED.cyan();
    }

    public static net.minecraft.world.item.Item CYAN_CARPET() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CARPET.cyan();
    }

    public static net.minecraft.world.item.Item CYAN_CONCRETE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE.cyan();
    }

    public static net.minecraft.world.item.Item CYAN_CONCRETE_POWDER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE_POWDER.cyan();
    }

    public static net.minecraft.world.item.Item CYAN_DYE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.DYE.cyan();
    }

    public static net.minecraft.world.item.Item CYAN_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.GLAZED_TERRACOTTA.cyan();
    }

    public static net.minecraft.world.item.Item CYAN_HARNESS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.HARNESS.cyan();
    }

    public static net.minecraft.world.item.Item CYAN_STAINED_GLASS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS.cyan();
    }

    public static net.minecraft.world.item.Item CYAN_STAINED_GLASS_PANE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS_PANE.cyan();
    }

    public static net.minecraft.world.item.Item CYAN_WOOL() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.WOOL.cyan();
    }

    public static net.minecraft.world.item.Item GRAY_BANNER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BANNER.gray();
    }

    public static net.minecraft.world.item.Item GRAY_BED() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BED.gray();
    }

    public static net.minecraft.world.item.Item GRAY_CARPET() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CARPET.gray();
    }

    public static net.minecraft.world.item.Item GRAY_CONCRETE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE.gray();
    }

    public static net.minecraft.world.item.Item GRAY_CONCRETE_POWDER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE_POWDER.gray();
    }

    public static net.minecraft.world.item.Item GRAY_DYE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.DYE.gray();
    }

    public static net.minecraft.world.item.Item GRAY_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.GLAZED_TERRACOTTA.gray();
    }

    public static net.minecraft.world.item.Item GRAY_HARNESS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.HARNESS.gray();
    }

    public static net.minecraft.world.item.Item GRAY_STAINED_GLASS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS.gray();
    }

    public static net.minecraft.world.item.Item GRAY_STAINED_GLASS_PANE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS_PANE.gray();
    }

    public static net.minecraft.world.item.Item GRAY_WOOL() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.WOOL.gray();
    }

    public static net.minecraft.world.item.Item GREEN_BANNER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BANNER.green();
    }

    public static net.minecraft.world.item.Item GREEN_BED() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BED.green();
    }

    public static net.minecraft.world.item.Item GREEN_CARPET() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CARPET.green();
    }

    public static net.minecraft.world.item.Item GREEN_CONCRETE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE.green();
    }

    public static net.minecraft.world.item.Item GREEN_CONCRETE_POWDER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE_POWDER.green();
    }

    public static net.minecraft.world.item.Item GREEN_DYE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.DYE.green();
    }

    public static net.minecraft.world.item.Item GREEN_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.GLAZED_TERRACOTTA.green();
    }

    public static net.minecraft.world.item.Item GREEN_HARNESS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.HARNESS.green();
    }

    public static net.minecraft.world.item.Item GREEN_STAINED_GLASS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS.green();
    }

    public static net.minecraft.world.item.Item GREEN_STAINED_GLASS_PANE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS_PANE.green();
    }

    public static net.minecraft.world.item.Item GREEN_WOOL() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.WOOL.green();
    }

    public static net.minecraft.world.item.Item LIGHT_BLUE_BANNER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BANNER.lightBlue();
    }

    public static net.minecraft.world.item.Item LIGHT_BLUE_BED() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BED.lightBlue();
    }

    public static net.minecraft.world.item.Item LIGHT_BLUE_CARPET() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CARPET.lightBlue();
    }

    public static net.minecraft.world.item.Item LIGHT_BLUE_CONCRETE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE.lightBlue();
    }

    public static net.minecraft.world.item.Item LIGHT_BLUE_CONCRETE_POWDER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE_POWDER.lightBlue();
    }

    public static net.minecraft.world.item.Item LIGHT_BLUE_DYE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.DYE.lightBlue();
    }

    public static net.minecraft.world.item.Item LIGHT_BLUE_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.GLAZED_TERRACOTTA.lightBlue();
    }

    public static net.minecraft.world.item.Item LIGHT_BLUE_HARNESS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.HARNESS.lightBlue();
    }

    public static net.minecraft.world.item.Item LIGHT_BLUE_STAINED_GLASS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS.lightBlue();
    }

    public static net.minecraft.world.item.Item LIGHT_BLUE_STAINED_GLASS_PANE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS_PANE.lightBlue();
    }

    public static net.minecraft.world.item.Item LIGHT_BLUE_WOOL() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.WOOL.lightBlue();
    }

    public static net.minecraft.world.item.Item LIGHT_GRAY_BANNER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BANNER.lightGray();
    }

    public static net.minecraft.world.item.Item LIGHT_GRAY_BED() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BED.lightGray();
    }

    public static net.minecraft.world.item.Item LIGHT_GRAY_CARPET() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CARPET.lightGray();
    }

    public static net.minecraft.world.item.Item LIGHT_GRAY_CONCRETE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE.lightGray();
    }

    public static net.minecraft.world.item.Item LIGHT_GRAY_CONCRETE_POWDER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE_POWDER.lightGray();
    }

    public static net.minecraft.world.item.Item LIGHT_GRAY_DYE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.DYE.lightGray();
    }

    public static net.minecraft.world.item.Item LIGHT_GRAY_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.GLAZED_TERRACOTTA.lightGray();
    }

    public static net.minecraft.world.item.Item LIGHT_GRAY_HARNESS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.HARNESS.lightGray();
    }

    public static net.minecraft.world.item.Item LIGHT_GRAY_STAINED_GLASS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS.lightGray();
    }

    public static net.minecraft.world.item.Item LIGHT_GRAY_STAINED_GLASS_PANE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS_PANE.lightGray();
    }

    public static net.minecraft.world.item.Item LIGHT_GRAY_WOOL() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.WOOL.lightGray();
    }

    public static net.minecraft.world.item.Item LIME_BANNER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BANNER.lime();
    }

    public static net.minecraft.world.item.Item LIME_BED() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BED.lime();
    }

    public static net.minecraft.world.item.Item LIME_CARPET() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CARPET.lime();
    }

    public static net.minecraft.world.item.Item LIME_CONCRETE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE.lime();
    }

    public static net.minecraft.world.item.Item LIME_CONCRETE_POWDER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE_POWDER.lime();
    }

    public static net.minecraft.world.item.Item LIME_DYE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.DYE.lime();
    }

    public static net.minecraft.world.item.Item LIME_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.GLAZED_TERRACOTTA.lime();
    }

    public static net.minecraft.world.item.Item LIME_HARNESS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.HARNESS.lime();
    }

    public static net.minecraft.world.item.Item LIME_STAINED_GLASS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS.lime();
    }

    public static net.minecraft.world.item.Item LIME_STAINED_GLASS_PANE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS_PANE.lime();
    }

    public static net.minecraft.world.item.Item LIME_WOOL() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.WOOL.lime();
    }

    public static net.minecraft.world.item.Item MAGENTA_BANNER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BANNER.magenta();
    }

    public static net.minecraft.world.item.Item MAGENTA_BED() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BED.magenta();
    }

    public static net.minecraft.world.item.Item MAGENTA_CARPET() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CARPET.magenta();
    }

    public static net.minecraft.world.item.Item MAGENTA_CONCRETE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE.magenta();
    }

    public static net.minecraft.world.item.Item MAGENTA_CONCRETE_POWDER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE_POWDER.magenta();
    }

    public static net.minecraft.world.item.Item MAGENTA_DYE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.DYE.magenta();
    }

    public static net.minecraft.world.item.Item MAGENTA_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.GLAZED_TERRACOTTA.magenta();
    }

    public static net.minecraft.world.item.Item MAGENTA_HARNESS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.HARNESS.magenta();
    }

    public static net.minecraft.world.item.Item MAGENTA_STAINED_GLASS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS.magenta();
    }

    public static net.minecraft.world.item.Item MAGENTA_STAINED_GLASS_PANE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS_PANE.magenta();
    }

    public static net.minecraft.world.item.Item MAGENTA_WOOL() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.WOOL.magenta();
    }

    public static net.minecraft.world.item.Item ORANGE_BANNER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BANNER.orange();
    }

    public static net.minecraft.world.item.Item ORANGE_BED() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BED.orange();
    }

    public static net.minecraft.world.item.Item ORANGE_CARPET() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CARPET.orange();
    }

    public static net.minecraft.world.item.Item ORANGE_CONCRETE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE.orange();
    }

    public static net.minecraft.world.item.Item ORANGE_CONCRETE_POWDER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE_POWDER.orange();
    }

    public static net.minecraft.world.item.Item ORANGE_DYE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.DYE.orange();
    }

    public static net.minecraft.world.item.Item ORANGE_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.GLAZED_TERRACOTTA.orange();
    }

    public static net.minecraft.world.item.Item ORANGE_HARNESS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.HARNESS.orange();
    }

    public static net.minecraft.world.item.Item ORANGE_STAINED_GLASS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS.orange();
    }

    public static net.minecraft.world.item.Item ORANGE_STAINED_GLASS_PANE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS_PANE.orange();
    }

    public static net.minecraft.world.item.Item ORANGE_WOOL() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.WOOL.orange();
    }

    public static net.minecraft.world.item.Item PINK_BANNER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BANNER.pink();
    }

    public static net.minecraft.world.item.Item PINK_BED() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BED.pink();
    }

    public static net.minecraft.world.item.Item PINK_CARPET() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CARPET.pink();
    }

    public static net.minecraft.world.item.Item PINK_CONCRETE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE.pink();
    }

    public static net.minecraft.world.item.Item PINK_CONCRETE_POWDER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE_POWDER.pink();
    }

    public static net.minecraft.world.item.Item PINK_DYE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.DYE.pink();
    }

    public static net.minecraft.world.item.Item PINK_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.GLAZED_TERRACOTTA.pink();
    }

    public static net.minecraft.world.item.Item PINK_HARNESS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.HARNESS.pink();
    }

    public static net.minecraft.world.item.Item PINK_STAINED_GLASS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS.pink();
    }

    public static net.minecraft.world.item.Item PINK_STAINED_GLASS_PANE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS_PANE.pink();
    }

    public static net.minecraft.world.item.Item PINK_WOOL() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.WOOL.pink();
    }

    public static net.minecraft.world.item.Item PURPLE_BANNER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BANNER.purple();
    }

    public static net.minecraft.world.item.Item PURPLE_BED() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BED.purple();
    }

    public static net.minecraft.world.item.Item PURPLE_CARPET() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CARPET.purple();
    }

    public static net.minecraft.world.item.Item PURPLE_CONCRETE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE.purple();
    }

    public static net.minecraft.world.item.Item PURPLE_CONCRETE_POWDER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE_POWDER.purple();
    }

    public static net.minecraft.world.item.Item PURPLE_DYE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.DYE.purple();
    }

    public static net.minecraft.world.item.Item PURPLE_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.GLAZED_TERRACOTTA.purple();
    }

    public static net.minecraft.world.item.Item PURPLE_HARNESS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.HARNESS.purple();
    }

    public static net.minecraft.world.item.Item PURPLE_STAINED_GLASS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS.purple();
    }

    public static net.minecraft.world.item.Item PURPLE_STAINED_GLASS_PANE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS_PANE.purple();
    }

    public static net.minecraft.world.item.Item PURPLE_WOOL() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.WOOL.purple();
    }

    public static net.minecraft.world.item.Item RED_BANNER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BANNER.red();
    }

    public static net.minecraft.world.item.Item RED_BED() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BED.red();
    }

    public static net.minecraft.world.item.Item RED_CARPET() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CARPET.red();
    }

    public static net.minecraft.world.item.Item RED_CONCRETE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE.red();
    }

    public static net.minecraft.world.item.Item RED_CONCRETE_POWDER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE_POWDER.red();
    }

    public static net.minecraft.world.item.Item RED_DYE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.DYE.red();
    }

    public static net.minecraft.world.item.Item RED_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.GLAZED_TERRACOTTA.red();
    }

    public static net.minecraft.world.item.Item RED_HARNESS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.HARNESS.red();
    }

    public static net.minecraft.world.item.Item RED_STAINED_GLASS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS.red();
    }

    public static net.minecraft.world.item.Item RED_STAINED_GLASS_PANE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS_PANE.red();
    }

    public static net.minecraft.world.item.Item RED_WOOL() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.WOOL.red();
    }

    public static net.minecraft.world.item.Item WHITE_BANNER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BANNER.white();
    }

    public static net.minecraft.world.item.Item WHITE_BED() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BED.white();
    }

    public static net.minecraft.world.item.Item WHITE_CARPET() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CARPET.white();
    }

    public static net.minecraft.world.item.Item WHITE_CONCRETE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE.white();
    }

    public static net.minecraft.world.item.Item WHITE_CONCRETE_POWDER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE_POWDER.white();
    }

    public static net.minecraft.world.item.Item WHITE_DYE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.DYE.white();
    }

    public static net.minecraft.world.item.Item WHITE_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.GLAZED_TERRACOTTA.white();
    }

    public static net.minecraft.world.item.Item WHITE_HARNESS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.HARNESS.white();
    }

    public static net.minecraft.world.item.Item WHITE_STAINED_GLASS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS.white();
    }

    public static net.minecraft.world.item.Item WHITE_STAINED_GLASS_PANE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS_PANE.white();
    }

    public static net.minecraft.world.item.Item WHITE_WOOL() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.WOOL.white();
    }

    public static net.minecraft.world.item.Item YELLOW_BANNER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BANNER.yellow();
    }

    public static net.minecraft.world.item.Item YELLOW_BED() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.BED.yellow();
    }

    public static net.minecraft.world.item.Item YELLOW_CARPET() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CARPET.yellow();
    }

    public static net.minecraft.world.item.Item YELLOW_CONCRETE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE.yellow();
    }

    public static net.minecraft.world.item.Item YELLOW_CONCRETE_POWDER() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.CONCRETE_POWDER.yellow();
    }

    public static net.minecraft.world.item.Item YELLOW_DYE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.DYE.yellow();
    }

    public static net.minecraft.world.item.Item YELLOW_GLAZED_TERRACOTTA() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.GLAZED_TERRACOTTA.yellow();
    }

    public static net.minecraft.world.item.Item YELLOW_HARNESS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.HARNESS.yellow();
    }

    public static net.minecraft.world.item.Item YELLOW_STAINED_GLASS() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS.yellow();
    }

    public static net.minecraft.world.item.Item YELLOW_STAINED_GLASS_PANE() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.STAINED_GLASS_PANE.yellow();
    }

    public static net.minecraft.world.item.Item YELLOW_WOOL() {
        return (net.minecraft.world.item.Item) net.minecraft.world.item.Items.WOOL.yellow();
    }
}

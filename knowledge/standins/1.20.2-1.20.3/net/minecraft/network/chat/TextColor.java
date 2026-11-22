package mmt.standin.net.minecraft.network.chat;

/**
 * 1.20.2's TextColor on 1.20.3.
 *
 * Read from both releases: 1.20.2's parseColor(text) read "#RRGGBB" as an RGB color (fromRgb of the hex after "#", null
 * when it was no hex number) and anything else as a named color (null when none had that name). 1.20.3's parseColor
 * returns a DataResult, an error where 1.20.2 returned null, and also refuses a hex value above 0xFFFFFF. So the old method
 * reads a hex color as 1.20.2 did (fromRgb, which 1.20.3 keeps) and a name through the new parseColor's result, null when
 * it fails.
 *
 * NeoForge's primers begin at 1.20.4 -> 1.20.5, and Fabric's 1.20.3 post (https://fabricmc.net/2023/11/30/1203.html)
 * says text "got a codec, and now is serialized using it"; this is from the game's code.
 */
public final class TextColor {

    private TextColor() {}

    public static net.minecraft.network.chat.TextColor parseColor(String text) {
        if (text.startsWith("#")) {
            try {
                return net.minecraft.network.chat.TextColor.fromRgb(Integer.parseInt(text.substring(1), 16));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return (net.minecraft.network.chat.TextColor) net.minecraft.network.chat.TextColor.parseColor(text).result().orElse(null);
    }
}

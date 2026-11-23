package mmt.standin.net.minecraft.client.gui.screens;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import java.net.URI;
import java.net.URISyntaxException;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;

/**
 * 26.2's ConfirmLinkScreen on 26.3.
 *
 * Read from both releases: 26.2's confirmLinkNow(parent, uri, trusted) took the link as text, asked the player and
 * opened it (Util.OS.openUri(String), which logged and opened nothing when the text was no URI); confirmLinkNow(parent,
 * uri) was the same, trusted. 26.3 keeps only the URI forms, which ask and open the same way. So the old methods are the
 * URI forms for the parsed text, logging as 26.2 did when it is no URI.
 * 26.2's constructor (callback, url, trusted) showed the link's text under the confirm message and kept it for copying;
 * 26.3's (callback, uri, trusted) shows and copies uri.toString(), so it is made from the text read as a URI (a mod's
 * links are URLs; text that is none is refused as URI.create refuses it).
 *
 * Agrees with NeoForge's 26.2 -> 26.3 primer (https://github.com/neoforged/.github/blob/main/primers/26.3/index.md, CC BY 4.0): "ConfirmLinkScreen now takes in a URI instead of a String", "confirmLinkNow methods that take in a String for the URI are removed".
 */
public final class ConfirmLinkScreen {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ConfirmLinkScreen() {}

    public static void confirmLinkNow(Screen parent, String uri, boolean trusted) {
        try {
            net.minecraft.client.gui.screens.ConfirmLinkScreen.confirmLinkNow(parent, new URI(uri), trusted);
        } catch (URISyntaxException e) {
            LOGGER.error("Couldn't open uri '{}'", uri, e);
        }
    }

    public static void confirmLinkNow(Screen parent, String uri) {
        confirmLinkNow(parent, uri, true);
    }

    public static net.minecraft.client.gui.screens.ConfirmLinkScreen init(BooleanConsumer callback, String url, boolean trusted) {
        return new net.minecraft.client.gui.screens.ConfirmLinkScreen(callback, URI.create(url), trusted);
    }
}

package mmt.standin.net.minecraft.util;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.logging.LogUtils;
import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import net.minecraft.util.Util;
import org.slf4j.Logger;

/**
 * 26.2's Util.OS on 26.3.
 *
 * Read from both releases: 26.2's Util.OS opened a link or file with the platform's own command (rundll32 on Windows,
 * open on macOS, xdg-open elsewhere): openUri(String) parsed the text as a URI (logging "Couldn't open uri" when it was
 * not one) and opened it, and openFile(File) opened the file's URI. 26.3 opens through SDL instead
 * (Blaze3D.openUri(URI), Blaze3D.openPath(Path)) and drops both. So each old method opens the same URI with
 * Blaze3D.openUri, with 26.2's log for text that is no URI.
 *
 * Agrees with NeoForge's 26.2 -> 26.3 primer (https://github.com/neoforged/.github/blob/main/primers/26.3/index.md, CC BY 4.0): "Util$OS: openUri -> Blaze3D#openUri, openPath -> Blaze3D#openPath", and whose list of removals names "Util$OS#openFile".
 */
public final class Util$OS {

    private static final Logger LOGGER = LogUtils.getLogger();

    private Util$OS() {}

    public static void openUri(Util.OS self, String uri) {
        try {
            Blaze3D.openUri(new URI(uri));
        } catch (IllegalArgumentException | URISyntaxException e) {
            LOGGER.error("Couldn't open uri '{}'", uri, e);
        }
    }

    public static void openFile(Util.OS self, File file) {
        Blaze3D.openUri(file.toURI());
    }
}

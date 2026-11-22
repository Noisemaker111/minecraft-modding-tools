package mmt.standin.net.minecraft;

import com.mojang.logging.LogUtils;
import java.net.URL;
import net.minecraft.Util;
import org.slf4j.Logger;

/**
 * 1.20.6's Util.OS on 1.21.
 *
 * Read from both releases: 1.20.6's OS.openUrl(url) ran the system's open command for the URL, logging "Couldn't open
 * url" on failure, and openUri(uri) went through it. 1.21 opens URIs only (OS.openUri(URI), with openUri(String) and
 * openFile), the same command for the URI.
 *
 * Game files only: the primer does not list it.
 */
public final class Util$OS {

    private static final Logger LOGGER = LogUtils.getLogger();

    private Util$OS() {}

    public static void openUrl(Util.OS self, URL url) {
        try {
            self.openUri(url.toURI());
        } catch (Exception e) {
            LOGGER.error("Couldn't open url '{}'", url, e);
        }
    }
}

package mmt.standin.net.minecraft;

import net.minecraft.server.packs.PackType;

/**
 * 1.19.4's WorldVersion.getPackVersion(PackType) on 1.19.3, for a port back.
 *
 * Read from both releases: 1.19.4's DetectedVersion answers getPackVersion(type) with its data pack version for
 * SERVER_DATA and its resource pack version for CLIENT_RESOURCES. 1.19.3's version is a javabridge GameVersion
 * (javabridge 2.0.25) with getPackVersion(bridge PackType), and 1.19.3's PackType.getVersion(version) asks it for the
 * pack type's own bridge type (RESOURCE for CLIENT_RESOURCES, DATA for SERVER_DATA). So it is type.getVersion(self).
 */
public final class WorldVersion {

    private WorldVersion() {
    }

    public static int getPackVersion(net.minecraft.WorldVersion self, PackType type) {
        return type.getVersion(self);
    }
}

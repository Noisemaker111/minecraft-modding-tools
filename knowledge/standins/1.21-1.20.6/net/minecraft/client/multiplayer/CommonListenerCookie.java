package mmt.standin.net.minecraft.client.multiplayer;

/**
 * 1.21's CommonListenerCookie(..., customReportDetails, serverLinks) on 1.20.6, for a port back: 1.20.6's cookie from
 * the ten values it shares.
 *
 * Read from both releases: 1.21's record adds the server's custom crash-report details and its links to 1.20.6's ten
 * fields (profile, telemetry, registries, features, brand, server data, disconnect screen, cookies, chat state, strict
 * error handling); 1.20.6's server sends neither, so neither is kept.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`net.minecraft.server.MinecraftServer#serverLinks` - A list of entries indicating what the link coming from the server is".
 */
public final class CommonListenerCookie {

    private CommonListenerCookie() {}

    public static net.minecraft.client.multiplayer.CommonListenerCookie init(com.mojang.authlib.GameProfile profile, net.minecraft.client.telemetry.WorldSessionTelemetryManager telemetry, net.minecraft.core.RegistryAccess.Frozen registries, net.minecraft.world.flag.FeatureFlagSet features, String brand, net.minecraft.client.multiplayer.ServerData server, net.minecraft.client.gui.screens.Screen afterDisconnect, java.util.Map cookies, net.minecraft.client.gui.components.ChatComponent.State chat, boolean strictErrors, java.util.Map reportDetails, mmt.standin.net.minecraft.server.ServerLinks links) {
        return new net.minecraft.client.multiplayer.CommonListenerCookie(profile, telemetry, registries, features, brand, server, afterDisconnect, cookies, chat, strictErrors);
    }
}

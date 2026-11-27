package mmt.standin.net.minecraft.client.multiplayer;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientRegistryLayer;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.telemetry.WorldSessionTelemetryManager;
import net.minecraft.network.Connection;
import net.minecraft.world.flag.FeatureFlags;

/**
 * 1.20.1's ClientPacketListener constructor on 1.20.2, as the old class mods extend and make.
 *
 * Read from both releases: 1.20.1's ClientPacketListener(minecraft, callbackScreen, connection, serverData, profile,
 * telemetry) kept those, and started with the client's built-in registries (ClientRegistryLayer.createRegistryAccess()),
 * the default feature flags (FeatureFlags.DEFAULT_FLAGS) and no server brand, which the login packet filled in later.
 * 1.20.2's ClientPacketListener(minecraft, connection, cookie) takes what came before play in a CommonListenerCookie
 * (localGameProfile, telemetryManager, receivedRegistries, enabledFeatures, serverBrand, serverData, postDisconnectScreen),
 * the screen to go back to being the old callback screen. So the old constructor is the new one given a cookie of the
 * same starting values, the registries as one frozen access (compositeAccess(), as the login packet's handling read
 * them).
 *
 * Agrees with Fabric's 1.20.2 post (https://fabricmc.net/2023/09/12/1202.html), on the stage now between login and play:
 * "In 1.20.2, after the player authenticates, but before the player joins the world ..., there exist a phase where the
 * client and the server can communicate to configure each other."
 */
public class ClientPacketListener extends net.minecraft.client.multiplayer.ClientPacketListener {

    public ClientPacketListener(Minecraft minecraft, Screen callbackScreen, Connection connection, ServerData serverData, GameProfile profile, WorldSessionTelemetryManager telemetry) {
        super(minecraft, connection, cookie(callbackScreen, serverData, profile, telemetry));
    }

    public static net.minecraft.client.multiplayer.ClientPacketListener init(Minecraft minecraft, Screen callbackScreen, Connection connection, ServerData serverData, GameProfile profile, WorldSessionTelemetryManager telemetry) {
        return new ClientPacketListener(minecraft, callbackScreen, connection, serverData, profile, telemetry);
    }

    private static CommonListenerCookie cookie(Screen callbackScreen, ServerData serverData, GameProfile profile, WorldSessionTelemetryManager telemetry) {
        return new CommonListenerCookie(profile, telemetry, ClientRegistryLayer.createRegistryAccess().compositeAccess(), FeatureFlags.DEFAULT_FLAGS, null, serverData, callbackScreen);
    }
}

package mmt.standin.net.minecraft.client.gui.screens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * 1.16.5's ConnectScreen constructors on 1.17.
 *
 * Read from both releases: 1.16.5's new ConnectScreen(parent, minecraft, serverData) parsed the server's address, cleared
 * the level, set the current server and started connecting on a thread of its own; the (parent, minecraft, host, port)
 * form did the same without a server entry; the caller then showed the screen. 1.17 made the constructor private: the
 * static startConnecting(parent, minecraft, ServerAddress, ServerData) makes the screen, clears the level, sets the
 * current server, shows the screen and connects. This calls it and returns the screen it showed, which the caller then
 * shows again as before.
 *
 * Fabric's 1.17 post for developers (https://fabricmc.net/2021/05/27/117-for-developers.html) covers Java 16, Fabric API and
 * Yarn, not this; this is from the game's code alone.
 */
public final class ConnectScreen {

    private ConnectScreen() {}

    public static net.minecraft.client.gui.screens.ConnectScreen init(Screen parent, Minecraft minecraft, ServerData server) {
        net.minecraft.client.gui.screens.ConnectScreen.startConnecting(parent, minecraft, ServerAddress.parseString(server.ip), server);
        return (net.minecraft.client.gui.screens.ConnectScreen) minecraft.screen;
    }

    public static net.minecraft.client.gui.screens.ConnectScreen init(Screen parent, Minecraft minecraft, String host, int port) {
        net.minecraft.client.gui.screens.ConnectScreen.startConnecting(parent, minecraft, new ServerAddress(host, port), null);
        return (net.minecraft.client.gui.screens.ConnectScreen) minecraft.screen;
    }
}

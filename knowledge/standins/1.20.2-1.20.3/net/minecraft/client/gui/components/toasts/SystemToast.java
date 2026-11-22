package mmt.standin.net.minecraft.client.gui.components.toasts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;

/**
 * 1.20.2's SystemToast on 1.20.3.
 *
 * Read from both releases: 1.20.2's SystemToast took its kind as a SystemToastIds constant (its constructor, multiline,
 * add, addOrUpdate) and returned it as its token (getToken()). 1.20.3's SystemToast has the same members taking and
 * returning a SystemToastId, the class that replaced the enum. So each old member is the new one for the old constant's
 * id (SystemToast$SystemToastIds), and getToken() the constant whose id the toast carries (null for an id no old
 * constant names, which 1.20.2 could not make).
 *
 * NeoForge's primers begin at 1.20.4 -> 1.20.5, and Fabric's 1.20.3 post (https://fabricmc.net/2023/11/30/1203.html)
 * does not mention system toasts; this is from the game's code alone.
 */
public final class SystemToast {

    private SystemToast() {}

    public static net.minecraft.client.gui.components.toasts.SystemToast init(SystemToast$SystemToastIds id, Component title, Component message) {
        return new net.minecraft.client.gui.components.toasts.SystemToast(id.id, title, message);
    }

    public static net.minecraft.client.gui.components.toasts.SystemToast multiline(Minecraft minecraft, SystemToast$SystemToastIds id, Component title, Component message) {
        return net.minecraft.client.gui.components.toasts.SystemToast.multiline(minecraft, id.id, title, message);
    }

    public static SystemToast$SystemToastIds getToken(net.minecraft.client.gui.components.toasts.SystemToast self) {
        return SystemToast$SystemToastIds.of(self.getToken());
    }

    public static void add(ToastComponent toasts, SystemToast$SystemToastIds id, Component title, Component message) {
        net.minecraft.client.gui.components.toasts.SystemToast.add(toasts, id.id, title, message);
    }

    public static void addOrUpdate(ToastComponent toasts, SystemToast$SystemToastIds id, Component title, Component message) {
        net.minecraft.client.gui.components.toasts.SystemToast.addOrUpdate(toasts, id.id, title, message);
    }
}

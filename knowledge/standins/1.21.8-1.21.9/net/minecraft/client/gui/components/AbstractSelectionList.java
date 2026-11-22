package mmt.standin.net.minecraft.client.gui.components;

/**
 * 1.21.8's AbstractSelectionList on 1.21.9.
 *
 * Read from both releases: 1.21.8's getEntry(index) was children().get(index). 1.21.9 dropped it; children() is still
 * the list's entries in order.
 *
 * Agrees with NeoForge's 1.21.8 -> 1.21.9 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.9/index.md, CC BY 4.0), which lists AbstractSelectionList's getEntry among the removals.
 */
public final class AbstractSelectionList {

    private AbstractSelectionList() {}

    public static net.minecraft.client.gui.components.AbstractSelectionList.Entry getEntry(net.minecraft.client.gui.components.AbstractSelectionList self, int index) {
        return (net.minecraft.client.gui.components.AbstractSelectionList.Entry) self.children().get(index);
    }
}

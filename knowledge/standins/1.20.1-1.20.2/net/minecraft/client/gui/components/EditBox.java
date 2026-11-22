package mmt.standin.net.minecraft.client.gui.components;

import net.minecraft.client.gui.screens.Screen;

/**
 * 1.20.1's EditBox cursor moves on 1.20.2.
 *
 * Read from both releases: 1.20.1's moveCursorTo(pos) set the cursor and, unless the box's shiftPressed field was set,
 * moved the selection's other end there too; keyPressed set that field to Screen.hasShiftDown() on every key, so a move
 * extended the selection while shift was held. moveCursorToStart() and moveCursorToEnd() were moveCursorTo(0) and
 * moveCursorTo(length). 1.20.2 dropped the field: moveCursorTo(pos, extendSelection), moveCursorToStart(extend) and
 * moveCursorToEnd(extend) take it, and its own key handling passes Screen.hasShiftDown().
 *
 * NeoForge's primers begin at 1.20.2 -> 1.20.3, and Fabric's 1.20.2 post (https://fabricmc.net/2023/09/12/1202.html) does not mention EditBox; this is from the game's code alone.
 */
public final class EditBox {

    private EditBox() {}

    public static void moveCursorTo(net.minecraft.client.gui.components.EditBox self, int pos) {
        self.moveCursorTo(pos, Screen.hasShiftDown());
    }

    public static void moveCursorToStart(net.minecraft.client.gui.components.EditBox self) {
        self.moveCursorToStart(Screen.hasShiftDown());
    }

    public static void moveCursorToEnd(net.minecraft.client.gui.components.EditBox self) {
        self.moveCursorToEnd(Screen.hasShiftDown());
    }
}

package mmt.standin.net.minecraft.client.gui.components;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 1.21.11's EditBox on 26.1.
 *
 * Read from both releases: 1.21.11's EditBox kept a filter (setFilter(Predicate), Objects::nonNull by default) and
 * every edit tested the text it would make first: setValue, insertText and deleteCharsToPos changed nothing, and told the
 * responder nothing, when the filter refused it. 26.1 has no filter; every edit sets the text, then onValueChange hands it
 * to the responder. So setFilter puts a gate in front of the box's responder: an edit the filter refuses puts back the
 * text, cursor and selection the box had, and the responder hears nothing, as in 1.21.11. A responder set after the
 * filter replaces the gate, so the filter holds from then on only through the order 1.21.11 mods set them in most often
 * (responder first).
 *
 * Agrees with NeoForge's 1.21.11 -> 26.1 primer (https://github.com/neoforged/.github/blob/main/primers/26.1/index.md, CC BY 4.0), whose list of removals names "net.minecraft.client.gui.components.EditBox#setFilter".
 */
public final class EditBox {

    private static final Map<net.minecraft.client.gui.components.EditBox, Gate> GATES = Collections.synchronizedMap(new WeakHashMap<>());

    private EditBox() {}

    public static void setFilter(net.minecraft.client.gui.components.EditBox self, Predicate filter) {
        Gate gate = GATES.get(self);
        if (gate == null || self.responder != gate) {
            gate = new Gate(self, self.responder);
            GATES.put(self, gate);
            self.responder = gate;
        }
        gate.filter = filter;
    }

    static final class Gate implements Consumer {
        final net.minecraft.client.gui.components.EditBox box;
        final Consumer next;
        Predicate filter;
        String value;
        int cursor;
        int highlight;

        Gate(net.minecraft.client.gui.components.EditBox box, Consumer next) {
            this.box = box;
            this.next = next;
            keep();
        }

        void keep() {
            value = box.getValue();
            cursor = box.getCursorPosition();
            highlight = box.highlightPos;
        }

        @Override
        public void accept(Object text) {
            if (!filter.test(box.getValue())) {
                box.value = value;
                box.setCursorPosition(cursor);
                box.setHighlightPos(highlight);
                return;
            }
            keep();
            if (next != null) {
                next.accept(text);
            }
        }
    }
}

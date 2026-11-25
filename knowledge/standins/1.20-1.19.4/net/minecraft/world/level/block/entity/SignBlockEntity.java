package mmt.standin.net.minecraft.world.level.block.entity;

import java.util.function.UnaryOperator;
import net.minecraft.world.entity.player.Player;

/**
 * 1.20's two-sided sign text on 1.19.4's one-sided sign.
 *
 * Read from both releases: 1.20's SignBlockEntity holds a front and a back SignText and a waxed flag; 1.19.4's has one
 * side, whose lines, filtered lines, color and glow are the fields 1.20 moved into SignText, and no waxing. So the
 * front is the sign's one side, read into a SignText and written back line by line (setMessage(line, text, filtered),
 * setColor, setHasGlowingText, then setChanged and a block update, as 1.20's markUpdated does). The back of a 1.19.4
 * sign is what 1.20 gives a side nothing was written on (new SignText()), and writing it changes nothing: there is no
 * back to write. A 1.19.4 sign is never waxed, so isWaxed is false and setWaxed changes nothing; every player faces the
 * front.
 */
public final class SignBlockEntity {

    private SignBlockEntity() {
    }

    public static SignText getFrontText(net.minecraft.world.level.block.entity.SignBlockEntity self) {
        net.minecraft.network.chat.Component[] lines = new net.minecraft.network.chat.Component[SignText.LINES];
        net.minecraft.network.chat.Component[] filtered = new net.minecraft.network.chat.Component[SignText.LINES];
        for (int i = 0; i < SignText.LINES; i++) {
            lines[i] = self.getMessage(i, false);
            filtered[i] = self.getMessage(i, true);
        }
        return new SignText(lines, filtered, self.getColor(), self.hasGlowingText());
    }

    public static SignText getBackText(net.minecraft.world.level.block.entity.SignBlockEntity self) {
        return new SignText();
    }

    public static SignText getText(net.minecraft.world.level.block.entity.SignBlockEntity self, boolean front) {
        return front ? getFrontText(self) : getBackText(self);
    }

    public static boolean isFacingFrontText(net.minecraft.world.level.block.entity.SignBlockEntity self, Player player) {
        return true;
    }

    public static SignText getTextFacingPlayer(net.minecraft.world.level.block.entity.SignBlockEntity self, Player player) {
        return getFrontText(self);
    }

    public static boolean setText(net.minecraft.world.level.block.entity.SignBlockEntity self, SignText text, boolean front) {
        if (!front) {
            return false;
        }
        for (int i = 0; i < SignText.LINES; i++) {
            self.setMessage(i, text.getMessage(i, false), text.getMessage(i, true));
        }
        self.setColor(text.getColor());
        self.setHasGlowingText(text.hasGlowingText());
        self.setChanged();
        if (self.getLevel() != null) {
            self.getLevel().sendBlockUpdated(self.getBlockPos(), self.getBlockState(), self.getBlockState(), 3);
        }
        return true;
    }

    public static boolean updateText(net.minecraft.world.level.block.entity.SignBlockEntity self, UnaryOperator operator, boolean front) {
        SignText text = getText(self, front);
        return setText(self, (SignText) operator.apply(text), front);
    }

    public static boolean isWaxed(net.minecraft.world.level.block.entity.SignBlockEntity self) {
        return false;
    }

    public static boolean setWaxed(net.minecraft.world.level.block.entity.SignBlockEntity self, boolean waxed) {
        return false;
    }
}

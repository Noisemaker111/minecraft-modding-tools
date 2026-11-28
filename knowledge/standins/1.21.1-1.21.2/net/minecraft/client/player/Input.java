package mmt.standin.net.minecraft.client.player;

import net.minecraft.client.player.ClientInput;

/**
 * 1.21.1's Input (renamed ClientInput in 1.21.2) on 1.21.2.
 *
 * Read from both releases: 1.21.1's Input kept each movement key as a public boolean field (up, down, left, right,
 * jumping, shiftKeyDown), set by KeyboardInput.tick. 1.21.2 keeps them in one record, ClientInput.keyPresses
 * (net.minecraft.world.entity.player.Input: forward, backward, left, right, jump, shift, sprint), which KeyboardInput.tick
 * replaces whole each tick and makeJump() copies with one value changed. A field read is the record's component; a write
 * is a copy of the record with that component changed, as makeJump writes jump.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "Input -> ClientInput and net.minecraft.world.entity.player.Input".
 */
public final class Input {

    private Input() {}

    public static boolean up(ClientInput self) {
        return self.keyPresses.forward();
    }

    public static boolean down(ClientInput self) {
        return self.keyPresses.backward();
    }

    public static boolean left(ClientInput self) {
        return self.keyPresses.left();
    }

    public static boolean right(ClientInput self) {
        return self.keyPresses.right();
    }

    public static void up(ClientInput self, boolean value) {
        net.minecraft.world.entity.player.Input k = self.keyPresses;
        self.keyPresses = new net.minecraft.world.entity.player.Input(value, k.backward(), k.left(), k.right(), k.jump(), k.shift(), k.sprint());
    }

    public static void down(ClientInput self, boolean value) {
        net.minecraft.world.entity.player.Input k = self.keyPresses;
        self.keyPresses = new net.minecraft.world.entity.player.Input(k.forward(), value, k.left(), k.right(), k.jump(), k.shift(), k.sprint());
    }

    public static void left(ClientInput self, boolean value) {
        net.minecraft.world.entity.player.Input k = self.keyPresses;
        self.keyPresses = new net.minecraft.world.entity.player.Input(k.forward(), k.backward(), value, k.right(), k.jump(), k.shift(), k.sprint());
    }

    public static void right(ClientInput self, boolean value) {
        net.minecraft.world.entity.player.Input k = self.keyPresses;
        self.keyPresses = new net.minecraft.world.entity.player.Input(k.forward(), k.backward(), k.left(), value, k.jump(), k.shift(), k.sprint());
    }

    public static boolean jumping(ClientInput self) {
        return self.keyPresses.jump();
    }

    public static boolean shiftKeyDown(ClientInput self) {
        return self.keyPresses.shift();
    }

    public static void jumping(ClientInput self, boolean value) {
        net.minecraft.world.entity.player.Input k = self.keyPresses;
        self.keyPresses = new net.minecraft.world.entity.player.Input(k.forward(), k.backward(), k.left(), k.right(), value, k.shift(), k.sprint());
    }

    public static void shiftKeyDown(ClientInput self, boolean value) {
        net.minecraft.world.entity.player.Input k = self.keyPresses;
        self.keyPresses = new net.minecraft.world.entity.player.Input(k.forward(), k.backward(), k.left(), k.right(), k.jump(), value, k.sprint());
    }
}

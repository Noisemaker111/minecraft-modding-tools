package mmt.standin.net.minecraft.client.player;

import net.minecraft.world.phys.Vec2;

/**
 * 1.21.4's ClientInput on 1.21.5.
 *
 * Read from both releases: 1.21.4 kept the sideways and forward movement in the fields leftImpulse and forwardImpulse
 * (getMoveVector() was new Vec2(leftImpulse, forwardImpulse)); KeyboardInput.tick wrote them each tick and the player
 * read them. 1.21.5 keeps both in the protected Vec2 moveVector (x sideways, y forward), which KeyboardInput.tick writes
 * and getMoveVector() returns.
 *
 * Agrees with NeoForge's 1.21.4 -> 1.21.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.5/index.md, CC BY 4.0): "ClientInput#leftImpulse, forwardImpulse -> moveVector, now protected".
 */
public final class ClientInput {

    private ClientInput() {}

    public static float leftImpulse(net.minecraft.client.player.ClientInput self) {
        return self.getMoveVector().x;
    }

    public static void leftImpulse(net.minecraft.client.player.ClientInput self, float value) {
        self.moveVector = new Vec2(value, self.moveVector.y);
    }

    public static float forwardImpulse(net.minecraft.client.player.ClientInput self) {
        return self.getMoveVector().y;
    }

    public static void forwardImpulse(net.minecraft.client.player.ClientInput self, float value) {
        self.moveVector = new Vec2(self.moveVector.x, value);
    }
}

package mmt.standin.net.minecraft.world.level.block.entity;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignText;

/**
 * 1.20.2's SignBlockEntity on 1.20.3.
 *
 * Read from both releases: 1.20.2's getTextFacingPlayer(player) was getText(isFacingFrontText(player)), the side of the
 * sign the player stands in front of. 1.20.3 keeps getText(boolean) and isFacingFrontText(player) as they were and drops
 * getTextFacingPlayer. So the old method is those two.
 *
 * NeoForge's primers begin at 1.20.4 -> 1.20.5, and Fabric's 1.20.3 post (https://fabricmc.net/2023/11/30/1203.html)
 * does not mention signs; this is from the game's code alone.
 */
public final class SignBlockEntity {

    private SignBlockEntity() {}

    public static SignText getTextFacingPlayer(net.minecraft.world.level.block.entity.SignBlockEntity self, Player player) {
        return self.getText(self.isFacingFrontText(player));
    }
}

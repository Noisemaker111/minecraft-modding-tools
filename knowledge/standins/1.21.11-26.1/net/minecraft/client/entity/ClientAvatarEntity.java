package mmt.standin.net.minecraft.client.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/**
 * 1.21.11's ClientAvatarEntity on 26.1.
 *
 * Read from both releases: 1.21.11's ClientAvatarEntity interface declared belowNameDisplay(), the text under an avatar's
 * name, written by the client's avatars (AbstractClientPlayer, ClientMannequin), both entities. 26.1 drops it from the
 * interface and declares Entity.belowNameDisplay() for every entity, which those avatars write. So the old method asks the
 * avatar as the entity it is.
 *
 * Agrees with NeoForge's 1.21.11 -> 26.1 primer (https://github.com/neoforged/.github/blob/main/primers/26.1/index.md, CC BY 4.0): "ClientAvatarEntity#belowNameDisplay -> Entity#belowNameDisplay".
 */
public final class ClientAvatarEntity {

    private ClientAvatarEntity() {}

    public static Component belowNameDisplay(net.minecraft.client.entity.ClientAvatarEntity self) {
        return ((Entity) self).belowNameDisplay();
    }
}

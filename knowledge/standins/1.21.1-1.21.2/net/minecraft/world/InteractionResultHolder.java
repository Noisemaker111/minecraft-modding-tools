package mmt.standin.net.minecraft.world;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

/**
 * 1.21.1's InteractionResultHolder, which 1.21.2 folded into InteractionResult.
 *
 * Read from both releases: 1.21.1's Item.use returned new InteractionResultHolder(result, stack), and
 * ServerPlayerGameMode.useItem put the holder's stack in the player's hand when it differed from the one used. 1.21.2's
 * Item.use returns an InteractionResult; a success carries the stack for the hand
 * (InteractionResult.Success.heldItemTransformedTo(stack)), which useItem puts in the hand, or keeps the hand's stack when
 * none is given. A holder of a success is that success carrying its stack; of anything else, the result alone.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "InteractionResultHolder, ItemInteractionResult -> InteractionResult", a $Success carrying in its $ItemContext what the held item was transformed to.
 */
public final class InteractionResultHolder {

    private InteractionResultHolder() {}

    public static InteractionResult init(InteractionResult result, Object value) {
        if (result instanceof InteractionResult.Success success && value instanceof ItemStack stack) {
            return success.heldItemTransformedTo(stack);
        }
        return result;
    }
}

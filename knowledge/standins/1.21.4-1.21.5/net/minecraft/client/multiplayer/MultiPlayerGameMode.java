package mmt.standin.net.minecraft.client.multiplayer;

/**
 * 1.21.4's MultiPlayerGameMode.hasInfiniteItems() on 1.21.5.
 *
 * Read from both releases: 1.21.4's hasInfiniteItems() answered whether the local player's game mode was creative
 * (localPlayerMode.isCreative()). 1.21.5 removed it and asks the entity instead (LivingEntity.hasInfiniteMaterials());
 * the same game mode is still public (getPlayerMode()), so the old answer is read from it as before.
 *
 * Agrees with NeoForge's 1.21.4 -> 1.21.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.5/index.md, CC BY 4.0):
 * "MultiPlayerGameMode#hasInfiniteItems -> net.minecraft.world.entity.LivingEntity#hasInfiniteMaterials".
 */
public final class MultiPlayerGameMode {

    private MultiPlayerGameMode() {}

    public static boolean hasInfiniteItems(net.minecraft.client.multiplayer.MultiPlayerGameMode self) {
        return self.getPlayerMode().isCreative();
    }
}

package mmt.standin.net.minecraft.world.level.block;

/**
 * 1.20's PitcherCropBlock on 1.19.4, which has no pitcher crop (1.20 added the pitcher plant with the sniffer's other
 * seeds; 1.19.4's experimental sniffer has the torchflower only): no block of 1.19.4 is one.
 */
public final class PitcherCropBlock {

    private PitcherCropBlock() {
    }

    public static boolean isInstance(Object o) {
        return false;
    }
}

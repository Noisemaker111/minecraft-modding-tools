package mmt.standin.net.minecraft.world.entity.vehicle;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * 1.21.1's new MinecartTNT(Level, x, y, z) on 1.21.2.
 *
 * Read from both releases: 1.21.1's constructor passed EntityType.TNT_MINECART and the position to AbstractMinecart(EntityType,
 * Level, x, y, z), which called this(type, level), then setPos(x, y, z) and set xo, yo, zo to the same values. 1.21.2
 * keeps only MinecartTNT(EntityType, Level); AbstractMinecart's positioned constructor now calls setInitialPos(x, y, z), which
 * does the same setPos and xo, yo, zo, and AbstractMinecart.createMinecart makes a cart the same way (create, then
 * setInitialPos).
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "setInitialPos - Sets the initial position of the minecart" and "AbstractMinecartContainer(EntityType, double, double, double, Level) is removed".
 */
public final class MinecartTNT {

    private MinecartTNT() {}

    public static net.minecraft.world.entity.vehicle.MinecartTNT init(Level level, double x, double y, double z) {
        net.minecraft.world.entity.vehicle.MinecartTNT cart = new net.minecraft.world.entity.vehicle.MinecartTNT(EntityType.TNT_MINECART, level);
        cart.setInitialPos(x, y, z);
        return cart;
    }
}

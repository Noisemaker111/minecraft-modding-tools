package mmt.standin.net.minecraft.world.level.block;

import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.TintedGlassBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.WaterloggedTransparentBlock;

/**
 * 1.20.2's GlassBlock, which 1.20.3 replaced with TransparentBlock.
 *
 * Read from both releases: 1.20.2's GlassBlock (an AbstractGlassBlock) was the class of plain glass alone (Blocks.GLASS =
 * new GlassBlock(...)); stained and tinted glass were AbstractGlassBlocks of their own. 1.20.3 makes plain glass a
 * TransparentBlock (Blocks.GLASS = new TransparentBlock(...)), which stained glass (StainedGlassBlock), tinted glass
 * (TintedGlassBlock) and the copper grates (WaterloggedTransparentBlock) now extend. So a block is what 1.20.2 called a
 * GlassBlock when it is a TransparentBlock and none of those three.
 *
 * NeoForge's primers begin at 1.20.4 -> 1.20.5. Agrees with Fabric's 1.20.3 post (https://fabricmc.net/2023/11/30/1203.html): "TransparentBlock was split into TranslucentBlock ... and TransparentBlock, which extends TranslucentBlock and is used by grates" (Yarn names; GlassBlock in Mojang's).
 */
public final class GlassBlock {

    private GlassBlock() {}

    public static boolean isInstance(Object value) {
        return value instanceof TransparentBlock && !(value instanceof StainedGlassBlock) && !(value instanceof TintedGlassBlock) && !(value instanceof WaterloggedTransparentBlock);
    }
}

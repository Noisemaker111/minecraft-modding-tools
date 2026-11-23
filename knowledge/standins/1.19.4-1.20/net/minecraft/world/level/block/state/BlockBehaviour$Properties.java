package mmt.standin.net.minecraft.world.level.block.state;

import java.util.function.Function;
import mmt.standin.net.minecraft.world.level.material.Material;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;

/**
 * 1.19.4's BlockBehaviour$Properties.of(material, ...) on 1.20.
 *
 * Read from both releases: 1.19.4's of(material) made properties whose map color was the material's (of(material,
 * color), of(material, dyeColor) and of(material, function) gave their own) and kept the material, from which 1.19.4
 * read a block's push reaction (BlockBehaviour.getPistonPushReaction), whether lava ignites it (LavaFluid,
 * isFlammable), whether it is a liquid, whether it can be replaced (canBeReplaced), whether it is solid (the state's
 * material.isSolid()), and its note block instrument when nothing about the block itself chose one
 * (NoteBlockInstrument.byState: STONE BASEDRUM, SAND SNARE, GLASS HAT, WOOD and NETHER_WOOD BASS, else HARP). 1.20's
 * of() takes none and sets each of those as a property: mapColor, pushReaction, ignitedByLava, liquid, replaceable,
 * forceSolidOn or forceSolidOff (solid whatever the shape), instrument. So the old call is of() with each property the
 * material gave set as 1.19.4 read it.
 *
 * Agrees with NeoForge's 1.19.4 -> 1.20 primer (https://github.com/neoforged/.github/blob/main/primers/1.20/index.md, CC BY 4.0): "Creating a block property is done through the static constructor #of which takes no arguments", pushReaction, ignitedByLava, liquid, forceSolidOn and forceSolidOff, replaceable, "The sound a block makes when put underneath a note block is set using BlockBehaviour$Properties#instrument", and "MapColors can be specified on a block property using #mapColor (replaces #color)".
 */
public final class BlockBehaviour$Properties {

    private BlockBehaviour$Properties() {}

    public static BlockBehaviour.Properties of(Material material) {
        return of(material, material.getColor());
    }

    public static BlockBehaviour.Properties of(Material material, MapColor color) {
        return from(material).mapColor(color);
    }

    public static BlockBehaviour.Properties of(Material material, DyeColor color) {
        return from(material).mapColor(color.getMapColor());
    }

    public static BlockBehaviour.Properties of(Material material, Function color) {
        return from(material).mapColor(color);
    }

    private static BlockBehaviour.Properties from(Material material) {
        BlockBehaviour.Properties properties = BlockBehaviour.Properties.of().pushReaction(material.getPushReaction());
        if (material.isFlammable()) {
            properties.ignitedByLava();
        }
        if (material.isLiquid()) {
            properties.liquid();
        }
        if (material.isReplaceable()) {
            properties.replaceable();
        }
        if (material.isSolid()) {
            properties.forceSolidOn();
        } else {
            properties.forceSolidOff();
        }
        if (material == Material.STONE) {
            properties.instrument(NoteBlockInstrument.BASEDRUM);
        } else if (material == Material.SAND) {
            properties.instrument(NoteBlockInstrument.SNARE);
        } else if (material == Material.GLASS) {
            properties.instrument(NoteBlockInstrument.HAT);
        } else if (material == Material.WOOD || material == Material.NETHER_WOOD) {
            properties.instrument(NoteBlockInstrument.BASS);
        }
        return properties;
    }
}

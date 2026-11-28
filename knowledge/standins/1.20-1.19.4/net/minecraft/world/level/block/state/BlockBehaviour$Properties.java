package mmt.standin.net.minecraft.world.level.block.state;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.Material;
import net.minecraft.world.level.material.MaterialColor;
import net.minecraft.world.level.material.PushReaction;

/**
 * 1.20's block settings that took over from 1.19.4's Material, written on 1.19.4 as the one flag of the block's
 * material each one is.
 *
 * Read from both releases: 1.20 dropped Material and moved its flags onto the settings, each read where 1.19.4 read the
 * material's: pushReaction(p) is what BlockStateBase.getPistonPushReaction() gives (1.19.4: getMaterial().getPushReaction()),
 * ignitedByLava() what LavaFluid.isFlammable reads (getMaterial().isFlammable()), replaceable() what canBeReplaced()
 * reads (getMaterial().isReplaceable()), liquid() what liquid() reads (getMaterial().isLiquid()), and forceSolidOn()/
 * forceSolidOff() fix isSolid(), and with it blocksMotion() (1.20's is isSolid() for every block but the cobweb and the
 * bamboo sapling; 1.19.4's getMaterial().isSolid() and blocksMotion()). Each setter here gives the settings the
 * material they had with that one flag changed: a block's material is the one its settings hold when it is made
 * (BlockBehaviour's constructor reads Properties.material), and a Material is a value of these flags.
 *
 * of(): 1.20's settings start with no map color (MapColor.NONE), a normal push reaction, not flammable, replaceable or
 * liquid, and redstone conducting and suffocating where the collision shape is a full block (1.19.4: where the
 * material is solid-blocking, or blocks motion, and the shape is full). 1.20 works isSolid out from the collision shape
 * of each state (BlockStateBase.calculateSolid: solid unless the shape is empty or small), which 1.19.4 must fix in the
 * material before any shape exists: the material made here is solid, as 1.20 finds every block with a full collision
 * shape. A block without collision (noCollission()) is not solid in 1.20 and is here; forceSolidOff() makes it not.
 *
 * instrument(i): 1.20 keeps the note a block gives a note block on its settings; 1.19.4 reads it from the material by
 * identity (NoteBlockInstrument.byStateBelow: STONE a bass drum, SAND a snare, GLASS a hat, WOOD and NETHER_WOOD a
 * bass, else a harp). The settings take that material whenever their flags are its flags, so the block keeps every
 * flag it was given and plays the note it was given wherever 1.19.4 can tell the two apart.
 *
 * mapColor(f): the map color of each state, 1.19.4's Properties.materialColor, which of(Material, Function) sets.
 */
public final class BlockBehaviour$Properties {

    private static final Map<BlockBehaviour.Properties, NoteBlockInstrument> INSTRUMENTS = Collections.synchronizedMap(new WeakHashMap<>());

    private BlockBehaviour$Properties() {
    }

    public static BlockBehaviour.Properties of() {
        return BlockBehaviour.Properties.of(new Material(MaterialColor.NONE, false, true, true, true, false, false, PushReaction.NORMAL));
    }

    public static BlockBehaviour.Properties instrument(BlockBehaviour.Properties self, NoteBlockInstrument instrument) {
        INSTRUMENTS.put(self, instrument);
        Material m = self.material;
        return with(self, m.isLiquid(), m.isSolid(), m.blocksMotion(), m.isSolidBlocking(), m.isFlammable(), m.isReplaceable(), m.getPushReaction());
    }

    public static BlockBehaviour.Properties mapColor(BlockBehaviour.Properties self, Function f) {
        self.materialColor = f;
        return self;
    }

    public static BlockBehaviour.Properties pushReaction(BlockBehaviour.Properties self, PushReaction reaction) {
        Material m = self.material;
        return with(self, m.isLiquid(), m.isSolid(), m.blocksMotion(), m.isSolidBlocking(), m.isFlammable(), m.isReplaceable(), reaction);
    }

    public static BlockBehaviour.Properties ignitedByLava(BlockBehaviour.Properties self) {
        Material m = self.material;
        return with(self, m.isLiquid(), m.isSolid(), m.blocksMotion(), m.isSolidBlocking(), true, m.isReplaceable(), m.getPushReaction());
    }

    public static BlockBehaviour.Properties replaceable(BlockBehaviour.Properties self) {
        Material m = self.material;
        return with(self, m.isLiquid(), m.isSolid(), m.blocksMotion(), m.isSolidBlocking(), m.isFlammable(), true, m.getPushReaction());
    }

    public static BlockBehaviour.Properties liquid(BlockBehaviour.Properties self) {
        Material m = self.material;
        return with(self, true, m.isSolid(), m.blocksMotion(), m.isSolidBlocking(), m.isFlammable(), m.isReplaceable(), m.getPushReaction());
    }

    public static BlockBehaviour.Properties forceSolidOn(BlockBehaviour.Properties self) {
        Material m = self.material;
        return with(self, m.isLiquid(), true, true, m.isSolidBlocking(), m.isFlammable(), m.isReplaceable(), m.getPushReaction());
    }

    public static BlockBehaviour.Properties forceSolidOff(BlockBehaviour.Properties self) {
        Material m = self.material;
        return with(self, m.isLiquid(), false, false, m.isSolidBlocking(), m.isFlammable(), m.isReplaceable(), m.getPushReaction());
    }

    /** The settings with a material of these flags: the one their instrument is read from where it has them, else a new one. */
    private static BlockBehaviour.Properties with(BlockBehaviour.Properties self, boolean liquid, boolean solid, boolean blocksMotion, boolean solidBlocking,
                                                  boolean flammable, boolean replaceable, PushReaction push) {
        NoteBlockInstrument instrument = INSTRUMENTS.get(self);
        Material[] played = instrument == NoteBlockInstrument.BASEDRUM ? new Material[] { Material.STONE }
            : instrument == NoteBlockInstrument.SNARE ? new Material[] { Material.SAND }
            : instrument == NoteBlockInstrument.HAT ? new Material[] { Material.GLASS }
            : instrument == NoteBlockInstrument.BASS ? new Material[] { Material.WOOD, Material.NETHER_WOOD }
            : new Material[0];
        for (Material m : played) {
            if (m.isLiquid() == liquid && m.isSolid() == solid && m.blocksMotion() == blocksMotion && m.isSolidBlocking() == solidBlocking
                && m.isFlammable() == flammable && m.isReplaceable() == replaceable && m.getPushReaction() == push) {
                self.material = m;
                return self;
            }
        }
        Material m = self.material;
        self.material = new Material(m.getColor(), liquid, solid, blocksMotion, solidBlocking, flammable, replaceable, push);
        return self;
    }
}

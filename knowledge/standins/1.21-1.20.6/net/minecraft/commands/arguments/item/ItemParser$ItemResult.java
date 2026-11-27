package mmt.standin.net.minecraft.commands.arguments.item;

/**
 * 1.21's ItemParser.ItemResult.components() on 1.20.6, for a port back: the parsed components as a patch.
 *
 * Read from both releases: 1.21's ItemResult is (item, DataComponentPatch components), the components the input sets
 * (and, new in 1.21, removes with !name); 1.20.6's is (item, DataComponentMap components), the components it sets, which
 * 1.20.6 cannot remove. A patch setting each of them is the same input (DataComponentPatch.builder().set(each)).
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`net.minecraft.commands.arguments.item.ItemInput` now takes in a `DataComponentPatch` instead of a `DataComponentMap`".
 */
public final class ItemParser$ItemResult {

    private ItemParser$ItemResult() {}

    public static net.minecraft.core.component.DataComponentPatch components(net.minecraft.commands.arguments.item.ItemParser.ItemResult self) {
        net.minecraft.core.component.DataComponentPatch.Builder patch = net.minecraft.core.component.DataComponentPatch.builder();
        for (Object each : self.components()) {
            patch.set((net.minecraft.core.component.TypedDataComponent) each);
        }
        return patch.build();
    }
}

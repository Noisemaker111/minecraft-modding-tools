package mmt.standin.net.minecraft.client.renderer.block.model;

import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.19.4's BlockModel(location, elements, textures, Boolean ambientOcclusion, guiLight, transforms, overrides) on 1.19.3,
 * for a port back.
 *
 * Read from both releases: the constructors are the same but for ambient occlusion, a Boolean in 1.19.4 (null: not set
 * by this model) and a boolean in 1.19.3. 1.19.4's hasAmbientOcclusion() is the model's own value where set, else its
 * parent's, else true; 1.19.3's is its parent's where it has one, else its own (which its reader defaults to true). So a
 * null is true here, the value 1.19.4 gives a model without a parent that sets none.
 *
 * Not the same: a model with a parent that sets its own value takes the parent's here, as every 1.19.3 model does.
 */
public final class BlockModel {

    private BlockModel() {
    }

    public static net.minecraft.client.renderer.block.model.BlockModel init(ResourceLocation parent, List elements, Map textures, Boolean ambientOcclusion, net.minecraft.client.renderer.block.model.BlockModel.GuiLight guiLight, ItemTransforms transforms, List overrides) {
        return new net.minecraft.client.renderer.block.model.BlockModel(parent, elements, textures, ambientOcclusion == null || ambientOcclusion, guiLight, transforms, overrides);
    }
}

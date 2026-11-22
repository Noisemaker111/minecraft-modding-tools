package mmt.standin.net.minecraft.client.renderer.block.model;

import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.19.3's BlockModel constructor on 1.19.4.
 *
 * Read from both releases: 1.19.3's BlockModel(parentLocation, elements, textures, hasAmbientOcclusion, guiLight,
 * transforms, overrides) took ambient occlusion as a boolean, and hasAmbientOcclusion() answered the parent's when the
 * model had one (its own flag only for a model without a parent). 1.19.4's takes a Boolean: its own value when set, else
 * the parent's, else true. The old model is the new one with no value of its own when it names a parent (so the parent's
 * answers, as before) and its flag when it does not.
 *
 * Fabric's 1.19.4 post (https://fabricmc.net/2023/03/01/1194.html) does not mention BlockModel, and NeoForge's primers begin at 1.20.2 -> 1.20.3; this is from the game's code alone.
 */
public final class BlockModel {

    private BlockModel() {}

    public static net.minecraft.client.renderer.block.model.BlockModel init(ResourceLocation parentLocation, List elements, Map textures, boolean hasAmbientOcclusion, net.minecraft.client.renderer.block.model.BlockModel.GuiLight guiLight, ItemTransforms transforms, List overrides) {
        Boolean ambientOcclusion = parentLocation != null ? null : Boolean.valueOf(hasAmbientOcclusion);
        return new net.minecraft.client.renderer.block.model.BlockModel(parentLocation, elements, textures, ambientOcclusion, guiLight, transforms, overrides);
    }
}

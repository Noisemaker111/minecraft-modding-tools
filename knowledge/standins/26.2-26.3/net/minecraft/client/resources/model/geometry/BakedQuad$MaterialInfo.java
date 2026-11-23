package mmt.standin.net.minecraft.client.resources.model.geometry;

import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.client.resources.model.geometry.BakedQuad;

/**
 * 26.2's BakedQuad.MaterialInfo constructor and shade() on 26.3.
 *
 * Read from both releases: 26.2's MaterialInfo(sprite, layer, itemRenderType, tintIndex, shade, lightEmission) held
 * whether a quad is shaded; the block lighter lit a shaded quad by its own face (cardinalLighting.byFace(direction)) and an
 * unshaded one as an up face (cardinalLighting.up()). 26.3's record holds instead a nullable shadeDirectionOverride, the
 * lighter lighting by that face when set and by the quad's own when null, and two more render types for the item's glint,
 * which MaterialInfo.of picks from the same choice as itemRenderType (block atlas or not, translucent layer or not). So
 * shade true is a null override and false an UP override, and shade() is whether there is no override; the glint types
 * are picked as of() picks them.
 *
 * Agrees with NeoForge's 26.2 -> 26.3 primer (https://github.com/neoforged/.github/blob/main/primers/26.3/index.md, CC BY 4.0):
 * "BakedQuad$MaterialInfo now takes in a nullable Direction for the shade instead of a boolean, along with RenderTypes for
 * the item glint and special item glint".
 */
public final class BakedQuad$MaterialInfo {

    private BakedQuad$MaterialInfo() {}

    public static BakedQuad.MaterialInfo init(TextureAtlasSprite sprite, ChunkSectionLayer layer, RenderType itemRenderType, int tintIndex, boolean shade, int lightEmission) {
        boolean blocks = sprite.atlasLocation().equals(TextureAtlas.LOCATION_BLOCKS);
        boolean translucent = layer.translucent();
        RenderType glint;
        RenderType glintSpecial;
        if (blocks) {
            glint = translucent ? Sheets.translucentBlockItemGlintSheet() : Sheets.cutoutBlockItemGlintSheet();
            glintSpecial = translucent ? Sheets.translucentBlockItemGlintSpecialSheet() : Sheets.cutoutBlockItemGlintSpecialSheet();
        } else {
            glint = translucent ? Sheets.translucentItemGlintSheet() : Sheets.cutoutItemGlintSheet();
            glintSpecial = translucent ? Sheets.translucentItemGlintSpecialSheet() : Sheets.cutoutItemGlintSpecialSheet();
        }
        return new BakedQuad.MaterialInfo(sprite, layer, itemRenderType, glint, glintSpecial, tintIndex, shade ? null : Direction.UP, lightEmission);
    }

    public static boolean shade(BakedQuad.MaterialInfo self) {
        return self.shadeDirectionOverride() == null;
    }
}

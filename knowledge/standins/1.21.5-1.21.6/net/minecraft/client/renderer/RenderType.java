package mmt.standin.net.minecraft.client.renderer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.21.5's GUI render types on 1.21.6.
 *
 * Read from both releases: 1.21.5's RenderType.gui(), guiOverlay(), guiTextHighlight(), guiGhostRecipeOverlay() were
 * render types on the pipelines GUI, GUI_OVERLAY, GUI_TEXT_HIGHLIGHT and GUI_GHOST_RECIPE_OVERLAY, and guiTextured(t),
 * guiTexturedOverlay(t) memoized ones on GUI_TEXTURED and GUI_TEXTURED_OVERLAY with texture t (no blur, no mipmap),
 * made by RenderType.create(name, buffer size, pipeline, state). The overlay pipelines were the plain ones with the depth
 * test off. 1.21.6 draws the GUI flat, in the order it is submitted, so it dropped these types and the overlay and ghost
 * pipelines; GUI, GUI_TEXT_HIGHLIGHT and GUI_TEXTURED remain, and RenderType.create, CompositeState.builder() and
 * TextureStateShard(texture, mipmap) make the same types on them, an overlay being its plain type.
 *
 * Agrees with NeoForge's 1.21.5 -> 1.21.6 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.6/index.md, CC BY 4.0): "gui, guiOverlay, guiTexturedOverlay, guiOpaqueTexturedBackground, guiNauseaOverlay, guiTextHighlight,
 * guiGhostRecipeOverlay, guiTextured are removed" and "GUI_OVERLAY, GUI_GHOST_RECIPE_OVERLAY, GUI_TEXTURED_OVERLAY are removed".
 */
public final class RenderType {

    private static final net.minecraft.client.renderer.RenderType GUI = net.minecraft.client.renderer.RenderType.create(
        "gui", 786432, RenderPipelines.GUI, net.minecraft.client.renderer.RenderType.CompositeState.builder().createCompositeState(false));
    private static final net.minecraft.client.renderer.RenderType GUI_OVERLAY = net.minecraft.client.renderer.RenderType.create(
        "gui_overlay", 1536, RenderPipelines.GUI, net.minecraft.client.renderer.RenderType.CompositeState.builder().createCompositeState(false));
    private static final net.minecraft.client.renderer.RenderType GUI_TEXT_HIGHLIGHT = net.minecraft.client.renderer.RenderType.create(
        "gui_text_highlight", 1536, RenderPipelines.GUI_TEXT_HIGHLIGHT, net.minecraft.client.renderer.RenderType.CompositeState.builder().createCompositeState(false));
    private static final net.minecraft.client.renderer.RenderType GUI_GHOST_RECIPE_OVERLAY = net.minecraft.client.renderer.RenderType.create(
        "gui_ghost_recipe_overlay", 1536, RenderPipelines.GUI, net.minecraft.client.renderer.RenderType.CompositeState.builder().createCompositeState(false));
    private static final Map<ResourceLocation, net.minecraft.client.renderer.RenderType> GUI_TEXTURED = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, net.minecraft.client.renderer.RenderType> GUI_TEXTURED_OVERLAY = new ConcurrentHashMap<>();

    private RenderType() {}

    private static net.minecraft.client.renderer.RenderType textured(String name, int size, ResourceLocation texture) {
        return net.minecraft.client.renderer.RenderType.create(name, size, RenderPipelines.GUI_TEXTURED,
            net.minecraft.client.renderer.RenderType.CompositeState.builder().setTextureState(new RenderStateShard.TextureStateShard(texture, false)).createCompositeState(false));
    }

    public static net.minecraft.client.renderer.RenderType gui() {
        return GUI;
    }

    public static net.minecraft.client.renderer.RenderType guiOverlay() {
        return GUI_OVERLAY;
    }

    public static net.minecraft.client.renderer.RenderType guiTextHighlight() {
        return GUI_TEXT_HIGHLIGHT;
    }

    public static net.minecraft.client.renderer.RenderType guiGhostRecipeOverlay() {
        return GUI_GHOST_RECIPE_OVERLAY;
    }

    public static net.minecraft.client.renderer.RenderType guiTextured(ResourceLocation texture) {
        return GUI_TEXTURED.computeIfAbsent(texture, t -> textured("gui_textured", 786432, t));
    }

    public static net.minecraft.client.renderer.RenderType guiTexturedOverlay(ResourceLocation texture) {
        return GUI_TEXTURED_OVERLAY.computeIfAbsent(texture, t -> textured("gui_textured_overlay", 1536, t));
    }
}

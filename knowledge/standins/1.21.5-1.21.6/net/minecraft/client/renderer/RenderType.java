package mmt.standin.net.minecraft.client.renderer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.gui.screens.LoadingOverlay;
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
 *
 * The block and logo types, read from both releases: 1.21.5's translucent() was create("translucent", 786432, true, true,
 * RenderPipelines.TRANSLUCENT, lightmap + mipped block sheet + translucent target), chunkBufferLayers() the list solid,
 * cutoutMipped, cutout, translucent, tripwire, crosshair(t) a memoized type on CROSSHAIR with texture t, mojangLogo() one on
 * MOJANG_LOGO with LoadingOverlay's logo. 1.21.6 keeps the pipelines, the shards, RenderType.create and
 * solid/cutoutMipped/cutout/tripwire; texture shards lost their blur flag (now the sampler's), so the same types are made
 * without it.
 *
 * Agrees with the same primer: "RenderTypes used for defining how a block or fluid should render are now replaced with
 * ChunkSectionLayers ... certain RenderTypes are removed, like TRANSLUCENT", "chunkBufferLayers is removed", "crosshair is removed", "mojangLogo is removed".
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

    private static final net.minecraft.client.renderer.RenderType TRANSLUCENT = net.minecraft.client.renderer.RenderType.create(
        "translucent", 786432, true, true, RenderPipelines.TRANSLUCENT,
        net.minecraft.client.renderer.RenderType.CompositeState.builder()
            .setLightmapState(RenderStateShard.LIGHTMAP)
            .setTextureState(RenderStateShard.BLOCK_SHEET_MIPPED)
            .setOutputState(RenderStateShard.TRANSLUCENT_TARGET)
            .createCompositeState(true));
    private static final Map<ResourceLocation, net.minecraft.client.renderer.RenderType> CROSSHAIR = new ConcurrentHashMap<>();
    private static net.minecraft.client.renderer.RenderType mojangLogo;
    private static List chunkBufferLayers;

    private RenderType() {}

    public static net.minecraft.client.renderer.RenderType translucent() {
        return TRANSLUCENT;
    }

    public static List chunkBufferLayers() {
        if (chunkBufferLayers == null) {
            chunkBufferLayers = List.of(net.minecraft.client.renderer.RenderType.solid(), net.minecraft.client.renderer.RenderType.cutoutMipped(),
                net.minecraft.client.renderer.RenderType.cutout(), TRANSLUCENT, net.minecraft.client.renderer.RenderType.tripwire());
        }
        return chunkBufferLayers;
    }

    public static net.minecraft.client.renderer.RenderType crosshair(ResourceLocation texture) {
        return CROSSHAIR.computeIfAbsent(texture, t -> net.minecraft.client.renderer.RenderType.create("crosshair", 786432, RenderPipelines.CROSSHAIR,
            net.minecraft.client.renderer.RenderType.CompositeState.builder().setTextureState(new RenderStateShard.TextureStateShard(t, false)).createCompositeState(false)));
    }

    public static net.minecraft.client.renderer.RenderType mojangLogo() {
        if (mojangLogo == null) {
            mojangLogo = net.minecraft.client.renderer.RenderType.create("mojang_logo", 786432, RenderPipelines.MOJANG_LOGO,
                net.minecraft.client.renderer.RenderType.CompositeState.builder()
                    .setTextureState(new RenderStateShard.TextureStateShard(LoadingOverlay.MOJANG_STUDIOS_LOGO_LOCATION, false)).createCompositeState(false));
        }
        return mojangLogo;
    }

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

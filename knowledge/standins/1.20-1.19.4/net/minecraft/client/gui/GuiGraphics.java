package mmt.standin.net.minecraft.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

/**
 * 1.20's GuiGraphics on 1.19.4, for a port back: the class itself, which 1.19.4 does not have yet.
 *
 * Read from both releases: 1.20 gathered the drawing calls 1.19.4 makes as static methods of GuiComponent (fill,
 * fillGradient, blit, blitNineSliced, blitRepeating, renderOutline, drawString, drawCenteredString, enableScissor),
 * on ItemRenderer (renderAndDecorateItem, renderGuiItemDecorations) and on Screen (renderTooltip,
 * renderComponentTooltip, renderComponentHoverEffect) into one object that holds the PoseStack they were each given.
 * 1.20's methods take the same arguments as 1.19.4's less the PoseStack, and do the same: a blit names its texture
 * where 1.19.4 bound it first (RenderSystem.setShaderTexture(0, ...)), drawString draws with a shadow unless told not
 * to as 1.19.4's drawString (drawShadow) and Font.draw do, setColor is RenderSystem.setShaderColor. 1.20 queues text
 * and fills in its buffer source and flushes after each call (flushIfUnmanaged), so what is on screen after each call
 * is what 1.19.4's immediate drawing leaves. Tooltips are drawn by a screen the size of the window, as 1.19.4's
 * screens draw theirs. An override that 1.19.4 hands a PoseStack gets a GuiGraphics around that stack (from).
 *
 * Agrees with Fabric's 1.20 post (https://fabricmc.net/2023/05/25/120.html): "DrawableHelper has been replaced with
 * DrawContext ... All rendering methods were moved to DrawContext, and the MatrixStack argument is now inside the
 * context", with Mojang's names GuiComponent and GuiGraphics for Yarn's DrawableHelper and DrawContext.
 */
public class GuiGraphics {

    public static final float MAX_GUI_Z = 10000.0F;
    public static final float MIN_GUI_Z = -10000.0F;

    private final Minecraft minecraft;
    private final PoseStack pose;
    private final MultiBufferSource.BufferSource bufferSource;
    private Tooltips tooltips;

    public GuiGraphics(Minecraft minecraft, MultiBufferSource.BufferSource bufferSource) {
        this(minecraft, new PoseStack(), bufferSource);
    }

    GuiGraphics(Minecraft minecraft, PoseStack pose, MultiBufferSource.BufferSource bufferSource) {
        this.minecraft = minecraft;
        this.pose = pose;
        this.bufferSource = bufferSource;
    }

    /** What an override 1.19.4 calls with a PoseStack (Screen.render, Renderable.render) is given in GuiGraphics' place. */
    public static GuiGraphics from(PoseStack pose) {
        Minecraft minecraft = Minecraft.getInstance();
        return new GuiGraphics(minecraft, pose, minecraft.renderBuffers().bufferSource());
    }

    public void drawManaged(Runnable run) {
        flush();
        run.run();
        flush();
    }

    public int guiWidth() {
        return minecraft.getWindow().getGuiScaledWidth();
    }

    public int guiHeight() {
        return minecraft.getWindow().getGuiScaledHeight();
    }

    public PoseStack pose() {
        return pose;
    }

    public MultiBufferSource.BufferSource bufferSource() {
        return bufferSource;
    }

    public void flush() {
        RenderSystem.disableDepthTest();
        bufferSource.endBatch();
        RenderSystem.enableDepthTest();
    }

    public void hLine(int x1, int x2, int y, int color) {
        if (x2 < x1) {
            int t = x1;
            x1 = x2;
            x2 = t;
        }
        fill(x1, y, x2 + 1, y + 1, color);
    }

    public void vLine(int x, int y1, int y2, int color) {
        if (y2 < y1) {
            int t = y1;
            y1 = y2;
            y2 = t;
        }
        fill(x, y1 + 1, x + 1, y2, color);
    }

    public void enableScissor(int x1, int y1, int x2, int y2) {
        GuiComponent.enableScissor(x1, y1, x2, y2);
    }

    public void disableScissor() {
        GuiComponent.disableScissor();
    }

    public void setColor(float r, float g, float b, float a) {
        RenderSystem.setShaderColor(r, g, b, a);
    }

    public void fill(int x1, int y1, int x2, int y2, int color) {
        GuiComponent.fill(pose, x1, y1, x2, y2, color);
    }

    public void fill(int x1, int y1, int x2, int y2, int z, int color) {
        GuiComponent.fill(pose, x1, y1, x2, y2, z, color);
    }

    public void fillGradient(int x1, int y1, int x2, int y2, int from, int to) {
        fillGradient(x1, y1, x2, y2, 0, from, to);
    }

    public void fillGradient(int x1, int y1, int x2, int y2, int z, int from, int to) {
        RenderSystem.enableBlend();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f m = pose.last().pose();
        buffer.vertex(m, x1, y1, z).color(from >> 16 & 255, from >> 8 & 255, from & 255, from >>> 24).endVertex();
        buffer.vertex(m, x1, y2, z).color(to >> 16 & 255, to >> 8 & 255, to & 255, to >>> 24).endVertex();
        buffer.vertex(m, x2, y2, z).color(to >> 16 & 255, to >> 8 & 255, to & 255, to >>> 24).endVertex();
        buffer.vertex(m, x2, y1, z).color(from >> 16 & 255, from >> 8 & 255, from & 255, from >>> 24).endVertex();
        BufferUploader.drawWithShader(buffer.end());
        RenderSystem.disableBlend();
    }

    public void drawCenteredString(Font font, String text, int x, int y, int color) {
        GuiComponent.drawCenteredString(pose, font, text, x, y, color);
    }

    public void drawCenteredString(Font font, Component text, int x, int y, int color) {
        GuiComponent.drawCenteredString(pose, font, text, x, y, color);
    }

    public void drawCenteredString(Font font, FormattedCharSequence text, int x, int y, int color) {
        GuiComponent.drawCenteredString(pose, font, text, x, y, color);
    }

    public int drawString(Font font, String text, int x, int y, int color) {
        return drawString(font, text, x, y, color, true);
    }

    public int drawString(Font font, String text, int x, int y, int color, boolean shadow) {
        if (text == null) {
            return 0;
        }
        return shadow ? font.drawShadow(pose, text, (float) x, (float) y, color) : font.draw(pose, text, (float) x, (float) y, color);
    }

    public int drawString(Font font, FormattedCharSequence text, int x, int y, int color) {
        return drawString(font, text, x, y, color, true);
    }

    public int drawString(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
        return shadow ? font.drawShadow(pose, text, (float) x, (float) y, color) : font.draw(pose, text, (float) x, (float) y, color);
    }

    public int drawString(Font font, Component text, int x, int y, int color) {
        return drawString(font, text, x, y, color, true);
    }

    public int drawString(Font font, Component text, int x, int y, int color, boolean shadow) {
        return drawString(font, text.getVisualOrderText(), x, y, color, shadow);
    }

    public void drawWordWrap(Font font, FormattedText text, int x, int y, int width, int color) {
        for (Object line : font.split(text, width)) {
            drawString(font, (FormattedCharSequence) line, x, y, color, false);
            y += 9;
        }
    }

    public void blit(int x, int y, int z, int width, int height, TextureAtlasSprite sprite) {
        RenderSystem.setShaderTexture(0, sprite.atlasLocation());
        GuiComponent.blit(pose, x, y, z, width, height, sprite);
    }

    public void blit(int x, int y, int z, int width, int height, TextureAtlasSprite sprite, float r, float g, float b, float a) {
        RenderSystem.setShaderTexture(0, sprite.atlasLocation());
        GuiComponent.blit(pose, x, y, z, width, height, sprite, r, g, b, a);
    }

    public void renderOutline(int x, int y, int width, int height, int color) {
        GuiComponent.renderOutline(pose, x, y, width, height, color);
    }

    public void blit(ResourceLocation texture, int x, int y, int u, int v, int width, int height) {
        RenderSystem.setShaderTexture(0, texture);
        GuiComponent.blit(pose, x, y, u, v, width, height);
    }

    public void blit(ResourceLocation texture, int x, int y, int z, float u, float v, int width, int height, int texWidth, int texHeight) {
        RenderSystem.setShaderTexture(0, texture);
        GuiComponent.blit(pose, x, y, z, u, v, width, height, texWidth, texHeight);
    }

    public void blit(ResourceLocation texture, int x, int y, int width, int height, float u, float v, int uWidth, int vHeight, int texWidth, int texHeight) {
        RenderSystem.setShaderTexture(0, texture);
        GuiComponent.blit(pose, x, y, width, height, u, v, uWidth, vHeight, texWidth, texHeight);
    }

    public void blit(ResourceLocation texture, int x, int y, float u, float v, int width, int height, int texWidth, int texHeight) {
        RenderSystem.setShaderTexture(0, texture);
        GuiComponent.blit(pose, x, y, u, v, width, height, texWidth, texHeight);
    }

    public void blitNineSliced(ResourceLocation texture, int x, int y, int width, int height, int border, int texWidth, int texHeight, int u, int v) {
        RenderSystem.setShaderTexture(0, texture);
        GuiComponent.blitNineSliced(pose, x, y, width, height, border, texWidth, texHeight, u, v);
    }

    public void blitNineSliced(ResourceLocation texture, int x, int y, int width, int height, int sideBorder, int topBorder, int texWidth, int texHeight, int u, int v) {
        RenderSystem.setShaderTexture(0, texture);
        GuiComponent.blitNineSliced(pose, x, y, width, height, sideBorder, topBorder, texWidth, texHeight, u, v);
    }

    public void blitNineSliced(ResourceLocation texture, int x, int y, int width, int height, int left, int top, int right, int bottom, int texWidth, int texHeight, int u,
        int v) {
        RenderSystem.setShaderTexture(0, texture);
        GuiComponent.blitNineSliced(pose, x, y, width, height, left, top, right, bottom, texWidth, texHeight, u, v);
    }

    public void blitRepeating(ResourceLocation texture, int x, int y, int width, int height, int u, int v, int sliceWidth, int sliceHeight) {
        RenderSystem.setShaderTexture(0, texture);
        GuiComponent.blitRepeating(pose, x, y, width, height, u, v, sliceWidth, sliceHeight);
    }

    public void renderItem(ItemStack stack, int x, int y) {
        minecraft.getItemRenderer().renderAndDecorateItem(pose, stack, x, y);
    }

    public void renderItem(ItemStack stack, int x, int y, int seed) {
        minecraft.getItemRenderer().renderAndDecorateItem(pose, stack, x, y, seed);
    }

    public void renderItem(ItemStack stack, int x, int y, int seed, int guiOffset) {
        minecraft.getItemRenderer().renderAndDecorateItem(pose, stack, x, y, seed, guiOffset);
    }

    public void renderFakeItem(ItemStack stack, int x, int y) {
        minecraft.getItemRenderer().renderAndDecorateFakeItem(pose, stack, x, y);
    }

    public void renderItem(LivingEntity entity, ItemStack stack, int x, int y, int seed) {
        minecraft.getItemRenderer().renderAndDecorateItem(pose, entity, stack, x, y, seed);
    }

    public void renderItemDecorations(Font font, ItemStack stack, int x, int y) {
        minecraft.getItemRenderer().renderGuiItemDecorations(pose, font, stack, x, y);
    }

    public void renderItemDecorations(Font font, ItemStack stack, int x, int y, String text) {
        minecraft.getItemRenderer().renderGuiItemDecorations(pose, font, stack, x, y, text);
    }

    public void renderTooltip(Font font, ItemStack stack, int x, int y) {
        tooltips(font).item(pose, stack, x, y);
    }

    public void renderTooltip(Font font, List lines, Optional image, int x, int y) {
        tooltips(font).renderTooltip(pose, lines, image, x, y);
    }

    public void renderTooltip(Font font, Component line, int x, int y) {
        tooltips(font).renderTooltip(pose, line, x, y);
    }

    public void renderComponentTooltip(Font font, List lines, int x, int y) {
        tooltips(font).renderComponentTooltip(pose, lines, x, y);
    }

    public void renderTooltip(Font font, List lines, int x, int y) {
        tooltips(font).renderTooltip(pose, lines, x, y);
    }

    public void renderComponentHoverEffect(Font font, Style style, int x, int y) {
        tooltips(font).hover(pose, style, x, y);
    }

    private Tooltips tooltips(Font font) {
        if (tooltips == null) {
            tooltips = new Tooltips();
        }
        tooltips.init(minecraft, guiWidth(), guiHeight());
        tooltips.use(font);
        return tooltips;
    }

    /** An empty screen the size of the window, which draws tooltips as 1.19.4's screens draw theirs. */
    static final class Tooltips extends Screen {

        Tooltips() {
            super(Component.empty());
        }

        void use(Font font) {
            this.font = font;
        }

        void item(PoseStack pose, ItemStack stack, int x, int y) {
            renderTooltip(pose, stack, x, y);
        }

        void hover(PoseStack pose, Style style, int x, int y) {
            renderComponentHoverEffect(pose, style, x, y);
        }
    }
}

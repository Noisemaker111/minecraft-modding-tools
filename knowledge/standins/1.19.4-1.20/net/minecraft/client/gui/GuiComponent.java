package mmt.standin.net.minecraft.client.gui;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Divisor;
import it.unimi.dsi.fastutil.ints.IntIterator;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.FastColor;
import org.joml.Matrix4f;

/**
 * 1.19.4's GuiComponent drawing helpers on 1.20.
 *
 * Read from both releases: 1.19.4's GuiComponent held static drawing helpers (fill, fillGradient, hLine and vLine, blit
 * of the texture RenderSystem.setShaderTexture had bound, blit of an atlas sprite, blitNineSliced, blitRepeating,
 * renderOutline, drawString and drawCenteredString, a scissor stack) and the shared texture names; each drew at once
 * through the Tesselator with the pose's matrix. 1.20 moved them onto GuiGraphics, which takes the texture with each blit
 * and batches into its buffer source; the port carries the GuiComponent type to GuiGraphics. These are 1.19.4's bodies on
 * 1.20: the same Tesselator, BufferBuilder, BufferUploader, RenderSystem and shader calls, which 1.20 keeps, so a blit
 * still draws the texture the mod bound; text goes through this update's Font stand-in, 1.19.4's Font.drawShadow. Each
 * public static method answers the old static method of its name, and the texture names (files 1.20 still ships) answer
 * the old fields.
 *
 * Agrees with Fabric's 1.20 post (https://fabricmc.net/2023/05/25/120.html): "`DrawableHelper`, a utility that is
 * frequently used by subclassing it, turned into an object passed to rendering methods: `DrawContext`." (GuiComponent
 * and GuiGraphics in Mojang's names.)
 */
public final class GuiComponent {

   private GuiComponent() {}

   public static final ResourceLocation BACKGROUND_LOCATION = new ResourceLocation("textures/gui/options_background.png");
   public static final ResourceLocation STATS_ICON_LOCATION = new ResourceLocation("textures/gui/container/stats_icons.png");
   public static final ResourceLocation GUI_ICONS_LOCATION = new ResourceLocation("textures/gui/icons.png");
   public static final ResourceLocation LIGHT_DIRT_BACKGROUND = new ResourceLocation("textures/gui/light_dirt_background.png");
   public static ResourceLocation BACKGROUND_LOCATION() {
      return BACKGROUND_LOCATION;
   }

   public static ResourceLocation STATS_ICON_LOCATION() {
      return STATS_ICON_LOCATION;
   }

   public static ResourceLocation GUI_ICONS_LOCATION() {
      return GUI_ICONS_LOCATION;
   }

   public static ResourceLocation LIGHT_DIRT_BACKGROUND() {
      return LIGHT_DIRT_BACKGROUND;
   }

   private static final ScissorStack SCISSOR_STACK = new ScissorStack();

   public static void hLine(PoseStack p0, int p1, int p2, int p3, int p4) {
      if (p2 < p1) {
         int p5 = p1;
         p1 = p2;
         p2 = p5;
      }

      fill(p0, p1, p3, p2 + 1, p3 + 1, p4);
   }

   public static void vLine(PoseStack p0, int p1, int p2, int p3, int p4) {
      if (p3 < p2) {
         int p5 = p2;
         p2 = p3;
         p3 = p5;
      }

      fill(p0, p1, p2 + 1, p1 + 1, p3, p4);
   }

   public static void enableScissor(int p0, int p1, int p2, int p3) {
      applyScissor(SCISSOR_STACK.push(new ScreenRectangle(p0, p1, p2 - p0, p3 - p1)));
   }

   public static void disableScissor() {
      applyScissor(SCISSOR_STACK.pop());
   }

   private static void applyScissor(ScreenRectangle p0) {
      if (p0 != null) {
         Window p1 = Minecraft.getInstance().getWindow();
         int p2 = p1.getHeight();
         double p3 = p1.getGuiScale();
         double p4 = p0.left() * p3;
         double p5 = p2 - p0.bottom() * p3;
         double p6 = p0.width() * p3;
         double p7 = p0.height() * p3;
         RenderSystem.enableScissor((int)p4, (int)p5, Math.max(0, (int)p6), Math.max(0, (int)p7));
      } else {
         RenderSystem.disableScissor();
      }
   }

   public static void fill(PoseStack p0, int p1, int p2, int p3, int p4, int p5) {
      fill(p0, p1, p2, p3, p4, 0, p5);
   }

   public static void fill(PoseStack p0, int p1, int p2, int p3, int p4, int p5, int p6) {
      Matrix4f p7 = p0.last().pose();
      if (p1 < p3) {
         int p8 = p1;
         p1 = p3;
         p3 = p8;
      }

      if (p2 < p4) {
         int p9 = p2;
         p2 = p4;
         p4 = p9;
      }

      float p10 = FastColor.ARGB32.alpha(p6) / 255.0F;
      float p11 = FastColor.ARGB32.red(p6) / 255.0F;
      float p12 = FastColor.ARGB32.green(p6) / 255.0F;
      float p13 = FastColor.ARGB32.blue(p6) / 255.0F;
      BufferBuilder p14 = Tesselator.getInstance().getBuilder();
      RenderSystem.enableBlend();
      RenderSystem.setShader(GameRenderer::getPositionColorShader);
      p14.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
      p14.vertex(p7, p1, p2, p5).color(p11, p12, p13, p10).endVertex();
      p14.vertex(p7, p1, p4, p5).color(p11, p12, p13, p10).endVertex();
      p14.vertex(p7, p3, p4, p5).color(p11, p12, p13, p10).endVertex();
      p14.vertex(p7, p3, p2, p5).color(p11, p12, p13, p10).endVertex();
      BufferUploader.drawWithShader(p14.end());
      RenderSystem.disableBlend();
   }

   public static void fillGradient(PoseStack p0, int p1, int p2, int p3, int p4, int p5, int p6) {
      fillGradient(p0, p1, p2, p3, p4, p5, p6, 0);
   }

   public static void fillGradient(PoseStack p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7) {
      RenderSystem.enableBlend();
      RenderSystem.setShader(GameRenderer::getPositionColorShader);
      Tesselator p8 = Tesselator.getInstance();
      BufferBuilder p9 = p8.getBuilder();
      p9.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
      fillGradient(p0.last().pose(), p9, p1, p2, p3, p4, p7, p5, p6);
      p8.end();
      RenderSystem.disableBlend();
   }

   public static void fillGradient(Matrix4f p0, BufferBuilder p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8) {
      float p9 = FastColor.ARGB32.alpha(p7) / 255.0F;
      float p10 = FastColor.ARGB32.red(p7) / 255.0F;
      float p11 = FastColor.ARGB32.green(p7) / 255.0F;
      float p12 = FastColor.ARGB32.blue(p7) / 255.0F;
      float p13 = FastColor.ARGB32.alpha(p8) / 255.0F;
      float p14 = FastColor.ARGB32.red(p8) / 255.0F;
      float p15 = FastColor.ARGB32.green(p8) / 255.0F;
      float p16 = FastColor.ARGB32.blue(p8) / 255.0F;
      p1.vertex(p0, p2, p3, p6).color(p10, p11, p12, p9).endVertex();
      p1.vertex(p0, p2, p5, p6).color(p14, p15, p16, p13).endVertex();
      p1.vertex(p0, p4, p5, p6).color(p14, p15, p16, p13).endVertex();
      p1.vertex(p0, p4, p3, p6).color(p10, p11, p12, p9).endVertex();
   }

   public static void drawCenteredString(PoseStack p0, Font p1, String p2, int p3, int p4, int p5) {
      mmt.standin.net.minecraft.client.gui.Font.drawShadow(p1, p0, p2, (float)(p3 - p1.width(p2) / 2), (float)p4, p5);
   }

   public static void drawCenteredString(PoseStack p0, Font p1, Component p2, int p3, int p4, int p5) {
      FormattedCharSequence p6 = p2.getVisualOrderText();
      mmt.standin.net.minecraft.client.gui.Font.drawShadow(p1, p0, p6, (float)(p3 - p1.width(p6) / 2), (float)p4, p5);
   }

   public static void drawCenteredString(PoseStack p0, Font p1, FormattedCharSequence p2, int p3, int p4, int p5) {
      mmt.standin.net.minecraft.client.gui.Font.drawShadow(p1, p0, p2, (float)(p3 - p1.width(p2) / 2), (float)p4, p5);
   }

   public static void drawString(PoseStack p0, Font p1, String p2, int p3, int p4, int p5) {
      mmt.standin.net.minecraft.client.gui.Font.drawShadow(p1, p0, p2, (float)p3, (float)p4, p5);
   }

   public static void drawString(PoseStack p0, Font p1, FormattedCharSequence p2, int p3, int p4, int p5) {
      mmt.standin.net.minecraft.client.gui.Font.drawShadow(p1, p0, p2, (float)p3, (float)p4, p5);
   }

   public static void drawString(PoseStack p0, Font p1, Component p2, int p3, int p4, int p5) {
      mmt.standin.net.minecraft.client.gui.Font.drawShadow(p1, p0, p2, (float)p3, (float)p4, p5);
   }

   public static void blitOutlineBlack(int p0, int p1, BiConsumer<Integer, Integer> p2) {
      RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
      p2.accept(p0 + 1, p1);
      p2.accept(p0 - 1, p1);
      p2.accept(p0, p1 + 1);
      p2.accept(p0, p1 - 1);
      RenderSystem.defaultBlendFunc();
      p2.accept(p0, p1);
   }

   public static void blit(PoseStack p0, int p1, int p2, int p3, int p4, int p5, TextureAtlasSprite p6) {
      innerBlit(p0.last().pose(), p1, p1 + p4, p2, p2 + p5, p3, p6.getU0(), p6.getU1(), p6.getV0(), p6.getV1());
   }

   public static void blit(PoseStack p0, int p1, int p2, int p3, int p4, int p5, TextureAtlasSprite p6, float p7, float p8, float p9, float p10) {
      innerBlit(p0.last().pose(), p1, p1 + p4, p2, p2 + p5, p3, p6.getU0(), p6.getU1(), p6.getV0(), p6.getV1(), p7, p8, p9, p10);
   }

   public static void renderOutline(PoseStack p0, int p1, int p2, int p3, int p4, int p5) {
      fill(p0, p1, p2, p1 + p3, p2 + 1, p5);
      fill(p0, p1, p2 + p4 - 1, p1 + p3, p2 + p4, p5);
      fill(p0, p1, p2 + 1, p1 + 1, p2 + p4 - 1, p5);
      fill(p0, p1 + p3 - 1, p2 + 1, p1 + p3, p2 + p4 - 1, p5);
   }

   public static void blit(PoseStack p0, int p1, int p2, int p3, int p4, int p5, int p6) {
      blit(p0, p1, p2, 0, p3, p4, p5, p6, 256, 256);
   }

   public static void blit(PoseStack p0, int p1, int p2, int p3, float p4, float p5, int p6, int p7, int p8, int p9) {
      blit(p0, p1, p1 + p6, p2, p2 + p7, p3, p6, p7, p4, p5, p8, p9);
   }

   public static void blit(PoseStack p0, int p1, int p2, int p3, int p4, float p5, float p6, int p7, int p8, int p9, int p10) {
      blit(p0, p1, p1 + p3, p2, p2 + p4, 0, p7, p8, p5, p6, p9, p10);
   }

   public static void blit(PoseStack p0, int p1, int p2, float p3, float p4, int p5, int p6, int p7, int p8) {
      blit(p0, p1, p2, p5, p6, p3, p4, p5, p6, p7, p8);
   }

   private static void blit(PoseStack p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, float p8, float p9, int p10, int p11) {
      innerBlit(p0.last().pose(), p1, p2, p3, p4, p5, (p8 + 0.0F) / p10, (p8 + p6) / p10, (p9 + 0.0F) / p11, (p9 + p7) / p11);
   }

   private static void innerBlit(Matrix4f p0, int p1, int p2, int p3, int p4, int p5, float p6, float p7, float p8, float p9) {
      RenderSystem.setShader(GameRenderer::getPositionTexShader);
      BufferBuilder p10 = Tesselator.getInstance().getBuilder();
      p10.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
      p10.vertex(p0, p1, p3, p5).uv(p6, p8).endVertex();
      p10.vertex(p0, p1, p4, p5).uv(p6, p9).endVertex();
      p10.vertex(p0, p2, p4, p5).uv(p7, p9).endVertex();
      p10.vertex(p0, p2, p3, p5).uv(p7, p8).endVertex();
      BufferUploader.drawWithShader(p10.end());
   }

   private static void innerBlit(
      Matrix4f p0, int p1, int p2, int p3, int p4, int p5, float p6, float p7, float p8, float p9, float p10, float p11, float p12, float p13
   ) {
      RenderSystem.setShader(GameRenderer::getPositionColorTexShader);
      RenderSystem.enableBlend();
      BufferBuilder p14 = Tesselator.getInstance().getBuilder();
      p14.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX);
      p14.vertex(p0, p1, p3, p5).color(p10, p11, p12, p13).uv(p6, p8).endVertex();
      p14.vertex(p0, p1, p4, p5).color(p10, p11, p12, p13).uv(p6, p9).endVertex();
      p14.vertex(p0, p2, p4, p5).color(p10, p11, p12, p13).uv(p7, p9).endVertex();
      p14.vertex(p0, p2, p3, p5).color(p10, p11, p12, p13).uv(p7, p8).endVertex();
      BufferUploader.drawWithShader(p14.end());
      RenderSystem.disableBlend();
   }

   public static void blitNineSliced(PoseStack p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9) {
      blitNineSliced(p0, p1, p2, p3, p4, p5, p5, p5, p5, p6, p7, p8, p9);
   }

   public static void blitNineSliced(PoseStack p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9, int p10) {
      blitNineSliced(p0, p1, p2, p3, p4, p5, p6, p5, p6, p7, p8, p9, p10);
   }

   public static void blitNineSliced(
      PoseStack p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9, int p10, int p11, int p12
   ) {
      p5 = Math.min(p5, p3 / 2);
      p7 = Math.min(p7, p3 / 2);
      p6 = Math.min(p6, p4 / 2);
      p8 = Math.min(p8, p4 / 2);
      if (p3 == p9 && p4 == p10) {
         blit(p0, p1, p2, p11, p12, p3, p4);
      } else if (p4 == p10) {
         blit(p0, p1, p2, p11, p12, p5, p4);
         blitRepeating(p0, p1 + p5, p2, p3 - p7 - p5, p4, p11 + p5, p12, p9 - p7 - p5, p10);
         blit(p0, p1 + p3 - p7, p2, p11 + p9 - p7, p12, p7, p4);
      } else if (p3 == p9) {
         blit(p0, p1, p2, p11, p12, p3, p6);
         blitRepeating(p0, p1, p2 + p6, p3, p4 - p8 - p6, p11, p12 + p6, p9, p10 - p8 - p6);
         blit(p0, p1, p2 + p4 - p8, p11, p12 + p10 - p8, p3, p8);
      } else {
         blit(p0, p1, p2, p11, p12, p5, p6);
         blitRepeating(p0, p1 + p5, p2, p3 - p7 - p5, p6, p11 + p5, p12, p9 - p7 - p5, p6);
         blit(p0, p1 + p3 - p7, p2, p11 + p9 - p7, p12, p7, p6);
         blit(p0, p1, p2 + p4 - p8, p11, p12 + p10 - p8, p5, p8);
         blitRepeating(p0, p1 + p5, p2 + p4 - p8, p3 - p7 - p5, p8, p11 + p5, p12 + p10 - p8, p9 - p7 - p5, p8);
         blit(p0, p1 + p3 - p7, p2 + p4 - p8, p11 + p9 - p7, p12 + p10 - p8, p7, p8);
         blitRepeating(p0, p1, p2 + p6, p5, p4 - p8 - p6, p11, p12 + p6, p5, p10 - p8 - p6);
         blitRepeating(p0, p1 + p5, p2 + p6, p3 - p7 - p5, p4 - p8 - p6, p11 + p5, p12 + p6, p9 - p7 - p5, p10 - p8 - p6);
         blitRepeating(p0, p1 + p3 - p7, p2 + p6, p5, p4 - p8 - p6, p11 + p9 - p7, p12 + p6, p7, p10 - p8 - p6);
      }
   }

   public static void blitRepeating(PoseStack p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8) {
      int p9 = p1;
      IntIterator p10 = slices(p3, p7);

      while (p10.hasNext()) {
         int p11 = p10.nextInt();
         int p12 = (p7 - p11) / 2;
         int p13 = p2;
         IntIterator p14 = slices(p4, p8);

         while (p14.hasNext()) {
            int p15 = p14.nextInt();
            int p16 = (p8 - p15) / 2;
            blit(p0, p9, p13, p5 + p12, p6 + p16, p11, p15);
            p13 += p15;
         }

         p9 += p11;
      }
   }

   private static IntIterator slices(int p0, int p1) {
      int p2 = Mth.positiveCeilDiv(p0, p1);
      return new Divisor(p0, p2);
   }

   static class ScissorStack {
      private final Deque<ScreenRectangle> stack = new ArrayDeque<>();

      public ScreenRectangle push(ScreenRectangle p0) {
         ScreenRectangle p1 = this.stack.peekLast();
         if (p1 != null) {
            ScreenRectangle p2 = Objects.requireNonNullElse(p0.intersection(p1), ScreenRectangle.empty());
            this.stack.addLast(p2);
            return p2;
         } else {
            this.stack.addLast(p0);
            return p0;
         }
      }

      public ScreenRectangle pop() {
         if (this.stack.isEmpty()) {
            throw new IllegalStateException("Scissor stack underflow");
         } else {
            this.stack.removeLast();
            return this.stack.peekLast();
         }
      }
   }
}

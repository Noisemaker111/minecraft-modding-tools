package mmt.standin.net.minecraft.client.gui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2fStack;
import org.joml.Quaternionfc;

/**
 * 1.21.5's GuiGraphics on 1.21.6.
 *
 * Read from both releases: 1.21.5's GuiGraphics drew at once into a buffer source through a 3D PoseStack (pose()), whose
 * pushes, translations, scales and rotations moved every later draw, z only ordering them; flush() drew what was batched
 * so later draws came on top; blit(renderTypeFor, texture, x, y, u, v, width, height, [regionWidth, regionHeight,]
 * textureWidth, textureHeight, color) drew with renderTypeFor(texture); drawString returned the x where the text ended
 * (x + font.width(text), + 1 with a shadow) and drew a colour with no alpha as opaque (Font.adjustColor). 1.21.6 records
 * draws into a GuiRenderState and orders them itself, putting each draw above any earlier one it overlaps
 * (GuiRenderState.findAppropriateNode), so there is nothing to flush; pose() is a 2D Matrix3x2fStack; blit takes the
 * RenderPipeline a render type was made with in the same argument order (each 1.21.5 GUI type's pipeline, known by
 * the type's name; any other type is drawn as GUI_TEXTURED, the textured GUI pipeline); drawString
 * returns nothing and skips a colour whose alpha is 0. So pose() gives, per GuiGraphics, a PoseStack whose pushes, pops,
 * translations, scales and z-rotations are also made on the 2D stack, z dropped as 1.21.6's own code drops it.
 *
 * Agrees with NeoForge's 1.21.5 -> 1.21.6 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.6/index.md, CC BY 4.0): "pose now returns a Matrix3x2fStack", "flush is removed", "All methods no longer take in a RenderType, VertexConsumer, or Function<ResourceLocation, RenderType>, instead specifying a RenderPipeline", "drawString, drawStringWithBackdrop no longer returns anything", "draw*String methods must now pass in an ARGB value, where A cannot be 0".
 */
public final class GuiGraphics {

    private static final Map<net.minecraft.client.gui.GuiGraphics, PoseStack> POSES = Collections.synchronizedMap(new WeakHashMap<>());

    private GuiGraphics() {}

    public static PoseStack pose(net.minecraft.client.gui.GuiGraphics self) {
        return POSES.computeIfAbsent(self, graphics -> new GuiGraphics$Pose(graphics.pose()));
    }

    public static void flush(net.minecraft.client.gui.GuiGraphics self) {
    }

    /** The pipeline of a GUI render type, known by its name (1.21.5's names, which RenderType's stand-in keeps). */
    private static RenderPipeline pipeline(Function renderTypeFor, ResourceLocation texture) {
        Object type = renderTypeFor.apply(texture);
        String name = type instanceof RenderType renderType ? renderType.name : "";
        if (name.equals("gui") || name.equals("gui_overlay") || name.equals("gui_ghost_recipe_overlay")) {
            return RenderPipelines.GUI;
        }
        if (name.equals("gui_text_highlight")) {
            return RenderPipelines.GUI_TEXT_HIGHLIGHT;
        }
        if (name.equals("gui_opaque_textured_background")) {
            return RenderPipelines.GUI_OPAQUE_TEXTURED_BACKGROUND;
        }
        return RenderPipelines.GUI_TEXTURED;
    }

    public static void blit(net.minecraft.client.gui.GuiGraphics self, Function renderTypeFor, ResourceLocation texture, int x, int y, float u, float v, int width, int height, int textureWidth, int textureHeight, int color) {
        self.blit(pipeline(renderTypeFor, texture), texture, x, y, u, v, width, height, textureWidth, textureHeight, color);
    }

    public static void blit(net.minecraft.client.gui.GuiGraphics self, Function renderTypeFor, ResourceLocation texture, int x, int y, float u, float v, int width, int height, int textureWidth, int textureHeight) {
        self.blit(pipeline(renderTypeFor, texture), texture, x, y, u, v, width, height, textureWidth, textureHeight);
    }

    public static void blit(net.minecraft.client.gui.GuiGraphics self, Function renderTypeFor, ResourceLocation texture, int x, int y, float u, float v, int width, int height, int regionWidth, int regionHeight, int textureWidth, int textureHeight) {
        self.blit(pipeline(renderTypeFor, texture), texture, x, y, u, v, width, height, regionWidth, regionHeight, textureWidth, textureHeight);
    }

    public static void blit(net.minecraft.client.gui.GuiGraphics self, Function renderTypeFor, ResourceLocation texture, int x, int y, float u, float v, int width, int height, int regionWidth, int regionHeight, int textureWidth, int textureHeight, int color) {
        self.blit(pipeline(renderTypeFor, texture), texture, x, y, u, v, width, height, regionWidth, regionHeight, textureWidth, textureHeight, color);
    }

    private static int opaque(int color) {
        return (color & 0xFC000000) == 0 ? color | 0xFF000000 : color;
    }

    public static int drawString(net.minecraft.client.gui.GuiGraphics self, Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
        self.drawString(font, text, x, y, opaque(color), shadow);
        return x + font.width(text) + (shadow ? 1 : 0);
    }

    public static int drawString(net.minecraft.client.gui.GuiGraphics self, Font font, FormattedCharSequence text, int x, int y, int color) {
        return drawString(self, font, text, x, y, color, true);
    }

    public static int drawString(net.minecraft.client.gui.GuiGraphics self, Font font, String text, int x, int y, int color, boolean shadow) {
        if (text == null) {
            return 0;
        }
        self.drawString(font, text, x, y, opaque(color), shadow);
        return x + font.width(text) + (shadow ? 1 : 0);
    }

    public static int drawString(net.minecraft.client.gui.GuiGraphics self, Font font, String text, int x, int y, int color) {
        return drawString(self, font, text, x, y, color, true);
    }

    public static int drawString(net.minecraft.client.gui.GuiGraphics self, Font font, net.minecraft.network.chat.Component text, int x, int y, int color, boolean shadow) {
        return drawString(self, font, text.getVisualOrderText(), x, y, color, shadow);
    }

    public static int drawString(net.minecraft.client.gui.GuiGraphics self, Font font, net.minecraft.network.chat.Component text, int x, int y, int color) {
        return drawString(self, font, text.getVisualOrderText(), x, y, color, true);
    }
}

/** A PoseStack that makes each push, pop, translation, scale and z-rotation on the GUI's 2D stack too. */
final class GuiGraphics$Pose extends PoseStack {

    private final Matrix3x2fStack gui;

    GuiGraphics$Pose(Matrix3x2fStack gui) {
        this.gui = gui;
    }

    @Override
    public void pushPose() {
        super.pushPose();
        this.gui.pushMatrix();
    }

    @Override
    public void popPose() {
        super.popPose();
        this.gui.popMatrix();
    }

    @Override
    public void translate(float x, float y, float z) {
        super.translate(x, y, z);
        this.gui.translate(x, y);
    }

    @Override
    public void translate(double x, double y, double z) {
        this.translate((float) x, (float) y, (float) z);
    }

    @Override
    public void scale(float x, float y, float z) {
        super.scale(x, y, z);
        this.gui.scale(x, y);
    }

    @Override
    public void mulPose(Quaternionfc rotation) {
        super.mulPose(rotation);
        float x = rotation.x();
        float y = rotation.y();
        float z = rotation.z();
        float w = rotation.w();
        this.gui.rotate((float) Math.atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z)));
    }

    @Override
    public void setIdentity() {
        super.setIdentity();
        this.gui.identity();
    }
}

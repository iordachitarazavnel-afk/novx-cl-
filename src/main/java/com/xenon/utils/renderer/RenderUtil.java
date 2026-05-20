package com.xenon.utils.renderer;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.xenon.utils.renderer.arc.ArcOutlinePipeline;
import com.xenon.utils.renderer.arc.ArcPipeline;
import com.xenon.utils.renderer.blur.KawasePipeline;
import com.xenon.utils.renderer.glass.LiquidGlassPipeline;
import com.xenon.utils.renderer.outline.OutlinePipeline;
import com.xenon.utils.renderer.rect.RectPipeline;
import com.xenon.utils.renderer.texture.TexturePipeline;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.util.Identifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import com.mojang.blaze3d.systems.RenderPass;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

public class RenderUtil {
    private static final List<Runnable> OVERRIDE_TASKS = new ArrayList<>();
    private static final float Z_OVERRIDE = 0.0f;
    private static final int FIXED_GUI_SCALE = 1;
    private static boolean scissorActive = false;
    private static int scissorX, scissorY, scissorWidth, scissorHeight;

    public static int getFixedScaledWidth() {
        var window = MinecraftClient.getInstance().getWindow();
        return (int) Math.ceil((double) window.getWidth() / FIXED_GUI_SCALE);
    }

    public static int getFixedScaledHeight() {
        var window = MinecraftClient.getInstance().getWindow();
        return (int) Math.ceil((double) window.getHeight() / FIXED_GUI_SCALE);
    }

    public static float getScaleFactor() {
        var window = MinecraftClient.getInstance().getWindow();
        int currentScale = window.getScaleFactor();
        return (float) currentScale / FIXED_GUI_SCALE;
    }

    public static float convertX(float x) {
        return x * getScaleFactor();
    }

    public static float convertY(float y) {
        return y * getScaleFactor();
    }

    public static float convertSize(float size) {
        return size * getScaleFactor();
    }

    public static Matrix4f createProjection() {
        return new Matrix4f().ortho(0, (float) getFixedScaledWidth(),
                (float) getFixedScaledHeight(), 0, -1000, 1000);
    }

    public static Matrix4f createProjection(DrawContext context) {
        var window = MinecraftClient.getInstance().getWindow();
        Matrix3x2f m = context.getMatrices();
        return new Matrix4f()
                .ortho(0, (float) window.getScaledWidth(), (float) window.getScaledHeight(), 0, -1000, 1000)
                .mul(new Matrix4f(
                        m.m00, m.m01, 0, 0,
                        m.m10, m.m11, 0, 0,
                        0, 0, 1, 0,
                        m.m20, m.m21, 0, 1));
    }

    public static boolean hasScreenOpen() {
        var client = MinecraftClient.getInstance();
        return client.currentScreen != null && !(client.currentScreen instanceof ChatScreen);
    }

    public static void drawRoundedRect(DrawContext context, float x, float y, float width, float height, float radius,
            int color,
            boolean overrideContext) {
        drawRoundedRect(createProjection(context), x, y, width, height, radius, color, overrideContext);
    }

    public static void drawRoundedRect(DrawContext context, float x, float y, float width, float height, float radius,
            boolean overrideContext, int... colors) {
        drawRoundedRect(createProjection(context), x, y, width, height, radius, radius, radius, radius, overrideContext,
                colors);
    }

    public static void drawRoundedRect(DrawContext context, float x, float y, float width, float height, float tl,
            float tr,
            float br, float bl,
            boolean overrideContext, int... colors) {
        drawRoundedRect(createProjection(context), x, y, width, height, tl, tr, br, bl, overrideContext, colors);
    }

    public static void drawRoundedRect(Matrix4f matrix, float x, float y, float width, float height, float radius,
            int color,
            boolean overrideContext) {
        drawRoundedRect(matrix, x, y, width, height, radius, radius, radius, radius, overrideContext, color);
    }

    public static void drawRoundedRect(Matrix4f matrix, float x, float y, float width, float height, float tl, float tr,
            float br,
            float bl,
            boolean overrideContext, int... colors) {
        if (overrideContext) {
            OVERRIDE_TASKS
                    .add(() -> RectPipeline.draw(matrix, x, y, width, height, tl, tr, br, bl, Z_OVERRIDE, colors));
            return;
        }
        RectPipeline.draw(matrix, x, y, width, height, tl, tr, br, bl, Z_OVERRIDE, colors);
    }

    public static void drawRoundedRect(Matrix4f matrix, float x, float y, float width, float height, float radius,
            boolean overrideContext, int... colors) {
        drawRoundedRect(matrix, x, y, width, height, radius, radius, radius, radius, overrideContext, colors);
    }

    public static void drawOutline(DrawContext context, float x, float y, float width, float height, float radius,
            float thickness, int color, boolean overrideContext) {
        drawOutline(createProjection(context), x, y, width, height, radius, radius, radius, radius, thickness, color,
                overrideContext);
    }

    public static void drawOutline(DrawContext context, float x, float y, float width, float height, float tl, float tr,
            float br, float bl,
            float thickness, int color, boolean overrideContext) {
        drawOutline(createProjection(context), x, y, width, height, tl, tr, br, bl, thickness, color, overrideContext);
    }

    public static void drawOutline(DrawContext context, float x, float y, float width, float height, float radius,
            float thickness, boolean overrideContext, int... colors) {
        drawOutline(createProjection(context), x, y, width, height, radius, radius, radius, radius, thickness,
                overrideContext,
                colors);
    }

    public static void drawOutline(DrawContext context, float x, float y, float width, float height, float tl, float tr,
            float br, float bl,
            float thickness, boolean overrideContext, int... colors) {
        drawOutline(createProjection(context), x, y, width, height, tl, tr, br, bl, thickness, overrideContext, colors);
    }

    public static void drawOutline(Matrix4f matrix, float x, float y, float width, float height, float radius,
            float thickness, int color, boolean overrideContext) {
        drawOutline(matrix, x, y, width, height, radius, radius, radius, radius, thickness, color, overrideContext);
    }

    public static void drawOutline(Matrix4f matrix, float x, float y, float width, float height, float tl, float tr,
            float br, float bl,
            float thickness, int color, boolean overrideContext) {
        if (overrideContext) {
            OVERRIDE_TASKS.add(
                    () -> OutlinePipeline.draw(matrix, x, y, width, height, tl, tr, br, bl, thickness, Z_OVERRIDE,
                            color));
            return;
        }
        OutlinePipeline.draw(matrix, x, y, width, height, tl, tr, br, bl, thickness, Z_OVERRIDE, color);
    }

    public static void drawOutline(Matrix4f matrix, float x, float y, float width, float height, float radius,
            float thickness, boolean overrideContext, int... colors) {
        drawOutline(matrix, x, y, width, height, radius, radius, radius, radius, thickness, overrideContext, colors);
    }

    public static void drawOutline(Matrix4f matrix, float x, float y, float width, float height, float tl, float tr,
            float br, float bl,
            float thickness, boolean overrideContext, int... colors) {
        if (overrideContext) {
            OVERRIDE_TASKS.add(
                    () -> OutlinePipeline.draw(matrix, x, y, width, height, tl, tr, br, bl, thickness, Z_OVERRIDE,
                            colors));
            return;
        }
        OutlinePipeline.draw(matrix, x, y, width, height, tl, tr, br, bl, thickness, Z_OVERRIDE, colors);
    }

    public static void drawBlur(DrawContext context, float x, float y, float width, float height, float radius,
            float strength, boolean overrideContext) {
        Matrix4f projection = createProjection(context);
        if (overrideContext) {
            OVERRIDE_TASKS
                    .add(() -> KawasePipeline.draw(projection, x, y, width, height, radius, strength, Z_OVERRIDE));
            return;
        }
        KawasePipeline.draw(projection, x, y, width, height, radius, strength, Z_OVERRIDE);
    }

    public static void drawArc(DrawContext context, float x, float y, float size, float thickness, float degree,
            float rotation, int color, boolean overrideContext) {
        drawArc(createProjection(context), x, y, size, thickness, degree, rotation, color, overrideContext);
    }

    public static void drawArc(DrawContext context, float x, float y, float size, float thickness, float degree,
            float rotation, boolean overrideContext, int... colors) {
        drawArc(createProjection(context), x, y, size, thickness, degree, rotation, overrideContext, colors);
    }

    public static void drawArc(Matrix4f matrix, float x, float y, float size, float thickness, float degree,
            float rotation,
            int color, boolean overrideContext) {
        if (overrideContext) {
            OVERRIDE_TASKS
                    .add(() -> ArcPipeline.draw(matrix, x, y, size, thickness, degree, rotation, Z_OVERRIDE, color));
            return;
        }
        ArcPipeline.draw(matrix, x, y, size, thickness, degree, rotation, Z_OVERRIDE, color);
    }

    public static void drawArc(Matrix4f matrix, float x, float y, float size, float thickness, float degree,
            float rotation,
            boolean overrideContext, int... colors) {
        if (overrideContext) {
            OVERRIDE_TASKS
                    .add(() -> ArcPipeline.draw(matrix, x, y, size, thickness, degree, rotation, Z_OVERRIDE, colors));
            return;
        }
        ArcPipeline.draw(matrix, x, y, size, thickness, degree, rotation, Z_OVERRIDE, colors);
    }

    public static void arcOutline(DrawContext context, float x, float y, float size, float arcThickness,
            float degree, float rotation, float outlineThickness, int fillColor, int outlineColor,
            boolean overrideContext) {
        arcOutline(createProjection(context), x, y, size, arcThickness, degree, rotation, outlineThickness, fillColor,
                outlineColor, overrideContext);
    }

    public static void arcOutline(Matrix4f matrix, float x, float y, float size, float arcThickness,
            float degree, float rotation, float outlineThickness, int fillColor, int outlineColor,
            boolean overrideContext) {
        if (overrideContext) {
            OVERRIDE_TASKS.add(() -> ArcOutlinePipeline.draw(matrix, x, y, size, arcThickness, degree, rotation,
                    outlineThickness, fillColor, outlineColor, Z_OVERRIDE));
            return;
        }
        ArcOutlinePipeline.draw(matrix, x, y, size, arcThickness, degree, rotation, outlineThickness, fillColor,
                outlineColor,
                Z_OVERRIDE);
    }

    public static void drawTexture(DrawContext context, float x, float y, float size, Identifier texture, int color,
            float radius, boolean overrideContext) {
        drawTexture(createProjection(context), x, y, size, texture, color, radius, overrideContext);
    }

    public static void drawTexture(Matrix4f matrix, float x, float y, float size, Identifier texture, int color,
            float radius,
            boolean overrideContext) {
        var client = MinecraftClient.getInstance();
        var tex = client.getTextureManager().getTexture(texture);
        if (tex != null) {
            if (overrideContext) {
                OVERRIDE_TASKS.add(
                        () -> TexturePipeline.draw(matrix, x, y, size, tex.getGlTextureView(), color, radius,
                                Z_OVERRIDE));
                return;
            }
            TexturePipeline.draw(matrix, x, y, size, tex.getGlTextureView(), color, radius, Z_OVERRIDE);
        }
    }

    public static void drawLiquidGlass(DrawContext context, float x, float y, float width, float height,
            float squirt, float power, float radius, int color, boolean overrideContext) {

        Matrix4f projection = createProjection(context);
        float[] radii = new float[] { radius * squirt / 2.0f, radius * squirt / 2.0f, radius * squirt / 2.0f,
                radius * squirt / 2.0f };
        float globalAlpha = ((color >> 24) & 0xFF) / 255.0f;
        float fresnelPower = height == 240.0f ? 100.0f : 50.0f;
        int fresnelColor = color | 0xFF000000;
        float baseAlpha = 1.0f;
        boolean fresnelInvert = true;
        float fresnelMix = 0.0f;
        float distortStrength = power;

        if (overrideContext) {
            OVERRIDE_TASKS.add(() -> LiquidGlassPipeline.draw(
                    projection, x, y, width, height, radii, color, globalAlpha, fresnelPower,
                    fresnelColor, baseAlpha, fresnelInvert, fresnelMix, distortStrength, squirt, Z_OVERRIDE));
            return;
        }
        LiquidGlassPipeline.draw(
                projection, x, y, width, height, radii, color, globalAlpha, fresnelPower,
                fresnelColor, baseAlpha, fresnelInvert, fresnelMix, distortStrength, squirt, Z_OVERRIDE);
    }

    public static void addOverrideTask(Runnable task) {
        OVERRIDE_TASKS.add(task);
    }

    public static void renderOverrides(DrawContext context) {
        if (OVERRIDE_TASKS.isEmpty())
            return;

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        OVERRIDE_TASKS.forEach(Runnable::run);
        OVERRIDE_TASKS.clear();
        GL11.glEnable(GL11.GL_DEPTH_TEST);

        // Reset scissor after overrides to prevent state bleed
        scissorActive = false;
        GlStateManager._disableScissorTest();
    }

    public static void clearOverrideTasks() {
        OVERRIDE_TASKS.clear();
    }

    public static boolean hasOverrideTasks() {
        return !OVERRIDE_TASKS.isEmpty();
    }

    public static void setScissor(float x, float y, float width, float height, boolean overrideContext) {
        if (overrideContext) {
            OVERRIDE_TASKS.add(() -> setScissorTasks(x, y, width, height));
            return;
        }
        setScissorTasks(x, y, width, height);
    }

    private static void setScissorTasks(float x, float y, float width, float height) {
        var window = MinecraftClient.getInstance().getWindow();
        double scale = window.getScaleFactor();
        int framebufferWidth = window.getFramebufferWidth();
        int framebufferHeight = window.getFramebufferHeight();

        scissorActive = true;
        scissorX = (int) x;
        scissorY = (int) y;
        scissorWidth = (int) width;
        scissorHeight = (int) height;

        // Convert from scaled screen coords to framebuffer coords for glScissor.
        int fbX = (int) Math.round(scissorX * scale);
        int fbY = (int) Math.round(scissorY * scale);
        int fbW = (int) Math.round(scissorWidth * scale);
        int fbH = (int) Math.round(scissorHeight * scale);

        int glX = fbX;
        int glY = framebufferHeight - (fbY + fbH);
        int glWidth = fbW;
        int glHeight = fbH;

        // Clamp to framebuffer bounds so negative/offscreen scissor rects can't leak.
        int x0 = Math.max(0, glX);
        int y0 = Math.max(0, glY);
        int x1 = Math.min(framebufferWidth, glX + glWidth);
        int y1 = Math.min(framebufferHeight, glY + glHeight);
        int cw = Math.max(0, x1 - x0);
        int ch = Math.max(0, y1 - y0);
        if (cw == 0 || ch == 0) {
            scissorActive = false;
            GlStateManager._disableScissorTest();
            return;
        }

        GlStateManager._enableScissorTest();
        GlStateManager._scissorBox(x0, y0, cw, ch);
    }

    public static void applyScissor(RenderPass pass) {
        if (scissorActive) {
            pass.enableScissor(scissorX, scissorY, scissorX + scissorWidth, scissorY + scissorHeight);
        }
    }

    public static void clearScissor(boolean overrideContext) {
        if (overrideContext) {
            OVERRIDE_TASKS.add(() -> {
                scissorActive = false;
                GlStateManager._disableScissorTest();
            });
            return;
        }
        scissorActive = false;
        GlStateManager._disableScissorTest();
    }

    public static boolean isOverrideActive() {
        var client = MinecraftClient.getInstance();
        return client.currentScreen == null || client.currentScreen instanceof ChatScreen;
    }

    public static void unscaledProjection(DrawContext context) {
        var window = MinecraftClient.getInstance().getWindow();
        double scale = window.getScaleFactor();
        context.getMatrices().scale((float) (1.0 / scale), (float) (1.0 / scale));
    }

    public static void scaledProjection(DrawContext context) {
        var window = MinecraftClient.getInstance().getWindow();
        double scale = window.getScaleFactor();
        context.getMatrices().scale((float) scale, (float) scale);
    }

    public static int multiplyColor(int color, float amount) {
        int a = color & 0xFF000000;
        float r = ((color >> 16) & 255) / 255.0f;
        float g = ((color >> 8) & 255) / 255.0f;
        float b = (color & 255) / 255.0f;
        r = Math.min(r * amount, 1.0f);
        g = Math.min(g * amount, 1.0f);
        b = Math.min(b * amount, 1.0f);
        int ir = (int) (r * 255);
        int ig = (int) (g * 255);
        int ib = (int) (b * 255);
        return a | (ir << 16) | (ig << 8) | ib;
    }

    public static int interpolateColor(int c1, int c2, float ratio) {
        int a1 = (c1 >> 24) & 0xFF;
        int r1 = (c1 >> 16) & 0xFF;
        int g1 = (c1 >> 8) & 0xFF;
        int b1 = c1 & 0xFF;
        int a2 = (c2 >> 24) & 0xFF;
        int r2 = (c2 >> 16) & 0xFF;
        int g2 = (c2 >> 8) & 0xFF;
        int b2 = c2 & 0xFF;
        int a = (int) (a1 + (a2 - a1) * ratio);
        int r = (int) (r1 + (r2 - r1) * ratio);
        int g = (int) (g1 + (g2 - g1) * ratio);
        int b = (int) (b1 + (b2 - b1) * ratio);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}

package com.xenon.utils.renderer.glass;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.xenon.utils.renderer.RenderUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.GpuSampler;
import net.minecraft.client.gl.UniformType;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.OptionalDouble;
import java.util.OptionalInt;

public class LiquidGlassPipeline {

    private static RenderPipeline pipeline;
    private static GpuBuffer uniformBuffer;
    /** std140: ends at 168, round to 16 */
    private static final int UNIFORM_SIZE = 176;

    public static void init() {
        if (pipeline != null)
            return;

        try {
            pipeline = RenderPipeline.builder()
                    .withLocation(Identifier.of("xenon", "liquidglass"))
                    .withVertexShader(Identifier.of("xenon", "liquidglass_vertex"))
                    .withFragmentShader(Identifier.of("xenon", "liquidglass_fragment"))
                    .withVertexFormat(VertexFormat.builder().build(), VertexFormat.DrawMode.TRIANGLES)
                    .withUniform("Uniforms", UniformType.UNIFORM_BUFFER)
                    .withSampler("Sampler0")
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withCull(false)
                    .build();

            uniformBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "LiquidGlass Uniforms",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    UNIFORM_SIZE);
        } catch (Exception e) {
            System.err.println("[LiquidGlass] Failed to init: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void draw(Matrix4f projection, float x, float y, float width, float height, float[] radii,
            int color, float globalAlpha, float fresnelPower, int fresnelColor, float baseAlpha, boolean fresnelInvert,
            float fresnelMix, float distortStrength, float squirt, float z) {
        if (pipeline == null)
            init();
        if (pipeline == null || uniformBuffer == null)
            return;

        MinecraftClient client = MinecraftClient.getInstance();
        Framebuffer fb = client.getFramebuffer();
        if (fb == null || fb.getColorAttachmentView() == null)
            return;

        float fr = ((fresnelColor >> 16) & 0xFF) / 255.0f;
        float fg = ((fresnelColor >> 8) & 0xFF) / 255.0f;
        float fbCol = (fresnelColor & 0xFF) / 255.0f;
        float fa = ((fresnelColor >> 24) & 0xFF) / 255.0f;

        float r0 = radii.length > 0 ? radii[0] : 0;
        float r1 = radii.length > 1 ? radii[1] : r0;
        float r2 = radii.length > 2 ? radii[2] : r0;
        float r3 = radii.length > 3 ? radii[3] : r0;

        int texW = fb.textureWidth;
        int texH = fb.textureHeight;

        ByteBuffer buffer = MemoryUtil.memAlloc(UNIFORM_SIZE);
        buffer.putFloat(projection.m00()).putFloat(projection.m01()).putFloat(projection.m02())
                .putFloat(projection.m03());
        buffer.putFloat(projection.m10()).putFloat(projection.m11()).putFloat(projection.m12())
                .putFloat(projection.m13());
        buffer.putFloat(projection.m20()).putFloat(projection.m21()).putFloat(projection.m22())
                .putFloat(projection.m23());
        buffer.putFloat(projection.m30()).putFloat(projection.m31()).putFloat(projection.m32())
                .putFloat(projection.m33());

        buffer.position(64);
        buffer.putFloat(x).putFloat(y).putFloat(width).putFloat(height);
        buffer.putFloat(texW).putFloat(texH);
        buffer.position(96);
        buffer.putFloat(r0).putFloat(r1).putFloat(r2).putFloat(r3);

        buffer.position(112);
        buffer.putFloat(squirt);
        buffer.putFloat(2.0f);
        buffer.putFloat(globalAlpha);
        buffer.putFloat(fresnelPower);

        buffer.position(128);
        buffer.putFloat(fr).putFloat(fg).putFloat(fbCol).putFloat(fa);

        buffer.position(144);
        buffer.putFloat(baseAlpha);
        buffer.putInt(fresnelInvert ? 1 : 0);
        buffer.putFloat(fresnelMix);
        buffer.putFloat(distortStrength);
        buffer.putFloat(z);
        buffer.putFloat(0);
        buffer.flip();

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.writeToBuffer(uniformBuffer.slice(), buffer);
        MemoryUtil.memFree(buffer);

        GpuSampler sampler = RenderSystem.getSamplerCache().get(FilterMode.LINEAR);

        try (RenderPass pass = encoder.createRenderPass(
                () -> "LiquidGlass",
                fb.getColorAttachmentView(),
                OptionalInt.empty(),
                fb.getDepthAttachmentView(),
                OptionalDouble.of(1.0))) {
            RenderUtil.applyScissor(pass);
            pass.setPipeline(pipeline);
            pass.setUniform("Uniforms", uniformBuffer);
            pass.bindTexture("Sampler0", fb.getColorAttachmentView(), sampler);
            pass.draw(0, 6);
        }
    }

    public static void shutdown() {
        if (uniformBuffer != null) {
            uniformBuffer.close();
            uniformBuffer = null;
        }
        pipeline = null;
    }
}

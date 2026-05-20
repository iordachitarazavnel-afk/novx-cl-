package com.xenon.utils.renderer.blur;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.xenon.utils.renderer.RenderUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GpuSampler;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.OptionalDouble;
import java.util.OptionalInt;

public class KawasePipeline {

    private static final int BLUR_ITERATIONS = 5;
    private static final int DOWNSAMPLE_SCALE = 2;
    private static final int BUFFER_SIZE = 256;

    private static final RenderPipeline PIPELINE_BLUR = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET)
                    .withLocation(Identifier.of("xenon", "pipeline/blur_pass"))
                    .withVertexShader(Identifier.of("xenon", "blur_pass_vertex"))
                    .withFragmentShader(Identifier.of("xenon", "blur_pass_fragment"))
                    .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
                    .withUniform("BlurData", UniformType.UNIFORM_BUFFER)
                    .withSampler("Sampler0")
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withDepthWrite(false)
                    .withCull(false)
                    .build());

    private static final RenderPipeline PIPELINE_FINAL = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET)
                    .withLocation(Identifier.of("xenon", "pipeline/blur_final"))
                    .withVertexShader(Identifier.of("xenon", "blur_final_vertex"))
                    .withFragmentShader(Identifier.of("xenon", "blur_final_fragment"))
                    .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
                    .withUniform("BlurData", UniformType.UNIFORM_BUFFER)
                    .withSampler("Sampler0")
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withDepthWrite(false)
                    .withCull(false)
                    .build());

    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f(0, 0, 0);
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

    private static GpuBuffer uniformBuffer;
    private static GpuBuffer dummyVertexBuffer;
    private static ByteBuffer dataBuffer;

    private static GpuTexture copyTexture;
    private static GpuTextureView copyTextureView;
    private static GpuTexture[] pingPongTextures = new GpuTexture[2];
    private static GpuTextureView[] pingPongViews = new GpuTextureView[2];
    private static int lastWidth = 0;
    private static int lastHeight = 0;
    private static boolean initialized = false;

    private static long lastFrameTime = -1;
    private static int cachedBlurSrc = 0;
    private static float cachedStrength = 0;

    public static void init() {
        if (initialized)
            return;

        dataBuffer = MemoryUtil.memAlloc(BUFFER_SIZE);

        ByteBuffer dummyData = MemoryUtil.memAlloc(4);
        dummyData.putInt(0);
        dummyData.flip();
        dummyVertexBuffer = RenderSystem.getDevice().createBuffer(
                () -> "xenon:blur_dummy_vertex",
                GpuBuffer.USAGE_VERTEX,
                dummyData);
        MemoryUtil.memFree(dummyData);

        initialized = true;
    }

    // In KawasePipeline
    public static void captureFramebuffer() {
        // call this BEFORE any widgets render
        MinecraftClient client = MinecraftClient.getInstance();
        int fbWidth = client.getFramebuffer().textureWidth;
        int fbHeight = client.getFramebuffer().textureHeight;
        ensureTextures(fbWidth, fbHeight);

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(
                client.getFramebuffer().getColorAttachment(),
                copyTexture,
                0, 0, 0, 0, 0, fbWidth, fbHeight);
        lastFrameTime = -1;
    }

    private static void ensureTextures(int fbWidth, int fbHeight) {
        int blurWidth = fbWidth / DOWNSAMPLE_SCALE;
        int blurHeight = fbHeight / DOWNSAMPLE_SCALE;

        if (copyTexture != null && fbWidth == lastWidth && fbHeight == lastHeight)
            return;

        if (copyTextureView != null) {
            copyTextureView.close();
            copyTextureView = null;
        }
        if (copyTexture != null) {
            copyTexture.close();
            copyTexture = null;
        }

        copyTexture = RenderSystem.getDevice().createTexture(
                () -> "xenon:blur_copy",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                TextureFormat.RGBA8,
                fbWidth, fbHeight, 1, 1);
        copyTextureView = RenderSystem.getDevice().createTextureView(copyTexture);

        for (int i = 0; i < 2; i++) {
            if (pingPongViews[i] != null) {
                pingPongViews[i].close();
                pingPongViews[i] = null;
            }
            if (pingPongTextures[i] != null) {
                pingPongTextures[i].close();
                pingPongTextures[i] = null;
            }

            int idx = i;
            pingPongTextures[i] = RenderSystem.getDevice().createTexture(
                    () -> "xenon:blur_pp_" + idx,
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
                    TextureFormat.RGBA8,
                    blurWidth, blurHeight, 1, 1);
            pingPongViews[i] = RenderSystem.getDevice().createTextureView(pingPongTextures[i]);
        }

        lastWidth = fbWidth;
        lastHeight = fbHeight;
        lastFrameTime = -1;
    }

    public static void draw(Matrix4f matrix, float x, float y, float width, float height, float radius,
            float strength, float z) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getFramebuffer() == null)
            return;
        if (client.getFramebuffer().getColorAttachment() == null)
            return;

        init();

        int fbWidth = client.getFramebuffer().textureWidth;
        int fbHeight = client.getFramebuffer().textureHeight;
        int blurWidth = fbWidth / DOWNSAMPLE_SCALE;
        int blurHeight = fbHeight / DOWNSAMPLE_SCALE;
        ensureTextures(fbWidth, fbHeight);

        long currentTime = System.nanoTime() / 16_666_666;
        boolean needsBlur = currentTime != lastFrameTime || Math.abs(strength - cachedStrength) > 0.01f;

        GpuSampler sampler = RenderSystem.getSamplerCache().get(FilterMode.LINEAR);
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .write(RenderSystem.getModelViewMatrix(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

        if (needsBlur) {
            encoder.copyTextureToTexture(
                    client.getFramebuffer().getColorAttachment(),
                    copyTexture,
                    0, 0, 0, 0, 0,
                    fbWidth, fbHeight);

            prepareBlurData(fbWidth, fbHeight, blurWidth, blurHeight, 1.0f, strength);
            encoder.writeToBuffer(uniformBuffer.slice(), dataBuffer);

            try (RenderPass downsample = encoder.createRenderPass(
                    () -> "xenon:blur_downsample",
                    pingPongViews[0],
                    OptionalInt.empty(),
                    null,
                    OptionalDouble.empty())) {

                downsample.setPipeline(PIPELINE_BLUR);
                downsample.setVertexBuffer(0, dummyVertexBuffer);
                downsample.bindTexture("Sampler0", copyTextureView, sampler);
                RenderSystem.bindDefaultUniforms(downsample);
                downsample.setUniform("DynamicTransforms", dynamicTransforms);
                downsample.setUniform("BlurData", uniformBuffer);
                downsample.draw(0, 6);
            }

            int iterations = Math.max(2, (int) (BLUR_ITERATIONS * strength));
            float[] offsets = { 1.0f, 2.0f, 2.0f, 3.0f };

            for (int i = 0; i < iterations; i++) {
                int src = i % 2;
                int dst = (i + 1) % 2;
                float offset = i < offsets.length ? offsets[i] : 3.0f;
                final int passIdx = i;

                prepareBlurData(blurWidth, blurHeight, blurWidth, blurHeight, offset, 1.0f);
                encoder.writeToBuffer(uniformBuffer.slice(), dataBuffer);

                try (RenderPass pass = encoder.createRenderPass(
                        () -> "xenon:blur_" + passIdx,
                        pingPongViews[dst],
                        OptionalInt.empty(),
                        null,
                        OptionalDouble.empty())) {

                    pass.setPipeline(PIPELINE_BLUR);
                    pass.setVertexBuffer(0, dummyVertexBuffer);
                    pass.bindTexture("Sampler0", pingPongViews[src], sampler);
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("DynamicTransforms", dynamicTransforms);
                    pass.setUniform("BlurData", uniformBuffer);
                    pass.draw(0, 6);
                }
            }

            cachedBlurSrc = iterations % 2;
            lastFrameTime = currentTime;
            cachedStrength = strength;
        }

        float[] radii = new float[] { radius, radius, radius, radius };
        int scaledW = RenderUtil.getFixedScaledWidth();
        int scaledH = RenderUtil.getFixedScaledHeight();
        prepareFinalData(matrix, x, y, width, height, scaledW, scaledH, radii, z);
        encoder.writeToBuffer(uniformBuffer.slice(), dataBuffer);

        try (RenderPass finalPass = encoder.createRenderPass(
                () -> "xenon:blur_final",
                client.getFramebuffer().getColorAttachmentView(),
                OptionalInt.empty(),
                client.getFramebuffer().getDepthAttachmentView(),
                OptionalDouble.of(1.0))) {
            RenderUtil.applyScissor(finalPass);
            finalPass.setPipeline(PIPELINE_FINAL);
            finalPass.setVertexBuffer(0, dummyVertexBuffer);
            finalPass.bindTexture("Sampler0", pingPongViews[cachedBlurSrc], sampler);
            RenderSystem.bindDefaultUniforms(finalPass);
            finalPass.setUniform("DynamicTransforms", dynamicTransforms);
            finalPass.setUniform("BlurData", uniformBuffer);
            finalPass.draw(0, 6);
        }
    }

    private static void prepareBlurData(int screenW, int screenH, int texW, int texH, float offset, float strength) {
        dataBuffer.clear();
        for (int i = 0; i < 16; i++)
            dataBuffer.putFloat(0);
        dataBuffer.putFloat(0).putFloat(0).putFloat(screenW).putFloat(screenH);
        dataBuffer.putFloat(screenW).putFloat(screenH).putFloat(1.0f).putFloat(offset);
        dataBuffer.putFloat(texW).putFloat(texH).putFloat(1.0f).putFloat(strength);
        dataBuffer.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
        dataBuffer.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
        dataBuffer.flip();
        ensureBuffer();
    }

    private static void prepareFinalData(Matrix4f matrix, float x, float y, float w, float h, int fbW, int fbH,
            float[] r, float z) {
        dataBuffer.clear();
        dataBuffer.putFloat(matrix.m00()).putFloat(matrix.m01()).putFloat(matrix.m02()).putFloat(matrix.m03());
        dataBuffer.putFloat(matrix.m10()).putFloat(matrix.m11()).putFloat(matrix.m12()).putFloat(matrix.m13());
        dataBuffer.putFloat(matrix.m20()).putFloat(matrix.m21()).putFloat(matrix.m22()).putFloat(matrix.m23());
        dataBuffer.putFloat(matrix.m30()).putFloat(matrix.m31()).putFloat(matrix.m32()).putFloat(matrix.m33());
        dataBuffer.putFloat(x).putFloat(y).putFloat(w).putFloat(h);
        dataBuffer.putFloat(fbW).putFloat(fbH).putFloat(0).putFloat(0);
        dataBuffer.putFloat(fbW).putFloat(fbH).putFloat(1.0f).putFloat(0);
        dataBuffer.putFloat(r[0]).putFloat(r[1]).putFloat(r[2]).putFloat(r[3]);
        dataBuffer.putFloat(z).putFloat(0).putFloat(0).putFloat(0); // uZ_Padding
        dataBuffer.flip();
        ensureBuffer();
    }

    private static void ensureBuffer() {
        int size = dataBuffer.remaining();
        if (uniformBuffer == null || uniformBuffer.size() < size) {
            if (uniformBuffer != null)
                uniformBuffer.close();
            uniformBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "xenon:blur_uniform",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, size);
        }
    }

    public static GpuTextureView getBlurTextureView() {
        if (!initialized || pingPongViews[cachedBlurSrc] == null)
            return null;
        return pingPongViews[cachedBlurSrc];
    }

    public static void shutdown() {
        if (uniformBuffer != null) {
            uniformBuffer.close();
            uniformBuffer = null;
        }
        if (dummyVertexBuffer != null) {
            dummyVertexBuffer.close();
            dummyVertexBuffer = null;
        }
        if (dataBuffer != null) {
            MemoryUtil.memFree(dataBuffer);
            dataBuffer = null;
        }
        if (copyTextureView != null) {
            copyTextureView.close();
            copyTextureView = null;
        }
        if (copyTexture != null) {
            copyTexture.close();
            copyTexture = null;
        }
        for (int i = 0; i < 2; i++) {
            if (pingPongViews[i] != null) {
                pingPongViews[i].close();
                pingPongViews[i] = null;
            }
            if (pingPongTextures[i] != null) {
                pingPongTextures[i].close();
                pingPongTextures[i] = null;
            }
        }
        lastWidth = 0;
        lastHeight = 0;
        initialized = false;
        lastFrameTime = -1;
    }
}
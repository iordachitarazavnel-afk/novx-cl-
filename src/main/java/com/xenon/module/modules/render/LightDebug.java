package com.xenon.module.modules.render;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.ModeSetting;
import com.xenon.setting.Setting;
import com.xenon.utils.RenderUtils;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.opengl.GL11;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class LightDebug extends Module {
    private static final int FACE_DOWN = 1;
    private static final int FACE_UP = 2;
    private static final int FACE_NORTH = 4;
    private static final int FACE_SOUTH = 8;
    private static final int FACE_WEST = 16;
    private static final int FACE_EAST = 32;
    private static final int FACE_VERTEX_STRIDE = 12;
    private static final int BLOCK_DATA_STRIDE = 5;
    private static final String COLOR_GRAYSCALE = "Grayscale";
    private static final String COLOR_OCEAN = "Ocean";
    private static final String COLOR_WARM = "Warm";
    private static final String COLOR_COOL = "Cool";
    private static final String COLOR_CLASSIC = "Classic";
    private static final int DEFAULT_CHUNK_RADIUS = 8;
    private static final int MIN_CHUNK_RADIUS = 1;
    private static final int MAX_CHUNK_RADIUS = 12;
    private static final int DEFAULT_MAX_Y = -50;
    private static final int MIN_MAX_Y = -63;
    private static final int MAX_MAX_Y = 16;
    private static final float DEFAULT_ALPHA = 160f;
    private static final int REFRESH_INTERVAL_TICKS = 16;

    private final ModeSetting colorMode = new ModeSetting(
            "Color",
            COLOR_CLASSIC,
            new String[]{"ColorMode", "Color Mode"},
            COLOR_GRAYSCALE,
            COLOR_OCEAN,
            COLOR_WARM,
            COLOR_COOL,
            COLOR_CLASSIC
    );
    private final Setting<Float> alpha = new Setting<>("Alpha", DEFAULT_ALPHA, 0f, 255f);
    private final Setting<Integer> maxY = new Setting<>("MaxY", DEFAULT_MAX_Y, MIN_MAX_Y, MAX_MAX_Y) {
        @Override
        public boolean matchesName(String settingName) {
            return super.matchesName(settingName)
                    || "Max Y".equalsIgnoreCase(settingName)
                    || "Height".equalsIgnoreCase(settingName);
        }
    };
    private final Setting<Integer> chunkRadius = new Setting<>("ChunkRadius", DEFAULT_CHUNK_RADIUS, MIN_CHUNK_RADIUS, MAX_CHUNK_RADIUS) {
        @Override
        public boolean matchesName(String settingName) {
            return super.matchesName(settingName)
                    || "Chunk Radius".equalsIgnoreCase(settingName)
                    || "RenderDistance".equalsIgnoreCase(settingName);
        }
    };

    private static final int FIXED_MIN_Y = -64;
    private static final ChunkRenderData[] EMPTY_RENDER_CHUNKS = new ChunkRenderData[0];

    private final Map<Long, ChunkRenderData> chunkCache = new ConcurrentHashMap<>();
    private volatile ChunkRenderData[] renderChunks = EMPTY_RENDER_CHUNKS;

    private volatile ChunkPos  lastPlayerChunk = null;
    private volatile ClientWorld lastWorld     = null;
    private volatile int lastScannedRadius = Integer.MIN_VALUE;
    private volatile int lastScannedMaxY = Integer.MIN_VALUE;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "xenon-light-debug");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean isScanning = new AtomicBoolean(false);
    private final AtomicBoolean scanQueued = new AtomicBoolean(false);
    private final AtomicBoolean fullRescanRequested = new AtomicBoolean(true);
    private final AtomicBoolean refreshVisibleRequested = new AtomicBoolean(false);
    private final AtomicBoolean recolorVisibleRequested = new AtomicBoolean(false);
    private volatile long tickCounter = 0L;
    private volatile long lastRefreshTick = Long.MIN_VALUE;
    private volatile String lastColorMode = "";


    public LightDebug() {
        super("Light Debug", Category.RENDER);
        addSetting(colorMode);
        addSetting(alpha);
        addSetting(maxY);
        addSetting(chunkRadius);
    }


    @Override
    public void onEnable() {
        resetCache();
        fullRescanRequested.set(true);
        refreshVisibleRequested.set(true);
        recolorVisibleRequested.set(true);
        triggerScan();
    }

    @Override
    public void onDisable() {
        resetCache();
    }

    @Override
    public void onTick() {
        if (mc.world == null || mc.player == null) return;
        tickCounter++;
        ChunkPos current = mc.player.getChunkPos();
        long currentTick = tickCounter;
        int scanRadius = getScanRadius();
        int scanMaxY = getScanMaxY();
        String currentColorMode = colorMode.getValue();

        boolean moved = !current.equals(lastPlayerChunk);
        boolean worldChanged = mc.world != lastWorld;
        boolean boundsChanged = scanRadius != lastScannedRadius || scanMaxY != lastScannedMaxY;
        boolean colorModeChanged = !currentColorMode.equalsIgnoreCase(lastColorMode);

        if (worldChanged || boundsChanged) {
            fullRescanRequested.set(true);
        }
        if (colorModeChanged) {
            recolorVisibleRequested.set(true);
        }
        if (currentTick - lastRefreshTick >= REFRESH_INTERVAL_TICKS) {
            refreshVisibleRequested.set(true);
        }
        if (moved) {
            lastPlayerChunk = current;
        }
        if (moved || fullRescanRequested.get() || refreshVisibleRequested.get() || recolorVisibleRequested.get()) {
            triggerScan();
        }

        lastColorMode = currentColorMode;
    }

    private void triggerScan() {
        scanQueued.set(true);
        if (isScanning.compareAndSet(false, true)) {
            executor.submit(this::runPendingScans);
        }
    }

    private void runPendingScans() {
        try {
            while (scanQueued.getAndSet(false)) {
                rebuildVisibleChunks();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            isScanning.set(false);
            if (scanQueued.get() && isScanning.compareAndSet(false, true)) {
                executor.submit(this::runPendingScans);
            }
        }
    }

    private void rebuildVisibleChunks() {
        if (!isEnabled() || mc.world == null || mc.player == null) {
            return;
        }

        ClientWorld world = mc.world;
        ChunkPos center = mc.player.getChunkPos();
        int radius = getScanRadius();
        int scanMaxY = getScanMaxY();

        boolean fullRescan = fullRescanRequested.getAndSet(false)
                || world != lastWorld
                || radius != lastScannedRadius
                || scanMaxY != lastScannedMaxY;
        boolean refreshVisible = fullRescan || refreshVisibleRequested.getAndSet(false);
        boolean recolorVisible = fullRescan || refreshVisible || recolorVisibleRequested.getAndSet(false);
        long colorTick = tickCounter;

        LongSet visibleChunkKeys = collectVisibleChunkKeys(center, radius);
        Map<Long, ChunkRenderData> nextCache = fullRescan
                ? new HashMap<>(visibleChunkKeys.size())
                : new HashMap<>(chunkCache);

        if (!fullRescan) {
            nextCache.keySet().removeIf(key -> !visibleChunkKeys.contains(key));
        }

        for (long chunkKey : visibleChunkKeys) {
            ChunkRenderData existing = nextCache.get(chunkKey);
            if (!refreshVisible && existing != null) {
                if (recolorVisible) {
                    nextCache.put(chunkKey, recolorChunk(existing));
                }
                continue;
            }

            int chunkX = ChunkPos.getPackedX(chunkKey);
            int chunkZ = ChunkPos.getPackedZ(chunkKey);
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                nextCache.remove(chunkKey);
                continue;
            }

            WorldChunk chunk = world.getChunkManager().getWorldChunk(chunkX, chunkZ, false);
            if (chunk == null) {
                nextCache.remove(chunkKey);
                continue;
            }

            ChunkRenderData chunkData = scanChunk(world, chunk, scanMaxY);
            if (chunkData == null) {
                nextCache.remove(chunkKey);
            } else {
                nextCache.put(chunkKey, chunkData);
            }
        }

        if (!isEnabled()) {
            return;
        }

        chunkCache.clear();
        chunkCache.putAll(nextCache);
        renderChunks = nextCache.values().toArray(new ChunkRenderData[0]);
        lastWorld = world;
        lastPlayerChunk = center;
        lastScannedRadius = radius;
        lastScannedMaxY = scanMaxY;
        if (refreshVisible) {
            lastRefreshTick = colorTick;
        }
    }

    private ChunkRenderData scanChunk(ClientWorld world, WorldChunk chunk, int scanMaxY) {
        ChunkPos chunkPos = chunk.getPos();
        int startX = chunkPos.getStartX();
        int startZ = chunkPos.getStartZ();
        int endX = startX + 15;
        int endZ = startZ + 15;
        int yRange = scanMaxY - FIXED_MIN_Y + 1;
        Long2IntOpenHashMap litBlocks = new Long2IntOpenHashMap(Math.max(512, 18 * 18 * yRange / 4));
        litBlocks.defaultReturnValue(0);
        BlockPos.Mutable mutablePos = new BlockPos.Mutable();

        for (int wx = startX - 1; wx <= endX + 1; wx++) {
            for (int wz = startZ - 1; wz <= endZ + 1; wz++) {
                for (int y = FIXED_MIN_Y; y <= scanMaxY; y++) {
                    mutablePos.set(wx, y, wz);

                    int blockLight = world.getLightLevel(LightType.BLOCK, mutablePos);
                    if (blockLight <= 0) {
                        continue;
                    }

                    int skyLight = world.getLightLevel(LightType.SKY, mutablePos);
                    if (blockLight <= skyLight) {
                        continue;
                    }

                    litBlocks.put(BlockPos.asLong(wx, y, wz), blockLight);
                }
            }
        }

        if (litBlocks.isEmpty()) {
            return null;
        }

        LongSet litSet = litBlocks.keySet();
        int[] data = new int[litBlocks.size() * BLOCK_DATA_STRIDE];
        int count = 0;
        int minY = Integer.MAX_VALUE;

        for (Long2IntMap.Entry entry : litBlocks.long2IntEntrySet()) {
            long key = entry.getLongKey();
            int wx = BlockPos.unpackLongX(key);
            int wy = BlockPos.unpackLongY(key);
            int wz = BlockPos.unpackLongZ(key);

            if (wx < startX || wx > endX || wz < startZ || wz > endZ) {
                continue;
            }

            int flags = 0;
            if (!litSet.contains(BlockPos.asLong(wx, wy - 1, wz))) flags |= FACE_DOWN;
            if (!litSet.contains(BlockPos.asLong(wx, wy + 1, wz))) flags |= FACE_UP;
            if (!litSet.contains(BlockPos.asLong(wx, wy, wz - 1))) flags |= FACE_NORTH;
            if (!litSet.contains(BlockPos.asLong(wx, wy, wz + 1))) flags |= FACE_SOUTH;
            if (!litSet.contains(BlockPos.asLong(wx - 1, wy, wz))) flags |= FACE_WEST;
            if (!litSet.contains(BlockPos.asLong(wx + 1, wy, wz))) flags |= FACE_EAST;
            if (flags == 0) {
                continue;
            }

            int idx = count * BLOCK_DATA_STRIDE;
            data[idx] = wx;
            data[idx + 1] = wy;
            data[idx + 2] = wz;
            data[idx + 3] = flags;
            data[idx + 4] = entry.getIntValue();
            count++;

            if (wy < minY) {
                minY = wy;
            }
        }

        if (count == 0) {
            return null;
        }

        int[] blockData = Arrays.copyOf(data, count * BLOCK_DATA_STRIDE);
        return buildChunkRenderData(startX, startZ, minY, blockData, count);
    }

    private LongSet collectVisibleChunkKeys(ChunkPos center, int radius) {
        int diameter = radius * 2 + 1;
        LongOpenHashSet visibleChunks = new LongOpenHashSet(diameter * diameter);
        for (int cx = center.x - radius; cx <= center.x + radius; cx++) {
            for (int cz = center.z - radius; cz <= center.z + radius; cz++) {
                visibleChunks.add(ChunkPos.toLong(cx, cz));
            }
        }
        return visibleChunks;
    }

    private int getScanRadius() {
        return MathHelper.clamp(chunkRadius.getValue(), MIN_CHUNK_RADIUS, MAX_CHUNK_RADIUS);
    }

    private int getScanMaxY() {
        return MathHelper.clamp(maxY.getValue(), FIXED_MIN_Y, MAX_MAX_Y);
    }

    private void resetCache() {
        chunkCache.clear();
        renderChunks = EMPTY_RENDER_CHUNKS;
        lastPlayerChunk = null;
        lastWorld = null;
        lastScannedRadius = Integer.MIN_VALUE;
        lastScannedMaxY = Integer.MIN_VALUE;
        scanQueued.set(false);
        fullRescanRequested.set(true);
        refreshVisibleRequested.set(false);
        recolorVisibleRequested.set(false);
        tickCounter = 0L;
        lastRefreshTick = Long.MIN_VALUE;
        lastColorMode = colorMode.getValue();
    }

    private ChunkRenderData buildChunkRenderData(int minX, int minZ, int minY, int[] blockData, int blockCount) {
        int faceCount = countFaces(blockData, blockCount);
        short[] faceVertices = bakeFaceVertices(blockData, blockCount, minX, minZ, minY, faceCount);
        int[] faceColors = bakeFaceColors(blockData, blockCount, faceCount);
        return new ChunkRenderData(minX, minZ, minY, blockData, blockCount, faceVertices, faceColors, faceCount);
    }

    private ChunkRenderData recolorChunk(ChunkRenderData source) {
        int[] recoloredFaces = bakeFaceColors(source.blockData, source.blockCount, source.faceCount);
        return new ChunkRenderData(
                source.minX,
                source.minZ,
                source.minY,
                source.blockData,
                source.blockCount,
                source.faceVertices,
                recoloredFaces,
                source.faceCount
        );
    }

    private int countFaces(int[] data, int count) {
        int faces = 0;
        for (int i = 0, end = count * BLOCK_DATA_STRIDE; i < end; i += BLOCK_DATA_STRIDE) {
            faces += Integer.bitCount(data[i + 3]);
        }
        return faces;
    }

    private short[] bakeFaceVertices(int[] data, int count, int startX, int startZ, int minY, int faceCount) {
        short[] faceVertices = new short[faceCount * FACE_VERTEX_STRIDE];
        int faceIndex = 0;
        for (int i = 0, end = count * BLOCK_DATA_STRIDE; i < end; i += BLOCK_DATA_STRIDE) {
            short x1 = (short) (data[i] - startX);
            short y1 = (short) (data[i + 1] - minY);
            short z1 = (short) (data[i + 2] - startZ);
            short x2 = (short) (x1 + 1);
            short y2 = (short) (y1 + 1);
            short z2 = (short) (z1 + 1);
            int flags = data[i + 3];

            if ((flags & FACE_DOWN) != 0) {
                writeFaceVertices(faceVertices, faceIndex++, x1, y1, z1, x1, y1, z2, x2, y1, z2, x2, y1, z1);
            }
            if ((flags & FACE_UP) != 0) {
                writeFaceVertices(faceVertices, faceIndex++, x1, y2, z1, x2, y2, z1, x2, y2, z2, x1, y2, z2);
            }
            if ((flags & FACE_NORTH) != 0) {
                writeFaceVertices(faceVertices, faceIndex++, x1, y1, z1, x2, y1, z1, x2, y2, z1, x1, y2, z1);
            }
            if ((flags & FACE_SOUTH) != 0) {
                writeFaceVertices(faceVertices, faceIndex++, x1, y1, z2, x1, y2, z2, x2, y2, z2, x2, y1, z2);
            }
            if ((flags & FACE_WEST) != 0) {
                writeFaceVertices(faceVertices, faceIndex++, x1, y1, z1, x1, y2, z1, x1, y2, z2, x1, y1, z2);
            }
            if ((flags & FACE_EAST) != 0) {
                writeFaceVertices(faceVertices, faceIndex++, x2, y1, z1, x2, y1, z2, x2, y2, z2, x2, y2, z1);
            }
        }
        return faceVertices;
    }

    private int[] bakeFaceColors(int[] data, int count, int faceCount) {
        int[] faceColors = new int[faceCount];
        int faceIndex = 0;
        for (int i = 0, end = count * BLOCK_DATA_STRIDE; i < end; i += BLOCK_DATA_STRIDE) {
            int color = computePackedColor(data[i + 4]);
            int flags = data[i + 3];
            int repeats = Integer.bitCount(flags);
            Arrays.fill(faceColors, faceIndex, faceIndex + repeats, color);
            faceIndex += repeats;
        }
        return faceColors;
    }

    private void writeFaceVertices(
            short[] faceVertices,
            int faceIndex,
            short x1,
            short y1,
            short z1,
            short x2,
            short y2,
            short z2,
            short x3,
            short y3,
            short z3,
            short x4,
            short y4,
            short z4
    ) {
        int vertexOffset = faceIndex * FACE_VERTEX_STRIDE;
        faceVertices[vertexOffset] = x1;
        faceVertices[vertexOffset + 1] = y1;
        faceVertices[vertexOffset + 2] = z1;
        faceVertices[vertexOffset + 3] = x2;
        faceVertices[vertexOffset + 4] = y2;
        faceVertices[vertexOffset + 5] = z2;
        faceVertices[vertexOffset + 6] = x3;
        faceVertices[vertexOffset + 7] = y3;
        faceVertices[vertexOffset + 8] = z3;
        faceVertices[vertexOffset + 9] = x4;
        faceVertices[vertexOffset + 10] = y4;
        faceVertices[vertexOffset + 11] = z4;
    }

    private int computePackedColor(int lightLevel) {
        float t = MathHelper.clamp(lightLevel / 15f, 0f, 1f);
        String selected = colorMode.getValue();

        return switch (selected) {
            case COLOR_GRAYSCALE -> grayscaleColor(t);
            case COLOR_OCEAN -> threeStepColor(0x0A1F4D, 0x1F63D8, 0x4AE6FF, t);
            case COLOR_WARM -> warmColor(t);
            case COLOR_COOL -> coolColor(t);
            case COLOR_CLASSIC -> classicColor(t);
            default -> classicColor(t);
        };
    }

    private int classicColor(float t) {
        int r = (int) (t * 255f);
        int g = (int) (50f + t * 205f);
        int b = (int) (200f * (1f - t));
        return (r << 16) | (g << 8) | b;
    }

    private int grayscaleColor(float t) {
        int value = MathHelper.clamp(Math.round(t * 255f), 0, 255);
        return (value << 16) | (value << 8) | value;
    }

    private int warmColor(float t) {
        return threeStepColor(0xB01818, 0xFF7A1A, 0xFFE95A, t);
    }

    private int coolColor(float t) {
        return threeStepColor(0x123D9B, 0x16D8FF, 0xFFFFFF, t);
    }

    private int threeStepColor(int lowRgb, int midRgb, int highRgb, float t) {
        float clamped = MathHelper.clamp(t, 0f, 1f);
        if (clamped <= 0.5f) {
            return lerpColor(lowRgb, midRgb, clamped * 2f);
        }
        return lerpColor(midRgb, highRgb, (clamped - 0.5f) * 2f);
    }

    private int lerpColor(int fromRgb, int toRgb, float t) {
        float clamped = MathHelper.clamp(t, 0f, 1f);
        int r1 = (fromRgb >> 16) & 0xFF;
        int g1 = (fromRgb >> 8) & 0xFF;
        int b1 = fromRgb & 0xFF;
        int r2 = (toRgb >> 16) & 0xFF;
        int g2 = (toRgb >> 8) & 0xFF;
        int b2 = toRgb & 0xFF;
        int r = Math.round(r1 + (r2 - r1) * clamped);
        int g = Math.round(g1 + (g2 - g1) * clamped);
        int b = Math.round(b1 + (b2 - b1) * clamped);
        return (r << 16) | (g << 8) | b;
    }

    @Override
    public void onRender(MatrixStack matrices, float tickDelta) {
        if (mc.world == null || mc.player == null) return;

        ChunkRenderData[] chunks = renderChunks;
        if (chunks.length == 0) return;

        Camera cam = RenderUtils.getCamera();
        if (cam == null) return;
        Vec3d camPos = RenderUtils.getCameraPos(cam);

        int alphaMask = clampAlpha(alpha.getValue()) << 24;
        if (alphaMask == 0) {
            return;
        }
        double px = camPos.x;
        double py = camPos.y;
        double pz = camPos.z;

        BufferAllocator allocator = new BufferAllocator(2 * 1024 * 1024);
        VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
        VertexConsumer buf = immediate.getBuffer(RenderLayers.debugFilledBox());
        boolean hasGeometry = false;

        for (ChunkRenderData chunk : chunks) {
            if (chunk == null) {
                continue;
            }

            matrices.push();
            matrices.translate(chunk.minX - px, chunk.minY - py, chunk.minZ - pz);
            MatrixStack.Entry chunkEntry = matrices.peek();
            short[] faceVertices = chunk.faceVertices;
            int[] faceColors = chunk.faceColors;
            for (int face = 0, vertexOffset = 0; face < chunk.faceCount; face++, vertexOffset += FACE_VERTEX_STRIDE) {
                int argb = alphaMask | faceColors[face];
                buf.vertex(chunkEntry, faceVertices[vertexOffset], faceVertices[vertexOffset + 1], faceVertices[vertexOffset + 2]).color(argb);
                buf.vertex(chunkEntry, faceVertices[vertexOffset + 3], faceVertices[vertexOffset + 4], faceVertices[vertexOffset + 5]).color(argb);
                buf.vertex(chunkEntry, faceVertices[vertexOffset + 6], faceVertices[vertexOffset + 7], faceVertices[vertexOffset + 8]).color(argb);
                buf.vertex(chunkEntry, faceVertices[vertexOffset + 9], faceVertices[vertexOffset + 10], faceVertices[vertexOffset + 11]).color(argb);
                hasGeometry = true;
            }
            matrices.pop();
        }

        if (!hasGeometry) {
            allocator.close();
            return;
        }
        boolean depthWasEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean polyOffsetWasEnabled = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(-1.0f, -1.0f);
        immediate.draw();
        GL11.glPolygonOffset(0.0f, 0.0f);
        if (!polyOffsetWasEnabled) {
            GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        }
        GL11.glDepthMask(true);
        if (!depthWasEnabled) {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }
        allocator.close();
    }

    private int clampAlpha(float value) {
        return MathHelper.clamp(Math.round(value), 0, 255);
    }

    private static final class ChunkRenderData {
        private final int minX;
        private final int minZ;
        private final int minY;
        private final int[] blockData;
        private final int blockCount;
        private final short[] faceVertices;
        private final int[] faceColors;
        private final int faceCount;

        private ChunkRenderData(
                int minX,
                int minZ,
                int minY,
                int[] blockData,
                int blockCount,
                short[] faceVertices,
                int[] faceColors,
                int faceCount
        ) {
            this.minX = minX;
            this.minZ = minZ;
            this.minY = minY;
            this.blockData = blockData;
            this.blockCount = blockCount;
            this.faceVertices = faceVertices;
            this.faceColors = faceColors;
            this.faceCount = faceCount;
        }
    }
}

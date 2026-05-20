package com.xenon.module.modules.render;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.Setting;
import com.xenon.utils.RenderUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class HoleESP extends Module {
    private static final int MAX_CHUNKS_PER_TICK = 200;
    private static final int FIXED_MIN_DEPTH = 7;

    private final Setting<Double> alpha = new Setting<>("Fill Alpha", 60.0, 0.0, 255.0);
    private final Setting<Color> color = new Setting<>("Color", new Color(255, 100, 0));
    private final Setting<Double> range = new Setting<>("Range", 64.0, 16.0, 128.0);
    private final Setting<Boolean> gradientFill = new Setting<>("Gradient Fill", true);

    private final Map<Long, TrackedChunk> chunks = new ConcurrentHashMap<>();
    private final Queue<Long> chunkQueue = new ArrayDeque<>();
    private final Set<Long> queuedChunks = ConcurrentHashMap.newKeySet();
    private final Set<HoleData> holes = ConcurrentHashMap.newKeySet();

    private ExecutorService executor;
    private ClientWorld currentWorld;

    public HoleESP() {
        super("Hole ESP", Category.RENDER);
        addSetting(alpha);
        addSetting(color);
        addSetting(range);
        addSetting(gradientFill);
    }

    @Override
    public void onEnable() {
        currentWorld = mc.world;
        ensureExecutor();
        clear();
    }

    @Override
    public void onDisable() {
        shutdownExecutor();
        clear();
        currentWorld = null;
    }

    @Override
    public void onTick() {
        if (mc.world == null || mc.player == null) {
            return;
        }

        if (mc.world != currentWorld) {
            currentWorld = mc.world;
            clear();
        }

        ensureExecutor();
        updateChunks();
    }

    @Override
    public void onRender(MatrixStack matrices, float tickDelta) {
        if (mc.world == null || mc.player == null || holes.isEmpty()) {
            return;
        }

        Camera cam = RenderUtils.getCamera();
        if (cam == null) {
            return;
        }

        Vec3d camPos = RenderUtils.getCameraPos(cam);
        int fillAlpha = clampAlpha(alpha.getValue());
        boolean renderGradient = gradientFill.getValue();

        BufferAllocator allocator = new BufferAllocator(2 * 1024 * 1024);
        VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
        VertexConsumer fillConsumer = immediate.getBuffer(RenderLayers.debugFilledBox());
        MatrixStack.Entry entry = matrices.peek();
        boolean rendered = false;

        for (HoleData hole : holes) {
            if (!hole.isReadyToRender()) {
                continue;
            }

            Box worldBox = hole.box;
            if (!RenderUtils.isWorldBoxVisible(worldBox.minX, worldBox.minY, worldBox.minZ, worldBox.maxX, worldBox.maxY, worldBox.maxZ)) {
                continue;
            }

            Color baseColor = color.getValue();
            Color fillColor = withAlpha(baseColor, fillAlpha);

            Box relativeBox = new Box(
                    worldBox.minX - camPos.x,
                    worldBox.minY - camPos.y,
                    worldBox.minZ - camPos.z,
                    worldBox.maxX - camPos.x,
                    worldBox.maxY - camPos.y,
                    worldBox.maxZ - camPos.z
            );

            if (renderGradient) {
                renderGradientBox(fillConsumer, entry, relativeBox, baseColor, fillAlpha);
            } else {
                renderFilledBox(fillConsumer, entry, relativeBox, toArgb(fillColor));
            }

            rendered = true;
        }

        if (!rendered) {
            allocator.close();
            return;
        }

        boolean depthWasEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        immediate.draw();
        GL11.glDepthMask(true);
        if (depthWasEnabled) {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
        }
        allocator.close();
    }

    private void renderFilledBox(VertexConsumer consumer, MatrixStack.Entry entry, Box box, int color) {
        float minX = (float) box.minX;
        float minY = (float) box.minY;
        float minZ = (float) box.minZ;
        float maxX = (float) box.maxX;
        float maxY = (float) box.maxY;
        float maxZ = (float) box.maxZ;

        emitQuad(consumer, entry, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ, color);
        emitQuad(consumer, entry, minX, maxY, minZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, color);
        emitQuad(consumer, entry, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ, maxX, minY, minZ, color);
        emitQuad(consumer, entry, minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ, color);
        emitQuad(consumer, entry, minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ, color);
        emitQuad(consumer, entry, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, maxX, minY, maxZ, color);
    }

    private void renderGradientBox(VertexConsumer consumer, MatrixStack.Entry entry, Box box, Color baseColor, int maxAlpha) {
        double height = Math.max(0.001D, box.maxY - box.minY);
        int slices = Math.max(1, MathHelper.ceil(height));
        int baseAlpha = Math.max(6, Math.round(maxAlpha * 0.18f));
        float minX = (float) box.minX;
        float minZ = (float) box.minZ;
        float maxX = (float) box.maxX;
        float maxZ = (float) box.maxZ;
        int topCapColor = 0;
        int bottomCapColor = 0;

        for (int slice = 0; slice < slices; slice++) {
            double startProgress = (double) slice / slices;
            double endProgress = (double) (slice + 1) / slices;
            float sliceMinY = (float) MathHelper.lerp(startProgress, box.minY, box.maxY);
            float sliceMaxY = (float) MathHelper.lerp(endProgress, box.minY, box.maxY);

            float bottomFade = 1.0f - ((float) slice / Math.max(1, slices - 1));
            float topFade = 1.0f - ((float) (slice + 1) / Math.max(1, slices));
            int sliceBottomColor = toArgb(withAlpha(baseColor, Math.max(baseAlpha, Math.round(maxAlpha * bottomFade))));
            int sliceTopColor = toArgb(withAlpha(baseColor, Math.max(baseAlpha, Math.round(maxAlpha * topFade))));

            if (slice == 0) {
                bottomCapColor = sliceBottomColor;
            }
            if (slice == slices - 1) {
                topCapColor = sliceTopColor;
            }

            emitVerticalGradientQuad(consumer, entry, minX, sliceMinY, minZ, minX, sliceMaxY, minZ, maxX, sliceMaxY, minZ, maxX, sliceMinY, minZ, sliceBottomColor, sliceTopColor);
            emitVerticalGradientQuad(consumer, entry, minX, sliceMinY, maxZ, maxX, sliceMinY, maxZ, maxX, sliceMaxY, maxZ, minX, sliceMaxY, maxZ, sliceBottomColor, sliceTopColor);
            emitVerticalGradientQuad(consumer, entry, minX, sliceMinY, minZ, minX, sliceMinY, maxZ, minX, sliceMaxY, maxZ, minX, sliceMaxY, minZ, sliceBottomColor, sliceTopColor);
            emitVerticalGradientQuad(consumer, entry, maxX, sliceMinY, minZ, maxX, sliceMaxY, minZ, maxX, sliceMaxY, maxZ, maxX, sliceMinY, maxZ, sliceBottomColor, sliceTopColor);
        }

        emitQuad(consumer, entry, minX, (float) box.maxY, minZ, minX, (float) box.maxY, maxZ, maxX, (float) box.maxY, maxZ, maxX, (float) box.maxY, minZ, topCapColor);
        emitQuad(consumer, entry, minX, (float) box.minY, minZ, maxX, (float) box.minY, minZ, maxX, (float) box.minY, maxZ, minX, (float) box.minY, maxZ, bottomCapColor);
    }

    private void emitVerticalGradientQuad(
            VertexConsumer consumer,
            MatrixStack.Entry entry,
            float x1,
            float y1,
            float z1,
            float x2,
            float y2,
            float z2,
            float x3,
            float y3,
            float z3,
            float x4,
            float y4,
            float z4,
            int bottomColor,
            int topColor
    ) {
        consumer.vertex(entry, x1, y1, z1).color(bottomColor);
        consumer.vertex(entry, x2, y2, z2).color(topColor);
        consumer.vertex(entry, x3, y3, z3).color(topColor);
        consumer.vertex(entry, x4, y4, z4).color(bottomColor);
    }

    private void emitQuad(
            VertexConsumer consumer,
            MatrixStack.Entry entry,
            float x1,
            float y1,
            float z1,
            float x2,
            float y2,
            float z2,
            float x3,
            float y3,
            float z3,
            float x4,
            float y4,
            float z4,
            int color
    ) {
        consumer.vertex(entry, x1, y1, z1).color(color);
        consumer.vertex(entry, x2, y2, z2).color(color);
        consumer.vertex(entry, x3, y3, z3).color(color);
        consumer.vertex(entry, x4, y4, z4).color(color);
    }

    private void updateChunks() {
        if (mc.world == null || mc.player == null) {
            return;
        }

        for (TrackedChunk trackedChunk : chunks.values()) {
            trackedChunk.marked = false;
        }

        int viewDist = Math.max(1, getRange() / 16);
        int playerChunkX = mc.player.getChunkPos().x;
        int playerChunkZ = mc.player.getChunkPos().z;

        for (int cx = playerChunkX - viewDist; cx <= playerChunkX + viewDist; cx++) {
            for (int cz = playerChunkZ - viewDist; cz <= playerChunkZ + viewDist; cz++) {
                WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(cx, cz, false);
                if (chunk == null) {
                    continue;
                }

                long key = ChunkPos.toLong(cx, cz);
                TrackedChunk trackedChunk = chunks.get(key);
                if (trackedChunk != null) {
                    trackedChunk.marked = true;
                    continue;
                }

                if (queuedChunks.add(key)) {
                    chunkQueue.add(key);
                }
            }
        }

        processChunkQueue();
        chunks.entrySet().removeIf(entry -> !entry.getValue().marked);

        Set<Long> activeKeys = chunks.keySet();
        holes.removeIf(hole -> !isBoxInActiveChunks(hole.box, activeKeys));
    }

    private boolean isBoxInActiveChunks(Box box, Set<Long> activeKeys) {
        int chunkX = ((int) Math.floor(box.getCenter().x)) >> 4;
        int chunkZ = ((int) Math.floor(box.getCenter().z)) >> 4;
        return activeKeys.contains(ChunkPos.toLong(chunkX, chunkZ));
    }

    private void processChunkQueue() {
        if (executor == null || mc.world == null) {
            return;
        }

        int processed = 0;
        while (!chunkQueue.isEmpty() && processed < MAX_CHUNKS_PER_TICK) {
            Long chunkKey = chunkQueue.poll();
            if (chunkKey == null) {
                continue;
            }

            queuedChunks.remove(chunkKey);

            int chunkX = ChunkPos.getPackedX(chunkKey);
            int chunkZ = ChunkPos.getPackedZ(chunkKey);
            WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(chunkX, chunkZ, false);
            if (chunk == null) {
                continue;
            }

            chunks.put(chunkKey, new TrackedChunk(chunkX, chunkZ));
            executor.execute(() -> searchChunk(chunk));
            processed++;
        }
    }

    private void searchChunk(WorldChunk chunk) {
        ClientWorld world = mc.world;
        if (world == null || world != currentWorld || !isEnabled()) {
            return;
        }

        ChunkSection[] sections = chunk.getSectionArray();
        int minY = world.getBottomY();
        int maxY = world.getBottomY() + world.getHeight();
        int sectionY = minY;

        for (ChunkSection section : sections) {
            if (section != null && !section.isEmpty()) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            int currentY = sectionY + y;
                            if (currentY <= minY || currentY >= maxY) {
                                continue;
                            }

                            BlockPos pos = new BlockPos(
                                    chunk.getPos().getStartX() + x,
                                    currentY,
                                    chunk.getPos().getStartZ() + z
                            );
                            checkHole(pos);
                            check3x1Hole(pos);
                        }
                    }
                }
            }

            sectionY += 16;
        }
    }

    private void checkHole(BlockPos pos) {
        if (!isValidHoleSection(pos) || isValidHoleSection(pos.up())) {
            return;
        }

        BlockPos.Mutable currentPos = pos.mutableCopy();
        while (isValidHoleSection(currentPos)) {
            currentPos.move(Direction.DOWN);
        }

        int depth = pos.getY() - currentPos.getY();
        if (depth < getMinDepth()) {
            return;
        }

        Box box = new Box(pos.getX(), currentPos.getY() + 1, pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        if (!containsIntersecting(box)) {
            holes.add(new HoleData(box, depth, true));
        }
    }

    private void check3x1Hole(BlockPos pos) {
        if (isValid3x1HoleSectionX(pos) && !isValid3x1HoleSectionX(pos.up())) {
            BlockPos.Mutable currentPos = pos.mutableCopy();
            while (isValid3x1HoleSectionX(currentPos)) {
                currentPos.move(Direction.DOWN);
            }

            int depth = pos.getY() - currentPos.getY();
            if (depth >= getMinDepth()) {
                Box box = new Box(pos.getX(), currentPos.getY() + 1, pos.getZ(), pos.getX() + 3, pos.getY() + 1, pos.getZ() + 1);
                if (!containsIntersecting(box)) {
                    holes.add(new HoleData(box, depth, false));
                }
            }
        }

        if (isValid3x1HoleSectionZ(pos) && !isValid3x1HoleSectionZ(pos.up())) {
            BlockPos.Mutable currentPos = pos.mutableCopy();
            while (isValid3x1HoleSectionZ(currentPos)) {
                currentPos.move(Direction.DOWN);
            }

            int depth = pos.getY() - currentPos.getY();
            if (depth >= getMinDepth()) {
                Box box = new Box(pos.getX(), currentPos.getY() + 1, pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 3);
                if (!containsIntersecting(box)) {
                    holes.add(new HoleData(box, depth, false));
                }
            }
        }
    }

    private boolean containsIntersecting(Box box) {
        for (HoleData data : holes) {
            if (data.box.equals(box) || data.box.intersects(box)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTransparentBlock(BlockState state) {
        return state.getBlock() == Blocks.OAK_LEAVES
                || state.getBlock() == Blocks.SPRUCE_LEAVES
                || state.getBlock() == Blocks.BIRCH_LEAVES
                || state.getBlock() == Blocks.JUNGLE_LEAVES
                || state.getBlock() == Blocks.ACACIA_LEAVES
                || state.getBlock() == Blocks.DARK_OAK_LEAVES
                || state.getBlock() == Blocks.CHERRY_LEAVES
                || state.getBlock() == Blocks.MANGROVE_LEAVES
                || state.getBlock() == Blocks.AZALEA_LEAVES
                || state.getBlock() == Blocks.FLOWERING_AZALEA_LEAVES
                || state.getBlock() == Blocks.GLASS
                || state.getBlock() == Blocks.GLASS_PANE
                || state.getBlock() == Blocks.VINE
                || state.getBlock() == Blocks.CAVE_VINES
                || state.getBlock() == Blocks.CAVE_VINES_PLANT
                || state.getBlock() == Blocks.WEEPING_VINES
                || state.getBlock() == Blocks.WEEPING_VINES_PLANT
                || state.getBlock() == Blocks.TWISTING_VINES
                || state.getBlock() == Blocks.TWISTING_VINES_PLANT
                || state.getBlock() == Blocks.GLOW_LICHEN
                || state.getBlock() == Blocks.HANGING_ROOTS
                || state.getBlock() == Blocks.SPORE_BLOSSOM
                || state.getBlock() == Blocks.BAMBOO
                || state.getBlock() == Blocks.BAMBOO_SAPLING
                || state.getBlock() == Blocks.KELP
                || state.getBlock() == Blocks.KELP_PLANT
                || state.getBlock() == Blocks.SEAGRASS
                || state.getBlock() == Blocks.TALL_SEAGRASS
                || state.getBlock() == Blocks.SHORT_GRASS
                || state.getBlock() == Blocks.TALL_GRASS
                || state.getBlock() == Blocks.FERN
                || state.getBlock() == Blocks.LARGE_FERN
                || state.getBlock() == Blocks.SUGAR_CANE
                || state.getBlock() == Blocks.DEAD_BUSH
                || state.getBlock() == Blocks.SWEET_BERRY_BUSH;
    }

    private boolean isSolidWall(BlockPos pos) {
        if (mc.world == null) {
            return false;
        }

        BlockState state = mc.world.getBlockState(pos);
        return !state.isAir() && !isTransparentBlock(state);
    }

    private boolean isValidHoleSection(BlockPos pos) {
        return isPassable(pos)
                && isSolidWall(pos.north())
                && isSolidWall(pos.south())
                && isSolidWall(pos.east())
                && isSolidWall(pos.west());
    }

    private boolean isValid3x1HoleSectionX(BlockPos pos) {
        return isPassable(pos)
                && isPassable(pos.east())
                && isPassable(pos.east(2))
                && isSolidWall(pos.north())
                && isSolidWall(pos.south())
                && isSolidWall(pos.west())
                && isSolidWall(pos.east(3));
    }

    private boolean isValid3x1HoleSectionZ(BlockPos pos) {
        return isPassable(pos)
                && isPassable(pos.south())
                && isPassable(pos.south(2))
                && isSolidWall(pos.east())
                && isSolidWall(pos.west())
                && isSolidWall(pos.north())
                && isSolidWall(pos.south(3));
    }

    private boolean isPassable(BlockPos pos) {
        if (mc.world == null) {
            return false;
        }

        BlockState state = mc.world.getBlockState(pos);
        if (!state.isAir()) {
            return false;
        }

        BlockState below = mc.world.getBlockState(pos.down());
        BlockState above = mc.world.getBlockState(pos.up());
        return !isPlantBlock(below)
                && !isPlantBlock(above)
                && !isMineshaftBlock(below)
                && !isMineshaftBlock(above);
    }

    private boolean isPlantBlock(BlockState state) {
        return state.getBlock() == Blocks.KELP
                || state.getBlock() == Blocks.KELP_PLANT
                || state.getBlock() == Blocks.SEAGRASS
                || state.getBlock() == Blocks.TALL_SEAGRASS
                || state.getBlock() == Blocks.VINE
                || state.getBlock() == Blocks.CAVE_VINES
                || state.getBlock() == Blocks.CAVE_VINES_PLANT
                || state.getBlock() == Blocks.WEEPING_VINES
                || state.getBlock() == Blocks.WEEPING_VINES_PLANT
                || state.getBlock() == Blocks.TWISTING_VINES
                || state.getBlock() == Blocks.TWISTING_VINES_PLANT
                || state.getBlock() == Blocks.GLOW_LICHEN
                || state.getBlock() == Blocks.HANGING_ROOTS
                || state.getBlock() == Blocks.SPORE_BLOSSOM;
    }

    private boolean isMineshaftBlock(BlockState state) {
        return state.getBlock() == Blocks.RAIL
                || state.getBlock() == Blocks.POWERED_RAIL
                || state.getBlock() == Blocks.DETECTOR_RAIL
                || state.getBlock() == Blocks.ACTIVATOR_RAIL
                || state.getBlock() == Blocks.OAK_FENCE
                || state.getBlock() == Blocks.DARK_OAK_FENCE
                || state.getBlock() == Blocks.SPRUCE_FENCE
                || state.getBlock() == Blocks.COBWEB;
    }

    private void clear() {
        chunks.clear();
        chunkQueue.clear();
        queuedChunks.clear();
        holes.clear();
    }

    private void ensureExecutor() {
        if (executor != null && !executor.isShutdown()) {
            return;
        }

        executor = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "xenon-hole-esp");
            thread.setDaemon(true);
            return thread;
        });
    }

    private void shutdownExecutor() {
        ExecutorService existing = executor;
        executor = null;
        if (existing == null) {
            return;
        }

        existing.shutdown();
        try {
            if (!existing.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                existing.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            existing.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private int getRange() {
        return MathHelper.clamp((int) Math.round(range.getValue()), 16, 128);
    }

    private int getMinDepth() {
        return FIXED_MIN_DEPTH;
    }

    private int clampAlpha(double value) {
        return MathHelper.clamp((int) Math.round(value), 0, 255);
    }

    private Color withAlpha(Color base, int alphaValue) {
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), MathHelper.clamp(alphaValue, 0, 255));
    }

    private int toArgb(Color color) {
        return (color.getAlpha() << 24)
                | (color.getRed() << 16)
                | (color.getGreen() << 8)
                | color.getBlue();
    }

    private static final class HoleData {
        private final Box box;
        private final int depth;
        private final boolean is1x1;
        private final long createdAt;

        private HoleData(Box box, int depth, boolean is1x1) {
            this.box = box;
            this.depth = depth;
            this.is1x1 = is1x1;
            this.createdAt = System.currentTimeMillis();
        }

        private boolean isReadyToRender() {
            return true;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof HoleData holeData)) {
                return false;
            }
            return Objects.equals(box, holeData.box);
        }

        @Override
        public int hashCode() {
            return Objects.hash(box);
        }
    }

    private static final class TrackedChunk {
        private final int x;
        private final int z;
        private boolean marked;

        private TrackedChunk(int x, int z) {
            this.x = x;
            this.z = z;
            this.marked = true;
        }
    }
}

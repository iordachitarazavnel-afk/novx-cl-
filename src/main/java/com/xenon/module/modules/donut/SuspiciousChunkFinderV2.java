package com.xenon.module.modules.donut;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.Setting;
import com.xenon.utils.RenderUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.*;
import net.minecraft.world.Heightmap;

import java.awt.*;
import java.util.*;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class SuspiciousChunkFinderV2 extends Module {

    enum SusV2ChunkColor { GREEN, RED, WHITE, YELLOW, CYAN, ORANGE, PURPLE }

    static class SusV2Analysis {
        int rotatedCount = 0;
        int amethystCount = 0;
        boolean hasLongDripstone = false;
        boolean hasLongVine = false;
        boolean allKelpFull = false;
        boolean hasDioriteVein = false;
        boolean hasObsidianVein = false;
        BlockPos susBlockPos = null;
    }

    private static final int THREAD_COUNT = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    private static final int MAX_CONCURRENT_SCANS = 3;

    private final Set<ChunkPos> flaggedChunks = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<ChunkPos, SusV2Analysis> chunkData = new ConcurrentHashMap<>();
    private final Set<ChunkPos> scannedChunks = ConcurrentHashMap.newKeySet();
    private final Queue<ChunkPos> scanQueue = new ConcurrentLinkedQueue<>();
    private final AtomicLong activeScans = new AtomicLong(0L);

    private final Setting<Boolean> alertCoords  = new Setting<>("Alert Coordinates", true);
    private final Setting<Boolean> tracerSetting = new Setting<>("Tracer", true);
    private final Setting<SusV2ChunkColor> chunkColor = new Setting<>("Chunk Color", SusV2ChunkColor.PURPLE);

    private ChunkPos lastPlayerChunk = null;
    private ExecutorService pool;
    private volatile boolean scanning = false;
    private long lastQueueRebuild = 0L;

    public SuspiciousChunkFinderV2() {
        super("Sus Chunk Finder V2", Category.DONUT);
        addSetting(alertCoords);
        addSetting(tracerSetting);
        addSetting(chunkColor);
    }

    @Override
    public void onEnable() {
        this.scanning = true;
        this.scannedChunks.clear();
        this.flaggedChunks.clear();
        this.chunkData.clear();
        this.scanQueue.clear();
        this.lastPlayerChunk = null;
        this.pool = Executors.newFixedThreadPool(THREAD_COUNT);
    }

    @Override
    public void onDisable() {
        this.scanning = false;
        if (this.pool != null) {
            this.pool.shutdownNow();
            this.pool = null;
        }
        this.scannedChunks.clear();
        this.flaggedChunks.clear();
        this.chunkData.clear();
        this.scanQueue.clear();
        this.lastPlayerChunk = null;
    }

    @Override
    public void onTick() {
        if (mc.world == null) {
            this.scanning = false;
            if (this.pool != null) { this.pool.shutdownNow(); this.pool = null; }
            this.scannedChunks.clear(); this.flaggedChunks.clear();
            this.chunkData.clear(); this.scanQueue.clear();
            this.lastPlayerChunk = null;
        }
    }

    @Override
    public void onRender(MatrixStack matrices, float tickDelta) {
        if (mc.player == null || mc.world == null) return;

        if (!this.scanning) {
            this.scanning = true;
            if (this.pool == null) this.pool = Executors.newFixedThreadPool(THREAD_COUNT);
        }

        long now = System.currentTimeMillis();
        int playerChunkX = (int) Math.floor(mc.player.getX() / 16.0);
        int playerChunkZ = (int) Math.floor(mc.player.getZ() / 16.0);
        ChunkPos currentPlayerChunk = new ChunkPos(playerChunkX, playerChunkZ);

        boolean moved = !currentPlayerChunk.equals(this.lastPlayerChunk);
        boolean timeout = now - this.lastQueueRebuild > 2000L;

        if (moved || (timeout && this.scanQueue.isEmpty())) {
            if (moved) {
                this.lastPlayerChunk = currentPlayerChunk;
                this.cleanupDistantChunks(currentPlayerChunk);
            }
            this.lastQueueRebuild = now;
            this.buildBFSScanQueue(currentPlayerChunk);
        }

        this.tryStartScans();
        this.renderFlaggedChunks(matrices);
    }

    private void cleanupDistantChunks(ChunkPos center) {
        int cleanupRadius = 16 + 2;
        this.scannedChunks.removeIf(chunk -> {
            int dx = Math.abs(chunk.x - center.x);
            int dz = Math.abs(chunk.z - center.z);
            return dx > cleanupRadius || dz > cleanupRadius;
        });
    }

    private void buildBFSScanQueue(ChunkPos center) {
        int radius = 16;
        Set<ChunkPos> visited = new HashSet<>();
        Queue<ChunkPos> bfsQueue = new LinkedList<>();
        bfsQueue.offer(center);
        visited.add(center);

        while (!bfsQueue.isEmpty()) {
            ChunkPos current = bfsQueue.poll();
            if (!this.scannedChunks.contains(current)) {
                this.scanQueue.offer(current);
            }
            int[][] offsets = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};
            for (int[] offset : offsets) {
                ChunkPos neighbor = new ChunkPos(current.x + offset[0], current.z + offset[1]);
                int dx = Math.abs(neighbor.x - center.x);
                int dz = Math.abs(neighbor.z - center.z);
                if (dx <= radius && dz <= radius && !visited.contains(neighbor)) {
                    visited.add(neighbor);
                    bfsQueue.offer(neighbor);
                }
            }
        }
    }

    private void tryStartScans() {
        if (!this.scanning || mc.world == null || mc.player == null || this.pool == null) return;
        while (this.activeScans.get() < MAX_CONCURRENT_SCANS && !this.scanQueue.isEmpty()) {
            ChunkPos pos = this.scanQueue.poll();
            if (pos == null || this.scannedChunks.contains(pos)) continue;
            if (!mc.world.getChunkManager().isChunkLoaded(pos.x, pos.z)) continue;
            this.scannedChunks.add(pos);
            this.activeScans.incrementAndGet();
            this.pool.submit(() -> {
                try {
                    Thread.sleep(100L);
                    this.analyzeChunk(pos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    this.activeScans.decrementAndGet();
                }
            });
        }
    }

    private void analyzeChunk(ChunkPos pos) {
        if (mc.world == null || !this.scanning) return;

        int startX = pos.getStartX();
        int startZ = pos.getStartZ();
        int minY = Math.max(-5, mc.world.getBottomY());
        int maxY = 319;
        SusV2Analysis analysis = new SusV2Analysis();

        // Block scan loop
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y <= maxY; y++) {
                    if (!this.scanning) return;
                    mutable.set(startX + x, y, startZ + z);
                    BlockState state = mc.world.getBlockState(mutable);

                    // Rotated deepslate
                    if (state.getBlock() == Blocks.DEEPSLATE && state.contains(Properties.AXIS)) {
                        if (state.get(Properties.AXIS) != Direction.Axis.Y) {
                            analysis.rotatedCount++;
                            if (analysis.susBlockPos == null) analysis.susBlockPos = mutable.toImmutable();
                        }
                    }
                    // Amethyst cluster
                    if (state.getBlock() == Blocks.AMETHYST_CLUSTER) {
                        analysis.amethystCount++;
                        if (analysis.susBlockPos == null) analysis.susBlockPos = mutable.toImmutable();
                    }
                }
            }
        }

        // Long dripstone
        BlockPos dp = checkHasLongDripstone(pos);
        if (dp != null) { analysis.hasLongDripstone = true; if (analysis.susBlockPos == null) analysis.susBlockPos = dp; }

        // Long vine
        BlockPos vp = checkHasLongVine(pos);
        if (vp != null) { analysis.hasLongVine = true; if (analysis.susBlockPos == null) analysis.susBlockPos = vp; }

        // Full kelp
        BlockPos kp = checkAllKelpFullyGrown(pos);
        if (kp != null) { analysis.allKelpFull = true; if (analysis.susBlockPos == null) analysis.susBlockPos = kp; }

        // Diorite vein
        BlockPos dv = checkHasDioriteVein(pos);
        if (dv != null) { analysis.hasDioriteVein = true; if (analysis.susBlockPos == null) analysis.susBlockPos = dv; }

        // Obsidian vein
        BlockPos ov = checkHasObsidianVein(pos);
        if (ov != null) { analysis.hasObsidianVein = true; if (analysis.susBlockPos == null) analysis.susBlockPos = ov; }

        this.chunkData.put(pos, analysis);
        this.evaluateChunk(pos, analysis);
    }

    private BlockPos checkHasDioriteVein(ChunkPos chunkPos) {
        if (mc.world == null) return null;
        int startX = chunkPos.getStartX();
        int startZ = chunkPos.getStartZ();
        Set<Long> visited = new HashSet<>();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = Math.max(mc.world.getBottomY(), -64); y < 319; y++) {
                    if (!this.scanning) return null;
                    BlockPos pos = new BlockPos(startX + x, y, startZ + z);
                    if (!visited.contains(pos.asLong())) {
                        BlockState state = mc.world.getBlockState(pos);
                        if (isTargetBlock(state)) {
                            int lenUp = countVerticalRun(pos, Direction.UP);
                            int lenDown = countVerticalRun(pos, Direction.DOWN);
                            int total = lenUp + 1 + lenDown;
                            if (total >= 5) {
                                BlockPos start = pos.offset(Direction.DOWN, lenDown);
                                boolean enclosed = true;
                                for (int i = 0; i < total; i++) {
                                    BlockPos bp = start.offset(Direction.UP, i);
                                    visited.add(bp.asLong());
                                    if (!isEnclosedByStone(bp)) enclosed = false;
                                }
                                if (enclosed) return pos;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private BlockPos checkHasObsidianVein(ChunkPos chunkPos) {
        if (mc.world == null) return null;
        int startX = chunkPos.getStartX();
        int startZ = chunkPos.getStartZ();
        Set<Long> visited = new HashSet<>();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 15; y <= 63; y++) {
                    if (!this.scanning) return null;
                    BlockPos pos = new BlockPos(startX + x, y, startZ + z);
                    if (!visited.contains(pos.asLong())) {
                        BlockState state = mc.world.getBlockState(pos);
                        if (state.isOf(Blocks.OBSIDIAN)) {
                            int lenUp = countVerticalRunObsidian(pos, Direction.UP);
                            int lenDown = countVerticalRunObsidian(pos, Direction.DOWN);
                            int total = lenUp + 1 + lenDown;
                            if (total >= 15) {
                                BlockPos start = pos.offset(Direction.DOWN, lenDown);
                                boolean enclosed = true;
                                for (int i = 0; i < total; i++) {
                                    BlockPos bp = start.offset(Direction.UP, i);
                                    visited.add(bp.asLong());
                                    if (!isEnclosedByNonObsidian(bp)) enclosed = false;
                                }
                                if (enclosed) return pos;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private BlockPos checkHasLongDripstone(ChunkPos chunkPos) {
        if (mc.world == null) return null;
        int startX = chunkPos.getStartX();
        int startZ = chunkPos.getStartZ();
        int worldMinY = mc.world.getBottomY();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 319; y >= worldMinY; y--) {
                    BlockPos pos = new BlockPos(startX + x, y, startZ + z);
                    BlockState state = mc.world.getBlockState(pos);
                    if (state.getBlock() == Blocks.POINTED_DRIPSTONE
                            && state.contains(Properties.VERTICAL_DIRECTION)
                            && state.get(Properties.VERTICAL_DIRECTION) == Direction.DOWN) {
                        BlockState above = mc.world.getBlockState(pos.up());
                        if (above.getBlock() != Blocks.POINTED_DRIPSTONE) {
                            int length = 1;
                            BlockPos cur = pos.down();
                            while (cur.getY() >= worldMinY && length < 50) {
                                BlockState cs = mc.world.getBlockState(cur);
                                if (cs.getBlock() == Blocks.POINTED_DRIPSTONE
                                        && cs.contains(Properties.VERTICAL_DIRECTION)
                                        && cs.get(Properties.VERTICAL_DIRECTION) == Direction.DOWN) {
                                    length++; cur = cur.down();
                                } else break;
                            }
                            if (length >= 7) return pos;
                        }
                    }
                }
            }
        }
        return null;
    }

    private BlockPos checkHasLongVine(ChunkPos chunkPos) {
        if (mc.world == null) return null;
        int startX = chunkPos.getStartX();
        int startZ = chunkPos.getStartZ();
        Set<Long> processedTops = new HashSet<>();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 319; y >= 40; y--) {
                    BlockPos pos = new BlockPos(startX + x, y, startZ + z);
                    if (!processedTops.contains(pos.asLong())) {
                        BlockState state = mc.world.getBlockState(pos);
                        if (state.getBlock() == Blocks.VINE) {
                            BlockState topState = mc.world.getBlockState(pos.up());
                            if (topState.getBlock() != Blocks.VINE && (topState.isSolidBlock(mc.world, pos.up()) || !topState.isAir())) {
                                processedTops.add(pos.asLong());
                                int len = 1;
                                BlockPos cur = pos.down();
                                while (cur.getY() >= Math.max(mc.world.getBottomY(), 40)) {
                                    if (mc.world.getBlockState(cur).getBlock() == Blocks.VINE) { len++; cur = cur.down(); }
                                    else break;
                                }
                                if (len >= 30) return pos;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private BlockPos checkAllKelpFullyGrown(ChunkPos chunkPos) {
        if (mc.world == null) return null;
        int startX = chunkPos.getStartX();
        int startZ = chunkPos.getStartZ();
        int kelpFound = 0, fullKelp = 0;
        BlockPos firstKelp = null;
        Set<Long> processed = new HashSet<>();
        int worldMinY = mc.world.getBottomY();
        int worldMaxY = 319;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = worldMinY; y <= worldMaxY; y++) {
                    BlockPos pos = new BlockPos(startX + x, y, startZ + z);
                    if (!processed.contains(pos.asLong())) {
                        BlockState state = mc.world.getBlockState(pos);
                        if (state.getBlock() == Blocks.KELP || state.getBlock() == Blocks.KELP_PLANT) {
                            BlockState below = mc.world.getBlockState(pos.down());
                            if (below.getBlock() != Blocks.KELP && below.getBlock() != Blocks.KELP_PLANT) {
                                processed.add(pos.asLong());
                                if (firstKelp == null) firstKelp = pos;
                                BlockPos cur = pos.up();
                                boolean reachedSurface = false;
                                int len = 1;
                                while (cur.getY() <= worldMaxY) {
                                    BlockState cs = mc.world.getBlockState(cur);
                                    if (cs.getBlock() == Blocks.KELP || cs.getBlock() == Blocks.KELP_PLANT) { len++; cur = cur.up(); continue; }
                                    if (cs.getFluidState().isEmpty()) reachedSurface = true;
                                    break;
                                }
                                if (len >= 6 || !reachedSurface) {
                                    kelpFound++;
                                    if (reachedSurface) fullKelp++;
                                }
                            }
                        }
                    }
                }
            }
        }
        if (kelpFound < 10) return null;
        return (kelpFound > 0 && kelpFound == fullKelp) ? firstKelp : null;
    }

    private boolean isTargetBlock(BlockState state) {
        return state.isOf(Blocks.GRANITE) || state.isOf(Blocks.DIORITE) || state.isOf(Blocks.ANDESITE);
    }

    private int countVerticalRun(BlockPos from, Direction dir) {
        int count = 0;
        BlockPos.Mutable m = new BlockPos.Mutable(from.getX(), from.getY(), from.getZ());
        while (count <= 20) { m.move(dir); if (!isTargetBlock(mc.world.getBlockState(m))) break; count++; }
        return count;
    }

    private int countVerticalRunObsidian(BlockPos from, Direction dir) {
        int count = 0;
        BlockPos.Mutable m = new BlockPos.Mutable(from.getX(), from.getY(), from.getZ());
        while (count <= 20) { m.move(dir); if (!mc.world.getBlockState(m).isOf(Blocks.OBSIDIAN)) break; count++; }
        return count;
    }

    private boolean isEnclosedByStone(BlockPos pos) {
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            if (!mc.world.getBlockState(pos.offset(d)).isOf(Blocks.STONE)) return false;
        }
        return true;
    }

    private boolean isEnclosedByNonObsidian(BlockPos pos) {
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            if (mc.world.getBlockState(pos.offset(d)).isOf(Blocks.OBSIDIAN)) return false;
        }
        return true;
    }

    private void evaluateChunk(ChunkPos pos, SusV2Analysis analysis) {
        boolean suspicious = false;
        StringBuilder reasons = new StringBuilder();

        if (analysis.rotatedCount >= 1)  { suspicious = true; reasons.append("Rotated:").append(analysis.rotatedCount).append(" "); }
        if (analysis.hasLongDripstone)   { suspicious = true; reasons.append("Dripstone "); }
        if (analysis.hasLongVine)        { suspicious = true; reasons.append("Vine "); }
        if (analysis.allKelpFull)        { suspicious = true; reasons.append("Kelp "); }
        if (analysis.hasDioriteVein)     { suspicious = true; reasons.append("DioriteVein "); }
        if (analysis.hasObsidianVein)    { suspicious = true; reasons.append("ObsidianVein "); }
        if (analysis.amethystCount >= 1) { suspicious = true; reasons.append("Amethyst:").append(analysis.amethystCount).append(" "); }

        if (!suspicious) { this.flaggedChunks.remove(pos); return; }

        if (this.flaggedChunks.add(pos)) {
            int finalX = analysis.susBlockPos != null ? analysis.susBlockPos.getX() : pos.getStartX() + 8;
            int finalZ = analysis.susBlockPos != null ? analysis.susBlockPos.getZ() : pos.getStartZ() + 8;
            String reasonStr = reasons.toString().trim();

            mc.execute(() -> {
                String coords = alertCoords.getValue()
                        ? "X:" + finalX + " Z:" + finalZ
                        : reasonStr;
                com.xenon.gui.notification.NotificationManager.INSTANCE.push(
                        "Sus Chunk V2",
                        coords,
                        new net.minecraft.item.ItemStack(net.minecraft.item.Items.AMETHYST_SHARD),
                        0xFFAA00FF
                );
            });
        }
    }

    private Color getSelectedColor(int alpha) {
        return switch (chunkColor.getValue()) {
            case RED    -> new Color(255, 50,  50,  alpha);
            case WHITE  -> new Color(255, 255, 255, alpha);
            case YELLOW -> new Color(255, 220, 0,   alpha);
            case CYAN   -> new Color(0,   220, 255, alpha);
            case ORANGE -> new Color(255, 140, 0,   alpha);
            case PURPLE -> new Color(140, 0,   255, alpha);
            default     -> new Color(0,   255, 0,   alpha);
        };
    }

    private void renderFlaggedChunks(MatrixStack matrices) {
        if (this.flaggedChunks.isEmpty() || mc.player == null || mc.world == null) return;

        Camera cam = RenderUtils.getCamera();
        Vec3d camPos = RenderUtils.getCameraPos(cam);

        Color fillColor    = getSelectedColor(80);
        Color outlineColor = getSelectedColor(220);
        Color tracerColor  = getSelectedColor(200);

        RenderUtils.WorldBatch batch = RenderUtils.beginWorldBatch(matrices);
        matrices.push();
        matrices.translate(-camPos.x, -camPos.y, -camPos.z);

        int rendered = 0;
        for (ChunkPos pos : this.flaggedChunks) {
            if (rendered++ >= 50) break;
            double distSq = mc.player.squaredDistanceTo(pos.getStartX() + 8.0, mc.player.getY(), pos.getStartZ() + 8.0);
            if (distSq > 62500.0) continue;

            double x1 = pos.getStartX();
            double z1 = pos.getStartZ();
            double x2 = x1 + 16.0;
            double z2 = z1 + 16.0;
            double y  = mc.world.getTopY(Heightmap.Type.WORLD_SURFACE, pos.getStartX() + 8, pos.getStartZ() + 8) + 0.05;

            batch.renderFilledBox(x1, y - 0.1, z1, x2, y + 0.15, z2, fillColor);
            batch.renderOutlineBox(x1, y - 0.1, z1, x2, y + 0.15, z2, outlineColor);

            if (tracerSetting.getValue()) {
                batch.renderLine(tracerColor, camPos, new Vec3d(pos.getStartX() + 8.0, y, pos.getStartZ() + 8.0), 2.0f);
            }
        }

        matrices.pop();
        batch.flush();
    }
}

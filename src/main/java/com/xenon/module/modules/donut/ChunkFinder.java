package com.xenon.module.modules.donut;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.Setting;
import com.xenon.utils.RenderUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.Camera;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.*;
import net.minecraft.util.math.random.Random;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.world.Heightmap;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class ChunkFinder extends Module {

    private static final int THREAD_COUNT = Math.max(2, Runtime.getRuntime().availableProcessors());
    private static final int MAX_CONCURRENT_SCANS = 50;
    private static final long RESCAN_INTERVAL_MS = 5000L;
    private static final long QUEUE_REBUILD_INTERVAL_MS = 2000L;

    private final Set<ChunkPos> flaggedChunks = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> notifiedChunks = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<ChunkPos, ChunkAnalysis> chunkData = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Long> scannedChunks = new ConcurrentHashMap<>();
    private final Queue<ChunkPos> scanQueue = new ConcurrentLinkedQueue<>();
    private final AtomicLong activeScans = new AtomicLong(0L);

    private final boolean ignorePlayerChunk = true;
    private final boolean detectItems = true;
    private final double maxItems = 3.0;

    private final Setting<Boolean> showReasons = new Setting<>("Show Reasons", true);
    private final Setting<Boolean> detectXP = new Setting<>("Check XP Orbs", true);
    private final Setting<Float> maxXP = new Setting<>("Max XP Orbs", 3.0f);
    private final Setting<Boolean> alertCoords = new Setting<>("Alert Coordinates", true);
    private final Setting<Float> deepslateThreshold = new Setting<>("Deepslate Limit", 3.0f);
    private final Setting<Float> rotatedThreshold = new Setting<>("Rotated DS Limit", 1.0f);
    private final Setting<Boolean> tracer = new Setting<>("Tracer", false);

    public enum ChunkColor { GREEN, RED, WHITE, YELLOW, CYAN, ORANGE }
    private final Setting<ChunkColor> chunkColor = new Setting<>("Chunk Color", ChunkColor.GREEN);

    private final Map<ChunkPos, Integer> chunkItemCounts = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Integer> chunkXPCounts = new ConcurrentHashMap<>();

    private ChunkPos lastPlayerChunk = null;
    private ExecutorService pool;
    private volatile boolean scanning = false;
    private long lastQueueRebuild = 0L;

    public ChunkFinder() {
        super("Chunk Finder", Category.DONUT);
        addSetting(alertCoords);
        addSetting(showReasons);
        addSetting(detectXP);
        addSetting(maxXP);
        addSetting(deepslateThreshold);
        addSetting(rotatedThreshold);
        addSetting(tracer);
        addSetting(chunkColor);
    }

    @Override
    public void onEnable() {
        this.scanning = true;
        this.scannedChunks.clear();
        this.flaggedChunks.clear();
        this.notifiedChunks.clear();
        this.chunkData.clear();
        this.scanQueue.clear();
        this.lastPlayerChunk = null;
        this.lastQueueRebuild = 0L;
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
        this.notifiedChunks.clear();
        this.chunkData.clear();
        this.scanQueue.clear();
        this.lastPlayerChunk = null;
        this.lastQueueRebuild = 0L;
    }

    @Override
    public void onTick() {
        if (mc.world == null) {
            this.scanning = false;
            if (this.pool != null) {
                this.pool.shutdownNow();
                this.pool = null;
            }
            this.scannedChunks.clear();
            this.flaggedChunks.clear();
            this.notifiedChunks.clear();
            this.chunkData.clear();
            this.scanQueue.clear();
            this.lastPlayerChunk = null;
            this.lastQueueRebuild = 0L;
            return;
        }

        this.chunkItemCounts.clear();
        this.chunkXPCounts.clear();
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof ItemEntity) {
                this.chunkItemCounts.merge(entity.getChunkPos(), 1, Integer::sum);
            } else if (entity instanceof ExperienceOrbEntity) {
                this.chunkXPCounts.merge(entity.getChunkPos(), 1, Integer::sum);
            }
        }
    }

    @Override
    public void onRender(MatrixStack matrices, float tickDelta) {
        if (mc.player == null || mc.world == null || !this.scanning) return;

        int playerChunkX = (int) Math.floor(mc.player.getX() / 16.0);
        int playerChunkZ = (int) Math.floor(mc.player.getZ() / 16.0);
        ChunkPos currentPlayerChunk = new ChunkPos(playerChunkX, playerChunkZ);
        long now = System.currentTimeMillis();

        if (this.lastPlayerChunk == null) {
            this.lastPlayerChunk = currentPlayerChunk;
            this.buildBFSScanQueue(currentPlayerChunk);
            this.lastQueueRebuild = now;
        } else if (!currentPlayerChunk.equals(this.lastPlayerChunk)) {
            this.lastPlayerChunk = currentPlayerChunk;
            this.cleanupDistantChunks(currentPlayerChunk);
            this.scanQueue.clear();
            this.buildBFSScanQueue(currentPlayerChunk);
            this.lastQueueRebuild = now;
        } else if (now - this.lastQueueRebuild >= QUEUE_REBUILD_INTERVAL_MS) {
            this.scanQueue.clear();
            this.buildBFSScanQueue(currentPlayerChunk);
            this.lastQueueRebuild = now;
        }

        this.tryStartScans();
        this.renderFlaggedChunks(matrices);
    }

    private int getScanRadius() {
        return mc.options.getViewDistance().getValue();
    }

    private void cleanupDistantChunks(ChunkPos center) {
        int cleanupRadius = this.getScanRadius() + 2;
        this.scannedChunks.keySet().removeIf(chunk -> {
            int dx = Math.abs(chunk.x - center.x);
            int dz = Math.abs(chunk.z - center.z);
            return dx > cleanupRadius || dz > cleanupRadius;
        });
    }

    private void buildBFSScanQueue(ChunkPos center) {
        int radius = this.getScanRadius();
        HashSet<ChunkPos> visited = new HashSet<>();
        LinkedList<ChunkPos> bfsQueue = new LinkedList<>();
        bfsQueue.offer(center);
        visited.add(center);

        int[][] offsets = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};
        long now = System.currentTimeMillis();

        while (!bfsQueue.isEmpty()) {
            ChunkPos current = bfsQueue.poll();
            Long scanTime = this.scannedChunks.get(current);
            if (scanTime == null || (now - scanTime >= RESCAN_INTERVAL_MS)) {
                this.scanQueue.offer(current);
            }
            for (int[] offset : offsets) {
                ChunkPos neighbor = new ChunkPos(current.x + offset[0], current.z + offset[1]);
                int dx = Math.abs(neighbor.x - center.x);
                int dz = Math.abs(neighbor.z - center.z);
                if (dx > radius || dz > radius || visited.contains(neighbor)) continue;
                visited.add(neighbor);
                bfsQueue.offer(neighbor);
            }
        }
    }

    private void tryStartScans() {
        if (!this.scanning || mc.world == null || mc.player == null || this.pool == null) return;
        long now = System.currentTimeMillis();
        while (this.activeScans.get() < MAX_CONCURRENT_SCANS && !this.scanQueue.isEmpty()) {
            ChunkPos pos = this.scanQueue.poll();
            if (pos == null) continue;
            Long lastScan = this.scannedChunks.get(pos);
            if (lastScan != null && (now - lastScan < RESCAN_INTERVAL_MS)) continue;
            if (!mc.world.isChunkLoaded(pos.x, pos.z)) continue;

            this.scannedChunks.put(pos, now);
            this.notifiedChunks.remove(pos);
            this.activeScans.incrementAndGet();
            this.pool.submit(() -> {
                try {
                    this.analyzeChunk(pos);
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
        int worldMinY = mc.world.getBottomY();
        int worldMaxY = mc.world.getTopY(Heightmap.Type.WORLD_SURFACE, startX + 8, startZ + 8) - 1;

        ChunkAnalysis analysis = new ChunkAnalysis();

        BlockPos.Mutable mutable = new BlockPos.Mutable();
        HashSet<Long> dioriteVisited = new HashSet<>();
        HashSet<Long> obsidianVisited = new HashSet<>();
        HashSet<Long> processedVineTops = new HashSet<>();
        HashSet<Long> processedKelpBases = new HashSet<>();
        int kelpPlantsFound = 0;
        int fullKelpPlants = 0;
        BlockPos firstKelpPos = null;

        for (int x = 0; x < 16; ++x) {
            for (int z = 0; z < 16; ++z) {
                for (int y = worldMinY; y <= worldMaxY; ++y) {
                    if (!this.scanning) return;

                    mutable.set(startX + x, y, startZ + z);
                    BlockState state = mc.world.getBlockState(mutable);

                    if (y >= 0 && y <= 16) {
                        if (state.getBlock() == Blocks.DEEPSLATE && state.contains(Properties.AXIS)) {
                            Direction.Axis axis = state.get(Properties.AXIS);
                            if (axis != Direction.Axis.Y) {
                                ++analysis.rotatedCount;
                                if (analysis.susBlockPos == null) {
                                    analysis.susBlockPos = mutable.toImmutable();
                                }
                            }
                        }
                    }

                    if (!analysis.hasDioriteVein && this.isTargetBlock(state)) {
                        long posLong = mutable.asLong();
                        if (!dioriteVisited.contains(posLong)) {
                            BlockPos immutable = mutable.toImmutable();
                            int lenUp = this.countVerticalRun(immutable, Direction.UP);
                            int lenDown = this.countVerticalRun(immutable, Direction.DOWN);
                            int total = lenUp + 1 + lenDown;

                            if (total >= 5) {
                                BlockPos veinStart = immutable.offset(Direction.DOWN, lenDown);
                                boolean enclosed = true;
                                for (int i = 0; i < total; ++i) {
                                    BlockPos bp = veinStart.offset(Direction.UP, i);
                                    dioriteVisited.add(bp.asLong());
                                    if (!this.isEnclosedByStone(bp)) enclosed = false;
                                }
                                if (enclosed) {
                                    analysis.hasDioriteVein = true;
                                    if (analysis.susBlockPos == null) analysis.susBlockPos = immutable;
                                }
                            }
                        }
                    }

                    if (!analysis.hasObsidianVein && y >= 15 && y <= 63 && state.isOf(Blocks.OBSIDIAN)) {
                        long posLong = mutable.asLong();
                        if (!obsidianVisited.contains(posLong)) {
                            BlockPos immutable = mutable.toImmutable();
                            int lenUp = this.countVerticalRunObsidian(immutable, Direction.UP);
                            int lenDown = this.countVerticalRunObsidian(immutable, Direction.DOWN);
                            int total = lenUp + 1 + lenDown;

                            if (total >= 15) {
                                BlockPos veinStart = immutable.offset(Direction.DOWN, lenDown);
                                boolean enclosed = true;
                                for (int i = 0; i < total; ++i) {
                                    BlockPos bp = veinStart.offset(Direction.UP, i);
                                    obsidianVisited.add(bp.asLong());
                                    if (!this.isEnclosedByNonObsidian(bp)) enclosed = false;
                                }
                                if (enclosed) {
                                    analysis.hasObsidianVein = true;
                                    if (analysis.susBlockPos == null) analysis.susBlockPos = immutable;
                                }
                            }
                        }
                    }

                    if (!analysis.hasLongDripstone && state.getBlock() == Blocks.POINTED_DRIPSTONE) {
                        if (state.contains(Properties.VERTICAL_DIRECTION)
                                && state.get(Properties.VERTICAL_DIRECTION) == Direction.DOWN) {
                            BlockState aboveState = mc.world.getBlockState(mutable.up());
                            if (aboveState.getBlock() != Blocks.POINTED_DRIPSTONE) {
                                BlockPos immutable = mutable.toImmutable();
                                int length = 1;
                                BlockPos current = immutable.down();
                                while (current.getY() >= worldMinY && length < 63) {
                                    BlockState currentState = mc.world.getBlockState(current);
                                    if (currentState.getBlock() == Blocks.POINTED_DRIPSTONE
                                            && currentState.contains(Properties.VERTICAL_DIRECTION)
                                            && currentState.get(Properties.VERTICAL_DIRECTION) == Direction.DOWN) {
                                        ++length;
                                        current = current.down();
                                    } else break;
                                }
                                if (length >= 60) {
                                    analysis.hasLongDripstone = true;
                                    if (analysis.susBlockPos == null) analysis.susBlockPos = immutable;
                                }
                            }
                        }
                    }

                    if (!analysis.hasLongVine && y >= 40 && state.getBlock() == Blocks.VINE) {
                        long posLong = mutable.asLong();
                        if (!processedVineTops.contains(posLong)) {
                            BlockState topState = mc.world.getBlockState(mutable.up());
                            boolean isVineTop = topState.getBlock() != Blocks.VINE
                                    && (topState.isOpaque() || !topState.isFullCube(mc.world, mutable.up()));
                            if (isVineTop) {
                                processedVineTops.add(posLong);
                                BlockPos immutable = mutable.toImmutable();
                                int vineLength = 1;
                                BlockPos current = immutable.down();
                                while (current.getY() >= Math.max(worldMinY, 40)) {
                                    BlockState currentState = mc.world.getBlockState(current);
                                    if (currentState.getBlock() == Blocks.VINE) {
                                        ++vineLength;
                                        current = current.down();
                                    } else break;
                                }
                                if (vineLength >= 37) {
                                    analysis.hasLongVine = true;
                                    if (analysis.susBlockPos == null) analysis.susBlockPos = immutable;
                                }
                            }
                        }
                    }

                    if (!analysis.allKelpFull) {
                        if (state.getBlock() == Blocks.KELP || state.getBlock() == Blocks.KELP_PLANT) {
                            long posLong = mutable.asLong();
                            if (!processedKelpBases.contains(posLong)) {
                                BlockState belowState = mc.world.getBlockState(mutable.down());
                                boolean isKelpBase = belowState.getBlock() != Blocks.KELP
                                        && belowState.getBlock() != Blocks.KELP_PLANT;
                                if (isKelpBase) {
                                    processedKelpBases.add(posLong);
                                    BlockPos immutable = mutable.toImmutable();
                                    if (firstKelpPos == null) firstKelpPos = immutable;
                                    BlockPos current = immutable.up();
                                    boolean reachedWaterSurface = false;
                                    int kelpLength = 1;
                                    while (current.getY() <= worldMaxY) {
                                        BlockState currentState = mc.world.getBlockState(current);
                                        if (currentState.getBlock() == Blocks.KELP
                                                || currentState.getBlock() == Blocks.KELP_PLANT) {
                                            ++kelpLength;
                                            current = current.up();
                                            continue;
                                        }
                                        if (currentState.getFluidState().isEmpty()) reachedWaterSurface = true;
                                        break;
                                    }
                                    if (!(kelpLength < 6 && reachedWaterSurface)) {
                                        ++kelpPlantsFound;
                                        if (reachedWaterSurface) ++fullKelpPlants;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (kelpPlantsFound >= 8 && kelpPlantsFound == fullKelpPlants) {
            analysis.allKelpFull = true;
            if (analysis.susBlockPos == null) analysis.susBlockPos = firstKelpPos;
        }

        this.chunkData.put(pos, analysis);
        this.evaluateChunk(pos, analysis);
    }

    private boolean isTargetBlock(BlockState state) {
        return state.isOf(Blocks.DIORITE) || state.isOf(Blocks.GRANITE) || state.isOf(Blocks.ANDESITE);
    }

    private int countVerticalRun(BlockPos from, Direction dir) {
        int count = 0;
        BlockPos.Mutable m = new BlockPos.Mutable(from.getX(), from.getY(), from.getZ());
        while (count <= 20) {
            m.move(dir);
            if (!this.isTargetBlock(mc.world.getBlockState(m))) break;
            ++count;
        }
        return count;
    }

    private int countVerticalRunObsidian(BlockPos from, Direction dir) {
        int count = 0;
        BlockPos.Mutable m = new BlockPos.Mutable(from.getX(), from.getY(), from.getZ());
        while (count <= 20) {
            m.move(dir);
            if (!mc.world.getBlockState(m).isOf(Blocks.OBSIDIAN)) break;
            ++count;
        }
        return count;
    }

    private boolean isEnclosedByStone(BlockPos pos) {
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            if (!mc.world.getBlockState(pos.offset(d)).isOf(Blocks.STONE)) return false;
        }
        return true;
    }

    private boolean isEnclosedByNonObsidian(BlockPos pos) {
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            if (mc.world.getBlockState(pos.offset(d)).isOf(Blocks.OBSIDIAN)) return false;
        }
        return true;
    }

    private void evaluateChunk(ChunkPos pos, ChunkAnalysis analysis) {
        if (this.ignorePlayerChunk && pos.equals(this.lastPlayerChunk)) return;
        if (this.detectItems && this.chunkItemCounts.getOrDefault(pos, 0) > this.maxItems) return;
        if (this.detectXP.getValue() && this.chunkXPCounts.getOrDefault(pos, 0) > this.maxXP.getValue()) return;

        boolean suspicious = false;
        List<String> reasonList = new ArrayList<>();

        if (analysis.rotatedCount >= this.rotatedThreshold.getValue()) {
            suspicious = true;
            reasonList.add("Rotated: " + analysis.rotatedCount);
        }
        if (analysis.hasLongDripstone) { suspicious = true; reasonList.add("Long Dripstone"); }
        if (analysis.hasLongVine)      { suspicious = true; reasonList.add("Long Vine"); }
        if (analysis.allKelpFull)      { suspicious = true; reasonList.add("Grown Kelp"); }
        if (analysis.hasDioriteVein)   { suspicious = true; reasonList.add("Diorite Vein"); }
        if (analysis.hasObsidianVein)  { suspicious = true; reasonList.add("Obsidian Vein"); }

        if (!suspicious) return;

        if (this.flaggedChunks.add(pos) && this.notifiedChunks.add(pos)) {
            StringBuilder reasons = new StringBuilder();
            for (String reason : reasonList) reasons.append(reason).append(" ");

            int susBlockX = analysis.susBlockPos != null ? analysis.susBlockPos.getX() : pos.getStartX() + 8;
            int susBlockZ = analysis.susBlockPos != null ? analysis.susBlockPos.getZ() : pos.getStartZ() + 8;
            int finalX = susBlockX;
            int finalZ = susBlockZ;
            String reasonStr = reasons.toString().trim();

            mc.execute(() -> {
                if (mc.player == null) return;

                mc.getSoundManager().play(new PositionedSoundInstance(
                        SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                        SoundCategory.MASTER,
                        1.0F, 1.0F,
                        Random.create(),
                        mc.player.getBlockPos()
                ));

                String coords = alertCoords.getValue()
                        ? " §7(X: " + finalX + " Z: " + finalZ + ")"
                        : "";
                mc.player.sendMessage(
                        net.minecraft.text.Text.literal("§6[Chunk Finder] §f" + reasonStr + coords),
                        false);
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
            default     -> new Color(0,   255, 0,   alpha);
        };
    }

    private void renderFlaggedChunks(MatrixStack matrices) {
        if (this.flaggedChunks.isEmpty() || mc.player == null) return;

        Camera cam = RenderUtils.getCamera();
        Vec3d camPos = RenderUtils.getCameraPos(cam);

        int renderY = 63;
        Color fillColor    = getSelectedColor(80);
        Color outlineColor = new Color(255, 255, 255, 200);
        Color tracerColor  = getSelectedColor(200);

        int rendered = 0;

       RenderUtils.WorldBatch batch = RenderUtils.beginWorldBatch(matrices);

try {

    for (ChunkPos pos : suspiciousChunks) {

        double x1 = pos.getStartX();
        double y1 = mc.world.getBottomY();
        double z1 = pos.getStartZ();

        double x2 = x1 + 16;
        double y2 = mc.world.getTopY();
        double z2 = z1 + 16;

        // Box render
        RenderUtils.drawBox(
            matrices,
            x1, y1, z1,
            x2, y2, z2,
            1.0f, 0.0f, 0.0f, 0.25f
        );

        // Outline render
        RenderUtils.drawOutline(
            matrices,
            x1, y1, z1,
            x2, y2, z2,
            1.0f, 0.0f, 0.0f, 1.0f
        );

        // Optional tracer
        if (renderTracersSetting.getValue()) {

            Vec3d cam = mc.gameRenderer.getCamera().getPos();

            RenderUtils.drawLine(
                matrices,
                cam.x,
                cam.y,
                cam.z,
                x1 + 8,
                y1 + 1,
                z1 + 8,
                1.0f,
                0.0f,
                0.0f,
                1.0f
            );
        }
    }

} finally {
    RenderUtils.endWorldBatch(batch);
}
}

private static class ChunkAnalysis {
        int rotatedCount = 0;
        boolean hasLongDripstone = false;
        boolean hasLongVine = false;
        boolean allKelpFull = false;
        boolean hasDioriteVein = false;
        boolean hasObsidianVein = false;
        BlockPos susBlockPos = null;
    }
}

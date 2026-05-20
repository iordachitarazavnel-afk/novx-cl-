package com.xenon.module.modules.donut;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.Setting;
import com.xenon.utils.RenderUtils;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PointedDripstoneBlock;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.block.VineBlock;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GrowthFinder extends Module {

    private final Setting<Boolean> renderVines = new Setting<>("Render Vines", true);
    private final Setting<Boolean> renderDripstone = new Setting<>("Render Dripstone", true);
    private final Setting<Boolean> renderBerries = new Setting<>("Render Berries", true);
    private final Setting<Boolean> renderStandardChunks = new Setting<>("Render Gray Chunks", true);
    private final Setting<Float> alpha = new Setting<>("Alpha", 80.0f, 0.0f, 255.0f);

    private static final int SCAN_RADIUS = 8;
    private static final int CHUNKS_PER_TICK = 12;
    private static final int MIN_VINE_LENGTH = 6;
    private static final int MAX_VINE_SCAN_PER_CHUNK = 2;
    private static final double PLATE_HEIGHT = 0.08;

    private static final Color HIGH_SUSPICION_GRAY = new Color(135, 135, 135);
    private static final Color LOW_SUSPICION_GRAY = new Color(100, 100, 100);
    private static final Color SOURCE_PLATE_COLOR_BASE = new Color(203, 64, 255);
    private static final Color EXTREME_PLATE_COLOR_BASE = new Color(255, 198, 64);

    private final Set<ChunkPos> scannedChunks = new HashSet<>();
    private final Map<ChunkPos, SuspiciousGrowthData> suspiciousChunks = new HashMap<>();
    private final List<ChunkPos> scanQueue = new ArrayList<>();
    private ChunkPos lastQueueCenter = null;
    private int scanCursor = 0;

    private final List<ChunkPos> lockedBaseChunks = new ArrayList<>();
    private final Map<ChunkPos, Double> sourceHistory = new HashMap<>();

    public GrowthFinder() {
        super("Growth Finder", Category.DONUT);
        addSetting(renderVines);
        addSetting(renderDripstone);
        addSetting(renderBerries);
        addSetting(renderStandardChunks);
        addSetting(alpha);
    }

    @Override
    public void onEnable() { clearData(); }

    @Override
    public void onDisable() { clearData(); }

    private void clearData() {
        suspiciousChunks.clear();
        scannedChunks.clear();
        scanQueue.clear();
        scanCursor = 0;
        lastQueueCenter = null;
        lockedBaseChunks.clear();
        sourceHistory.clear();
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) return;

        ChunkPos playerChunk = new ChunkPos(mc.player.getBlockPos());
        rebuildScanQueueIfNeeded(playerChunk);
        processChunkBatch(mc.world);
        pruneFarChunks(playerChunk, SCAN_RADIUS);

        if (mc.world.getTime() % 20 == 0) {
            refreshLockedBaseChunk();
        }
    }

    private void rebuildScanQueueIfNeeded(ChunkPos playerChunk) {
        if (lastQueueCenter == null
                || playerChunk.x != lastQueueCenter.x || playerChunk.z != lastQueueCenter.z
                || scanCursor >= scanQueue.size()) {
            scanQueue.clear();
            for (int x = -SCAN_RADIUS; x <= SCAN_RADIUS; x++) {
                for (int z = -SCAN_RADIUS; z <= SCAN_RADIUS; z++) {
                    scanQueue.add(new ChunkPos(playerChunk.x + x, playerChunk.z + z));
                }
            }
            scanCursor = 0;
            lastQueueCenter = playerChunk;
        }
    }

    private void processChunkBatch(World world) {
        int processed = 0;
        while (scanCursor < scanQueue.size() && processed < CHUNKS_PER_TICK) {
            ChunkPos chunkPos = scanQueue.get(scanCursor++);
            if (scannedChunks.contains(chunkPos)) continue;
            WorldChunk chunk = world.getChunkManager().getWorldChunk(chunkPos.x, chunkPos.z, false);
            if (chunk != null) {
                analyzeChunk(chunk, chunkPos);
                scannedChunks.add(chunkPos);
            }
            processed++;
        }
    }

    private void pruneFarChunks(ChunkPos playerChunk, int radius) {
        int maxDistance = radius + 2;
        int pX = playerChunk.x;
        int pZ = playerChunk.z;
        suspiciousChunks.entrySet().removeIf(entry ->
                Math.abs(entry.getKey().x - pX) > maxDistance
                        || Math.abs(entry.getKey().z - pZ) > maxDistance);
        scannedChunks.removeIf(chunkPos ->
                Math.abs(chunkPos.x - pX) > maxDistance
                        || Math.abs(chunkPos.z - pZ) > maxDistance);
    }

    private void refreshLockedBaseChunk() {
        if (suspiciousChunks.isEmpty()) {
            sourceHistory.clear();
            return;
        }

        for (SuspiciousGrowthData data : suspiciousChunks.values()) {
            ChunkPos sourceChunk = data.baseChunk;
            sourceHistory.merge(sourceChunk, (double) data.suspicionLevel, Double::sum);
        }

        sourceHistory.entrySet().removeIf(e -> {
            double val = e.getValue() * 0.95;
            e.setValue(val);
            return val < 0.5;
        });

        List<Map.Entry<ChunkPos, Double>> ranked = new ArrayList<>(sourceHistory.entrySet());
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        lockedBaseChunks.clear();
        for (int i = 0; i < Math.min(2, ranked.size()); i++) {
            lockedBaseChunks.add(ranked.get(i).getKey());
        }
    }

    private void analyzeChunk(WorldChunk chunk, ChunkPos chunkPos) {
        int xStart = chunkPos.getStartX();
        int zStart = chunkPos.getStartZ();

        List<VineCluster> vineClusters = renderVines.getValue()
                ? detectTallGroundedVines(chunk, xStart, zStart) : Collections.emptyList();
        List<DripstoneCluster> dripstoneClusters = renderDripstone.getValue()
                ? detectMaxDripstoneClusters(chunk, xStart, zStart) : Collections.emptyList();
        List<BerryCluster> berryClusters = renderBerries.getValue()
                ? detectMaxGrownBerries(chunk, xStart, zStart) : Collections.emptyList();

        List<WeightedEvidence> evidencePoints = new ArrayList<>();
        double vineWeight = 0;
        int maxVineLength = 0;
        for (VineCluster cluster : vineClusters) {
            double weight = cluster.length;
            vineWeight += weight;
            if (cluster.length > maxVineLength) maxVineLength = cluster.length;
            evidencePoints.add(new WeightedEvidence(cluster.centroid(), weight));
        }

        double dripWeight = 0;
        for (DripstoneCluster cluster : dripstoneClusters) {
            dripWeight += 2.5;
            evidencePoints.add(new WeightedEvidence(cluster.center, 2.5));
        }
        double berryWeight = 0;
        for (BerryCluster cluster : berryClusters) {
            berryWeight += 1.0;
            evidencePoints.add(new WeightedEvidence(cluster.pos, 1.0));
        }

        int suspicionLevel = (int) Math.round(vineWeight * 0.5 + dripWeight * 0.75 + berryWeight * 1.0);
        if (suspicionLevel > 0) {
            BlockPos estimatedSource = calculateWeightedSource(evidencePoints, chunkPos);
            boolean isExtreme = maxVineLength >= 100;
            boolean isSource = maxVineLength >= 25;
            suspiciousChunks.put(chunkPos, new SuspiciousGrowthData(chunkPos, suspicionLevel,
                    new ChunkPos(estimatedSource), isExtreme, isSource, maxVineLength));
        } else {
            suspiciousChunks.remove(chunkPos);
        }
    }

    private List<VineCluster> detectTallGroundedVines(WorldChunk chunk, int xStart, int zStart) {
        List<VineCluster> clusters = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        int groundY = chunk.getBottomY();
        int tallClusters = 0;

        for (int x = xStart; x < xStart + 16; x += 2) {
            for (int z = zStart; z < zStart + 16; z += 2) {
                if (tallClusters >= MAX_VINE_SCAN_PER_CHUNK) break;
                int y = chunk.getHeightmap(Heightmap.Type.MOTION_BLOCKING).get(x - xStart, z - zStart);
                while (y >= groundY) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (visited.contains(pos)) { y--; continue; }
                    BlockState state = chunk.getBlockState(pos);
                    if (!isVineBlock(state.getBlock())) { y--; continue; }

                    List<BlockPos> positions = new ArrayList<>();
                    BlockPos currTrace = pos;
                    while (currTrace.getY() >= groundY && isVineBlock(chunk.getBlockState(currTrace).getBlock())) {
                        visited.add(currTrace);
                        positions.add(currTrace);
                        currTrace = currTrace.down();
                    }

                    if (positions.size() >= MIN_VINE_LENGTH) {
                        clusters.add(new VineCluster(positions));
                        tallClusters++;
                    }
                    y = currTrace.getY() - 1;
                }
            }
        }
        return clusters;
    }

    private boolean isVineBlock(Block block) {
        return block instanceof VineBlock
                || block == Blocks.CAVE_VINES
                || block == Blocks.CAVE_VINES_PLANT
                || block == Blocks.WEEPING_VINES
                || block == Blocks.WEEPING_VINES_PLANT
                || block == Blocks.TWISTING_VINES
                || block == Blocks.TWISTING_VINES_PLANT;
    }

    private List<DripstoneCluster> detectMaxDripstoneClusters(WorldChunk chunk, int xStart, int zStart) {
        List<DripstoneCluster> clusters = new ArrayList<>();
        for (int x = xStart; x < xStart + 16; x += 4) {
            for (int z = zStart; z < zStart + 16; z += 4) {
                int heightmapY = chunk.getHeightmap(Heightmap.Type.MOTION_BLOCKING).get(x - xStart, z - zStart);
                for (int y = -64; y <= heightmapY; y += 4) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (chunk.getBlockState(pos).getBlock() instanceof PointedDripstoneBlock) {
                        clusters.add(new DripstoneCluster(pos));
                        break;
                    }
                }
            }
        }
        return clusters;
    }

    private List<BerryCluster> detectMaxGrownBerries(WorldChunk chunk, int xStart, int zStart) {
        List<BerryCluster> clusters = new ArrayList<>();
        for (int x = xStart; x < xStart + 16; x += 2) {
            for (int z = zStart; z < zStart + 16; z += 2) {
                int heightmapY = chunk.getHeightmap(Heightmap.Type.MOTION_BLOCKING).get(x - xStart, z - zStart);
                for (int y = heightmapY; y >= heightmapY - 5; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    if (state.getBlock() instanceof SweetBerryBushBlock) {
                        try {
                            if (state.get(SweetBerryBushBlock.AGE) == 3) {
                                clusters.add(new BerryCluster(pos));
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        return clusters;
    }

    private BlockPos calculateWeightedSource(List<WeightedEvidence> evidencePoints, ChunkPos chunkPos) {
        if (evidencePoints.isEmpty())
            return new BlockPos(chunkPos.getStartX() + 8, 30, chunkPos.getStartZ() + 8);
        double sumX = 0, sumY = 0, sumZ = 0, sumW = 0;
        for (WeightedEvidence e : evidencePoints) {
            sumX += e.pos.getX() * e.weight;
            sumY += e.pos.getY() * e.weight;
            sumZ += e.pos.getZ() * e.weight;
            sumW += e.weight;
        }
        return new BlockPos((int) (sumX / sumW), (int) (sumY / sumW), (int) (sumZ / sumW));
    }

    @Override
    public void onRender(MatrixStack matrices, float tickDelta) {
        if (mc.world == null || mc.player == null) return;
        if (suspiciousChunks.isEmpty()) return;

        Camera cam = RenderUtils.getCamera();
        if (cam == null) return;
        Vec3d camPos = RenderUtils.getCameraPos(cam);

        int alphaInt = Math.max(0, Math.min(255, alpha.getValue().intValue()));

        matrices.push();
        RenderUtils.WorldBatch batch = RenderUtils.beginWorldBatch(matrices);
        for (SuspiciousGrowthData data : suspiciousChunks.values()) {
            boolean isStandard = !(data.extreme || data.source);
            if (isStandard && !renderStandardChunks.getValue()) continue;

            Color baseColor;
            if (data.extreme) baseColor = EXTREME_PLATE_COLOR_BASE;
            else if (data.source) baseColor = SOURCE_PLATE_COLOR_BASE;
            else if (data.suspicionLevel >= 5 || data.maxVineLength >= 4) baseColor = HIGH_SUSPICION_GRAY;
            else baseColor = LOW_SUSPICION_GRAY;

            Color finalColor = new Color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), alphaInt);

            double startX = data.chunkPos.getStartX();
            double startZ = data.chunkPos.getStartZ();
            double y = 30.0;

            double x1 = startX - camPos.x;
            double z1 = startZ - camPos.z;
            double x2 = startX + 16 - camPos.x;
            double z2 = startZ + 16 - camPos.z;
            double y1 = y - camPos.y;
            double y2 = y + PLATE_HEIGHT - camPos.y;
            batch.renderFilledBox(x1, y1, z1, x2, y2, z2, finalColor);
        }
        batch.flush();
        matrices.pop();
    }

    private static class VineCluster {
        final int length;
        final List<BlockPos> positions;
        VineCluster(List<BlockPos> p) { positions = p; length = p.size(); }
        BlockPos centroid() {
            long x = 0, y = 0, z = 0;
            for (BlockPos p : positions) { x += p.getX(); y += p.getY(); z += p.getZ(); }
            return new BlockPos((int) (x / positions.size()), (int) (y / positions.size()), (int) (z / positions.size()));
        }
    }

    private static class DripstoneCluster {
        final BlockPos center;
        DripstoneCluster(BlockPos c) { center = c; }
    }

    private record BerryCluster(BlockPos pos) {}
    private record WeightedEvidence(BlockPos pos, double weight) {}
    private record SuspiciousGrowthData(ChunkPos chunkPos, int suspicionLevel, ChunkPos baseChunk,
                                        boolean extreme, boolean source, int maxVineLength) {}
}

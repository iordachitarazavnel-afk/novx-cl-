package com.xenon.module.modules.donut;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.block.AmethystClusterBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.VineBlock;
import net.minecraft.client.render.Camera;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import com.xenon.event.EventListener;
import com.xenon.event.events.Render3DEvent;
import com.xenon.event.events.TickEvent;
import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.Setting;
import com.xenon.utils.RenderUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SuspiciousChunkFinder extends Module {

    private final Setting<Float> minPointsSetting = new Setting<>("Min Points", 12.0f);
    private final Setting<Float> scanRadiusSetting = new Setting<>("Scan Radius", 6.0f);
    private final Setting<Boolean> renderTracersSetting = new Setting<>("Tracers", true);

    private final Set<ChunkPos> detectedChunks = ConcurrentHashMap.newKeySet();
    private final Map<ChunkPos, ChunkScanResult> chunkResults = new ConcurrentHashMap<>();
    private final Set<ChunkPos> activelyScanning = ConcurrentHashMap.newKeySet();
    private ExecutorService scanExecutor;

    public SuspiciousChunkFinder() {
        super("Sus Chunk Finder", "Advanced generation bug analyzer", -1, Category.DONUT);
        this.addSettings(this.minPointsSetting, this.scanRadiusSetting, this.renderTracersSetting);
    }

    @Override
    public void onEnable() {
        this.scanExecutor = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
        this.detectedChunks.clear();
        this.chunkResults.clear();
        this.activelyScanning.clear();
    }

    @Override
    public void onDisable() {
        if (this.scanExecutor != null) {
            this.scanExecutor.shutdownNow();
            this.scanExecutor = null;
        }
    }

    @EventListener
    public void onTick(TickEvent event) {
        if (mc.world == null || mc.player == null || this.scanExecutor == null) return;

        ChunkPos playerChunk = mc.player.getChunkPos();
        int radius = Math.round(this.scanRadiusSetting.getValue());

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                ChunkPos targetPos = new ChunkPos(playerChunk.x + x, playerChunk.z + z);
                if (!chunkResults.containsKey(targetPos) && !activelyScanning.contains(targetPos)) {
                    WorldChunk chunk = mc.world.getChunk(targetPos.x, targetPos.z);
                    if (chunk != null && mc.world.isChunkLoaded(targetPos.x, targetPos.z)) {
                        this.activelyScanning.add(targetPos);
                        this.scanExecutor.submit(() -> this.scanChunkAsync(mc.world, chunk, targetPos));
                    }
                }
            }
        }
    }

    private void scanChunkAsync(World world, WorldChunk chunk, ChunkPos pos) {
        try {
            ChunkScanResult result = analyzeChunkBlocks(world, chunk, pos);
            if (result.score >= this.minPointsSetting.getValue()) {
                this.detectedChunks.add(pos);
                this.chunkResults.put(pos, result);
            }
        } catch (Exception ignored) {
        } finally {
            this.activelyScanning.remove(pos);
        }
    }

    private ChunkScanResult analyzeChunkBlocks(World world, WorldChunk chunk, ChunkPos chunkPos) {
        float totalScore = 0;
        int rotated = 0, indicator = 0, amethyst = 0, growth = 0;
        Map<OriginType, Integer> map = new HashMap<>();

        ChunkSection[] sections = chunk.getSectionArray();
        BlockPos.Mutable mutable = new BlockPos.Mutable();

        for (int sY = 0; sY < sections.length; sY++) {
            ChunkSection section = sections[sY];
            if (section == null || section.isEmpty()) continue;

            int worldY = chunk.getBottomY() + (sY << 4);

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = 0; y < 16; y++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.isOf(Blocks.AIR)) continue;

                        mutable.set(chunkPos.getStartX() + x, worldY + y, chunkPos.getStartZ() + z);
                        
                        if (state.isOf(Blocks.DEEPSLATE) && state.contains(Properties.AXIS)) {
                            if (state.get(Properties.AXIS) != Direction.Axis.Y && mutable.getY() < 16) {
                                rotated++;
                                totalScore += 2.0f;
                                map.merge(OriginType.ROTATED, 1, Integer::sum);
                            }
                        }
                        if (state.isOf(Blocks.AMETHYST_CLUSTER)) {
                            amethyst++;
                            totalScore += 1.5f;
                            map.merge(OriginType.AMETHYST, 1, Integer::sum);
                        }
                        if (state.isOf(Blocks.VINE) || state.isOf(Blocks.CAVE_VINES)) {
                            growth++;
                            totalScore += 0.5f;
                            map.merge(OriginType.VINES, 1, Integer::sum);
                        }
                    }
                }
            }
        }
        return new ChunkScanResult(totalScore, indicator, amethyst, growth, rotated, 0, map);
    }

    @EventListener
    public void onRender(Render3DEvent event) {
        if (mc.player == null || detectedChunks.isEmpty()) return;

        Camera cam = RenderUtils.getCamera();
        Vec3d camPos = RenderUtils.getCameraPos();

        event.matrixStack.push();
        event.matrixStack.multiply(RotationAxis.POSITIVE_X.rotationDegrees(cam.getPitch()));
        event.matrixStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(cam.getYaw() + 180.0f));

        for (ChunkPos pos : detectedChunks) {
            double cx = pos.getStartX() - camPos.x;
            double cz = pos.getStartZ() - camPos.z;
            double cy = mc.player.getY() - 2 - camPos.y;

            if (this.renderTracersSetting.getValue()) {
                RenderUtils.renderLine(event.matrixStack, new Color(255, 170, 0, 200), camPos, new Vec3d(pos.getStartX() + 8, mc.player.getY(), pos.getStartZ() + 8));
            }
        }
        event.matrixStack.pop();
    }

    private static final class ChunkScanResult {
        private final float score;
        private final int indicatorCount;
        private final int amethystHits;
        private final int growthHits;
        private final int rotatedHits;
        private final int sourceHitsLocal;
        private final Map<OriginType, Integer> originWeights;

        private ChunkScanResult(float score, int indicatorCount, int amethystHits, int growthHits, int rotatedHits,
                int sourceHitsLocal, Map<OriginType, Integer> originWeights) {
            this.score = score;
            this.indicatorCount = indicatorCount;
            this.amethystHits = amethystHits;
            this.growthHits = growthHits;
            this.rotatedHits = rotatedHits;
            this.sourceHitsLocal = sourceHitsLocal;
            this.originWeights = originWeights;
        }
    }

    private enum OriginType { AMETHYST, KELP, CAVE_VINES, VINES, ROTATED }
}

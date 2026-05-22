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
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SuspiciousChunkFinder extends Module {

    enum SusOriginType { AMETHYST, KELP, CAVE_VINES, VINES, ROTATED }

    static class SusChunkScanResult {
        float score;
        int indicatorCount;
        int amethystHits;
        int growthHits;
        int rotatedHits;
        int sourceHitsLocal;
        Map<SusOriginType, Integer> originWeights;

        SusChunkScanResult(float score, int indicatorCount, int amethystHits, int growthHits,
                           int rotatedHits, int sourceHitsLocal, Map<SusOriginType, Integer> originWeights) {
            this.score = score;
            this.indicatorCount = indicatorCount;
            this.amethystHits = amethystHits;
            this.growthHits = growthHits;
            this.rotatedHits = rotatedHits;
            this.sourceHitsLocal = sourceHitsLocal;
            this.originWeights = originWeights;
        }
    }

    private final Setting<Float> minPointsSetting = new Setting<>("Min Points", 12.0f, 1.0f, 50.0f);
    private final Setting<Float> scanRadiusSetting = new Setting<>("Scan Radius", 6.0f, 1.0f, 16.0f);
    private final Setting<Boolean> renderTracersSetting = new Setting<>("Tracers", true);

    private final Set<ChunkPos> detectedChunks = ConcurrentHashMap.newKeySet();
    private final Map<ChunkPos, SusChunkScanResult> chunkResults = new ConcurrentHashMap<>();
    private final Set<ChunkPos> activelyScanning = ConcurrentHashMap.newKeySet();
    private ExecutorService scanExecutor;

    public SuspiciousChunkFinder() {
        super("Sus Chunk Finder", Category.DONUT);
        addSetting(minPointsSetting);
        addSetting(scanRadiusSetting);
        addSetting(renderTracersSetting);
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
        this.detectedChunks.clear();
        this.chunkResults.clear();
        this.activelyScanning.clear();
    }

    @Override
    public void onTick() {
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
            SusChunkScanResult result = analyzeChunkBlocks(world, chunk, pos);
            if (result.score >= this.minPointsSetting.getValue()) {
                if (this.detectedChunks.add(pos)) {
                    this.chunkResults.put(pos, result);
                    int finalX = pos.getStartX() + 8;
                    int finalZ = pos.getStartZ() + 8;
                    float finalScore = result.score;
                    net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
                        com.xenon.gui.notification.NotificationManager.INSTANCE.push(
                                "Sus Chunk Finder",
                                "X:" + finalX + " Z:" + finalZ + " Score:" + (int) finalScore,
                                new net.minecraft.item.ItemStack(net.minecraft.item.Items.AMETHYST_SHARD),
                                0xFFFFAA00
                        );
                    });
                }
            }
        } catch (Exception ignored) {
        } finally {
            this.activelyScanning.remove(pos);
        }
    }

    private SusChunkScanResult analyzeChunkBlocks(World world, WorldChunk chunk, ChunkPos chunkPos) {
        float totalScore = 0;
        int rotated = 0, indicator = 0, amethyst = 0, growth = 0;
        Map<SusOriginType, Integer> map = new HashMap<>();

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
                                map.merge(SusOriginType.ROTATED, 1, Integer::sum);
                            }
                        }
                        if (state.isOf(Blocks.AMETHYST_CLUSTER)) {
                            amethyst++;
                            totalScore += 1.5f;
                            map.merge(SusOriginType.AMETHYST, 1, Integer::sum);
                        }
                        if (state.isOf(Blocks.VINE) || state.isOf(Blocks.CAVE_VINES)) {
                            growth++;
                            totalScore += 0.5f;
                            map.merge(SusOriginType.VINES, 1, Integer::sum);
                        }
                    }
                }
            }
        }
        return new SusChunkScanResult(totalScore, indicator, amethyst, growth, rotated, 0, map);
    }

    @Override
    public void onRender(MatrixStack matrices, float tickDelta) {
        if (mc.player == null || detectedChunks.isEmpty()) return;

        Camera cam = RenderUtils.getCamera();
        Vec3d camPos = RenderUtils.getCameraPos(cam);

        Color tracerColor  = new Color(255, 170, 0, 200);
        Color fillColor    = new Color(255, 170, 0, 60);
        Color outlineColor = new Color(255, 170, 0, 200);

        double renderY = mc.player.getY();

        RenderUtils.WorldBatch batch = RenderUtils.beginWorldBatch(matrices);
        matrices.push();
        matrices.translate(-camPos.x, -camPos.y, -camPos.z);

        for (ChunkPos pos : detectedChunks) {
            double x1 = pos.getStartX();
            double z1 = pos.getStartZ();
            double x2 = x1 + 16.0;
            double z2 = z1 + 16.0;
            double y1 = renderY - 0.1;
            double y2 = renderY + 0.15;

            batch.renderFilledBox(x1, y1, z1, x2, y2, z2, fillColor);
            batch.renderOutlineBox(x1, y1, z1, x2, y2, z2, outlineColor);

            if (renderTracersSetting.getValue()) {
                batch.renderLine(
                        tracerColor,
                        camPos,
                        new Vec3d(pos.getStartX() + 8.0, renderY, pos.getStartZ() + 8.0),
                        2.0f
                );
            }
        }

        matrices.pop();
        batch.flush();
    }
}

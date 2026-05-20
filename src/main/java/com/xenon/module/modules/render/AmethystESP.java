package com.xenon.module.modules.render;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.module.modules.client.XenonPlus;
import com.xenon.setting.Setting;
import com.xenon.utils.RenderUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.WorldChunk;

import java.awt.Color;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class AmethystESP extends Module {

    private static final double TRACER_START_DISTANCE = 150.0D;
    private static final double TRACER_END_DISTANCE   = 24.0D;
    private static final double TRACER_BEHIND_SPREAD  = 2.75D;
    private static final double CHUNK_THICKNESS       = 0.05D;

    private final Setting<Float>   simDistance      = new Setting<>("Sim Distance",     8.0f,  1.0f, 32.0f);
    private final Setting<Float>   clusterThreshold = new Setting<>("Min Cluster Size", 3.0f,  1.0f, 20.0f);
    private final Setting<Float>   chunkY           = new Setting<>("Chunk Y Level",    55.0f, -64.0f, 320.0f);
    private final Setting<Boolean> esp              = new Setting<>("Block ESP",        true);
    private final Setting<Boolean> chunkMark        = new Setting<>("Chunk Mark",       true);
    private final Setting<Boolean> tracers          = new Setting<>("Show Tracers",     true);
    private final Setting<Boolean> chatNotify       = new Setting<>("Chat Alert",       true);
    private final Setting<Color>   espColor         = new Setting<>("ESP Color",        new Color(180, 100, 255));
    private final Setting<Color>   chunkColor       = new Setting<>("Chunk Color",      new Color(180, 100, 255));

    private static AmethystESP INSTANCE;
    private final Map<ChunkPos, Set<BlockPos>> foundClusters = new ConcurrentHashMap<>();
    private final Set<ChunkPos> notifiedChunks = ConcurrentHashMap.newKeySet();
    private int tickCounter = 0;

    public AmethystESP() {
        super("Amethyst ESP", Category.RENDER);
        INSTANCE = this;
        addSetting(simDistance);
        addSetting(clusterThreshold);
        addSetting(chunkY);
        addSetting(esp);
        addSetting(chunkMark);
        addSetting(tracers);
        addSetting(chatNotify);
        addSetting(espColor);
        addSetting(chunkColor);
    }

    public static AmethystESP instance() { return INSTANCE; }

    @Override
    public void onEnable() {
        foundClusters.clear();
        notifiedChunks.clear();
        fullScan();
    }

    @Override
    public void onDisable() {
        foundClusters.clear();
        notifiedChunks.clear();
    }

    private void fullScan() {
        if (mc.world == null || mc.player == null) return;
        ChunkPos center = mc.player.getChunkPos();
        int radius = simDistance.getValue().intValue();
        int scanned = 0;

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                WorldChunk chunk = mc.world.getChunkManager()
                        .getWorldChunk(center.x + x, center.z + z, false);
                if (chunk != null) { scanChunk(chunk); scanned++; }
            }
        }
        sendChat("§7Scan: " + scanned + " Chunks | §d" + foundClusters.size() + " §7Geoden");
    }

    private void scanChunk(WorldChunk chunk) {
        if (mc.world == null) return;

        ChunkPos cp = chunk.getPos();
        Set<BlockPos> hits = new HashSet<>();
        int baseX = cp.x << 4;
        int baseZ = cp.z << 4;

        for (int y = -64; y <= 70; y++) {
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    BlockPos pos = new BlockPos(baseX + lx, y, baseZ + lz);
                    if (y <= 50 && mc.world.getLightLevel(LightType.BLOCK, pos) == 5) {
                        if (isAmethystNearby(pos)) {
                            hits.add(pos.toImmutable());
                        }
                    }
                }
            }
        }

        if (hits.size() >= clusterThreshold.getValue().intValue()) {
            foundClusters.put(cp, hits);
            if (chatNotify.getValue() && notifiedChunks.add(cp)) {
                sendChat("Amethyst Clusters on " + cp.getCenterX() + " " + cp.getCenterZ()
                        + " §7(" + hits.size() + " hits)");
            }
        } else {
            foundClusters.remove(cp);
            notifiedChunks.remove(cp);
        }
    }

    private boolean isAmethystNearby(BlockPos pos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockState state = mc.world.getBlockState(pos.add(dx, dy, dz));
                    if (state.isOf(Blocks.AMETHYST_CLUSTER)
                            || state.isOf(Blocks.LARGE_AMETHYST_BUD)
                            || state.isOf(Blocks.MEDIUM_AMETHYST_BUD)
                            || state.isOf(Blocks.SMALL_AMETHYST_BUD)
                            || state.isOf(Blocks.BUDDING_AMETHYST)
                            || state.isOf(Blocks.AMETHYST_BLOCK)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public void onTick() {
        if (mc.world == null || mc.player == null) return;
        if (++tickCounter % 40 == 0) {
            ChunkPos center = mc.player.getChunkPos();
            int radius = simDistance.getValue().intValue();
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    WorldChunk chunk = mc.world.getChunkManager()
                            .getWorldChunk(center.x + x, center.z + z, false);
                    if (chunk != null) scanChunk(chunk);
                }
            }
        }
    }

    @Override
    public void onRender(MatrixStack matrices, float tickDelta) {
        if (mc.world == null || mc.player == null || foundClusters.isEmpty()) return;

        Camera camera = RenderUtils.getCamera();
        if (camera == null) return;

        Vec3d camPos      = RenderUtils.getCameraPos(camera);
        Vec3d camForward  = RenderUtils.getCameraForward(camera);
        Vec3d camRight    = RenderUtils.getCameraRight(camera);
        Vec3d camUp       = RenderUtils.getCameraUp(camForward, camRight);
        Vec3d tracerStart = camForward.multiply(TRACER_START_DISTANCE);

        Color espFill   = withAlpha(espColor.getValue(),   180);
        Color chunkFill = withAlpha(chunkColor.getValue(), 200);
        Color tracer    = withAlpha(espColor.getValue(),   220);

        matrices.push();

        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);

        RenderUtils.WorldBatch batch = RenderUtils.beginWorldBatch(matrices);

        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);

        double fixedY = chunkY.getValue().doubleValue();

        for (Map.Entry<ChunkPos, Set<BlockPos>> entry : foundClusters.entrySet()) {
            ChunkPos cp = entry.getKey();
            Set<BlockPos> positions = entry.getValue();
            if (positions.isEmpty()) continue;

            if (chunkMark.getValue()) {
                double x1 = cp.getStartX() - camPos.x;
                double z1 = cp.getStartZ() - camPos.z;
                double x2 = cp.getEndX()   - camPos.x + 1;
                double z2 = cp.getEndZ()   - camPos.z + 1;
                double y1 = fixedY - camPos.y;
                double y2 = y1 + CHUNK_THICKNESS;

                batch.renderFilledBox(x1, y1, z1, x2, y2, z2, chunkFill);
            }

            if (esp.getValue()) {
                for (BlockPos p : positions) {
                    double x1 = p.getX() - camPos.x;
                    double y1 = p.getY() - camPos.y;
                    double z1 = p.getZ() - camPos.z;
                    batch.renderFilledBox(x1, y1, z1, x1 + 1.0, y1 + 1.0, z1 + 1.0, espFill);
                }
            }

            if (tracers.getValue()) {
                BlockPos nearest = null;
                double nearestDist = Double.MAX_VALUE;
                for (BlockPos p : positions) {
                    double dist = p.getSquaredDistance(mc.player.getBlockPos());
                    if (dist < nearestDist) { nearestDist = dist; nearest = p; }
                }

                if (nearest != null) {
                    Vec3d relTarget = new Vec3d(
                            nearest.getX() + 0.5 - camPos.x,
                            nearest.getY() + 0.5 - camPos.y,
                            nearest.getZ() + 0.5 - camPos.z
                    );
                    Vec3d tracerEnd = RenderUtils.getSpreadTracerEnd(
                            relTarget, camForward, camRight, camUp,
                            TRACER_END_DISTANCE, TRACER_BEHIND_SPREAD
                    );
                    batch.renderLine(tracer, tracerStart, tracerEnd, XenonPlus.tracerLineWidth());
                }
            }
        }

        batch.flush();

        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        matrices.pop();
    }

    private Color withAlpha(Color base, int alpha) {
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), Math.min(255, alpha));
    }

    public static void onChunkData(int cx, int cz) {
        if (INSTANCE == null || !INSTANCE.isEnabled()) return;
        WorldChunk chunk = MinecraftClient.getInstance().world
                .getChunkManager().getWorldChunk(cx, cz, false);
        if (chunk != null) INSTANCE.scanChunk(chunk);
    }

    public static void onBlockUpdate(BlockPos pos, BlockState state) {
        onChunkData(pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static void onParticle(String type, double x, double y, double z) {}

    public static void renderHud(DrawContext context, float delta) {
        if (INSTANCE == null || !INSTANCE.isEnabled()) return;
        context.drawText(
                MinecraftClient.getInstance().textRenderer,
                "§dAmethystESP: §f" + INSTANCE.foundClusters.size() + " Geoden",
                10, 10, -1, true
        );
    }

    private void sendChat(String message) {
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("§d[AmethystESP] §f" + message), false);
        }
    }
}

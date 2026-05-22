package com.xenon.module.modules.donut;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.Setting;
import com.xenon.utils.RenderUtils;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.util.math.*;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.ChunkSection;

import java.awt.*;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ArchonRadar extends Module {

    private final Setting<Float>   confirmThresh   = new Setting<>("Confirm Threshold", 60.0f, 10.0f, 300.0f);
    private final Setting<Boolean> requireMulti    = new Setting<>("Require Multi-Flag", true);
    private final Setting<Boolean> chatNotify      = new Setting<>("Chat Notify", true);
    private final Setting<Integer> scanRadius      = new Setting<>("Scan Radius", 8, 1, 16);
    private final Setting<Integer> scanInterval    = new Setting<>("Scan Interval", 80, 20, 400);
    private final Setting<Float>   wSpawner        = new Setting<>("Weight Spawner", 60.0f, 1.0f, 200.0f);
    private final Setting<Float>   wSuspicious     = new Setting<>("Weight Sus Block", 4.0f, 0.5f, 50.0f);
    private final Setting<Float>   soundBonus      = new Setting<>("Sound Bonus", 35.0f, 1.0f, 200.0f);
    private final Setting<Float>   playerBonus     = new Setting<>("Player Bonus", 50.0f, 1.0f, 200.0f);
    private final Setting<Float>   lightBonus      = new Setting<>("Light Bonus", 6.0f, 0.5f, 50.0f);
    private final Setting<Integer> lightInterval   = new Setting<>("Light Interval", 200, 40, 600);
    private final Setting<Boolean> showTracers     = new Setting<>("Tracers", true);
    private final Setting<Boolean> showPillar      = new Setting<>("Pillar", true);
    private final Setting<Integer> pillarHeight    = new Setting<>("Pillar Height", 100, 16, 320);

    enum RadarFlag { BLOCK_SCAN, SOUND, LIGHT, PLAYER_SEEN }

    static class RadarData {
        final Set<RadarFlag> flags = ConcurrentHashMap.newKeySet();
        double score = 0;
        boolean confirmed = false;
        int surfaceY = 64;
        void addFlag(RadarFlag f, double bonus) { flags.add(f); score += bonus; }
    }

    private final ConcurrentHashMap<ChunkPos, RadarData> radar = new ConcurrentHashMap<>();
    private int blockTick = 0, lightTick = 0;

    public ArchonRadar() {
        super("Archon Radar", Category.DONUT);
        addSetting(confirmThresh);
        addSetting(requireMulti);
        addSetting(chatNotify);
        addSetting(scanRadius);
        addSetting(scanInterval);
        addSetting(wSpawner);
        addSetting(wSuspicious);
        addSetting(soundBonus);
        addSetting(playerBonus);
        addSetting(lightBonus);
        addSetting(lightInterval);
        addSetting(showTracers);
        addSetting(showPillar);
        addSetting(pillarHeight);
    }

    @Override
    public void onEnable()  { radar.clear(); blockTick = 0; lightTick = 0; }
    @Override
    public void onDisable() { radar.clear(); }

    @Override
    public void onTick() {
        if (mc.world == null || mc.player == null) return;
        if (++blockTick >= scanInterval.getValue()) { blockTick = 0; runBlockScan(); runPlayerScan(); }
        if (++lightTick >= lightInterval.getValue()) { lightTick = 0; runLightScan(); }
    }

    private boolean isSuspicious(net.minecraft.block.Block b) {
        return b == Blocks.OBSIDIAN || b == Blocks.CRYING_OBSIDIAN
            || b == Blocks.END_STONE || b == Blocks.END_STONE_BRICKS
            || b == Blocks.CHEST || b == Blocks.BARREL
            || b == Blocks.HOPPER || b == Blocks.AMETHYST_BLOCK
            || b == Blocks.AMETHYST_CLUSTER || b == Blocks.SPAWNER;
    }

    private void runBlockScan() {
        int pCx = mc.player.getChunkPos().x;
        int pCz = mc.player.getChunkPos().z;
        int rad = scanRadius.getValue();
        int bottomY = mc.world.getBottomY();

        for (int dx = -rad; dx <= rad; dx++) {
            for (int dz = -rad; dz <= rad; dz++) {
                WorldChunk chunk = mc.world.getChunk(pCx + dx, pCz + dz);
                if (chunk == null) continue;
                ChunkPos cp = chunk.getPos();
                ChunkSection[] sections = chunk.getSectionArray();
                double score = 0;

                for (int i = 0; i < sections.length; i++) {
                    ChunkSection sec = sections[i];
                    if (sec == null || sec.isEmpty()) continue;
                    int secBaseY = bottomY + i * 16;
                    if (secBaseY + 16 > 0) continue;

                    for (int bx = 0; bx < 16; bx++)
                        for (int by = 0; by < 16; by++)
                            for (int bz = 0; bz < 16; bz++) {
                                var b = sec.getBlockState(bx, by, bz).getBlock();
                                if (b == Blocks.SPAWNER) score += wSpawner.getValue();
                                else if (isSuspicious(b)) score += wSuspicious.getValue();
                            }
                }

                if (score > 0) {
                    RadarData data = radar.computeIfAbsent(cp, k -> new RadarData());
                    data.addFlag(RadarFlag.BLOCK_SCAN, score);
                    data.surfaceY = mc.world.getTopY(Heightmap.Type.WORLD_SURFACE, cp.getStartX() + 8, cp.getStartZ() + 8);
                    tryConfirm(cp, data);
                }
            }
        }
    }

    private void runPlayerScan() {
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            if (player.getY() >= 0) continue;
            ChunkPos cp = player.getChunkPos();
            RadarData data = radar.computeIfAbsent(cp, k -> new RadarData());
            data.addFlag(RadarFlag.PLAYER_SEEN, playerBonus.getValue());
            tryConfirm(cp, data);
        }
    }

    private void runLightScan() {
        int pCx = mc.player.getChunkPos().x;
        int pCz = mc.player.getChunkPos().z;
        int rad = scanRadius.getValue();
        int bottomY = mc.world.getBottomY();

        for (int dx = -rad; dx <= rad; dx++) {
            for (int dz = -rad; dz <= rad; dz++) {
                WorldChunk chunk = mc.world.getChunk(pCx + dx, pCz + dz);
                if (chunk == null) continue;
                ChunkPos cp = chunk.getPos();
                ChunkSection[] sections = chunk.getSectionArray();
                int litCount = 0;

                for (int i = 0; i < sections.length; i++) {
                    ChunkSection sec = sections[i];
                    if (sec == null || sec.isEmpty()) continue;
                    int secBaseY = bottomY + i * 16;
                    if (secBaseY + 16 > 0) continue;

                    for (int bx = 1; bx < 15; bx++)
                        for (int by = 1; by < 15; by++)
                            for (int bz = 1; bz < 15; bz++) {
                                if (sec.getBlockState(bx, by, bz).isAir()) {
                                    BlockPos bp = new BlockPos(cp.getStartX() + bx, secBaseY + by, cp.getStartZ() + bz);
                                    if (mc.world.getLightLevel(LightType.BLOCK, bp) >= 1) litCount++;
                                }
                            }
                }

                if (litCount > 0) {
                    RadarData data = radar.computeIfAbsent(cp, k -> new RadarData());
                    data.addFlag(RadarFlag.LIGHT, litCount * lightBonus.getValue());
                    data.surfaceY = mc.world.getTopY(Heightmap.Type.WORLD_SURFACE, cp.getStartX() + 8, cp.getStartZ() + 8);
                    tryConfirm(cp, data);
                }
            }
        }
    }

    @Override
    public void onPacketReceive(Packet<?> packet) {
        if (!(packet instanceof PlaySoundS2CPacket pkt)) return;
        if (mc.player == null) return;
        double sy = pkt.getY();
        if (sy >= 0) return;
        ChunkPos cp = new ChunkPos(new BlockPos((int)pkt.getX(), (int)sy, (int)pkt.getZ()));
        RadarData data = radar.computeIfAbsent(cp, k -> new RadarData());
        data.addFlag(RadarFlag.SOUND, soundBonus.getValue());
        tryConfirm(cp, data);
    }

    private void tryConfirm(ChunkPos cp, RadarData data) {
        if (data.confirmed) return;
        boolean scoreOk = data.score >= confirmThresh.getValue();
        boolean flagsOk = !requireMulti.getValue() || data.flags.size() >= 2;
        if (scoreOk && flagsOk) {
            data.confirmed = true;
            if (chatNotify.getValue()) {
                mc.execute(() -> {
                    com.xenon.gui.notification.NotificationManager.INSTANCE.push(
                            "Archon Radar",
                            "BASE X:" + (cp.x * 16) + " Z:" + (cp.z * 16),
                            new net.minecraft.item.ItemStack(net.minecraft.item.Items.ENDER_EYE),
                            0xFFFF2020
                    );
                });
            }
        }
    }

    @Override
    public void onRender(MatrixStack matrices, float tickDelta) {
        if (mc.player == null || mc.world == null) return;

        Camera cam = RenderUtils.getCamera();
        Vec3d camPos = RenderUtils.getCameraPos(cam);
        Color fill   = new Color(225, 25, 25, 30);
        Color outline = new Color(225, 25, 25, 220);
        Color tracer  = new Color(225, 25, 25, 200);
        Color pillarFill = new Color(225, 25, 25, 25);
        Color pillarLine = new Color(225, 25, 25, 180);

        RenderUtils.WorldBatch batch = RenderUtils.beginWorldBatch(matrices);
        matrices.push();
        matrices.translate(-camPos.x, -camPos.y, -camPos.z);

        for (Map.Entry<ChunkPos, RadarData> e : radar.entrySet()) {
            if (!e.getValue().confirmed) continue;
            ChunkPos cp = e.getKey();
            double plateY = e.getValue().surfaceY + 0.05;
            double x0 = cp.getStartX(), z0 = cp.getStartZ();
            batch.renderFilledBox(x0, plateY - 0.05, z0, x0 + 16, plateY + 0.15, z0 + 16, fill);
            batch.renderOutlineBox(x0, plateY - 0.05, z0, x0 + 16, plateY + 0.15, z0 + 16, outline);
            if (showTracers.getValue())
                batch.renderLine(tracer, camPos, new Vec3d(x0 + 8, plateY, z0 + 8), 2.0f);
            if (showPillar.getValue()) {
                int h = pillarHeight.getValue();
                batch.renderFilledBox(x0, plateY, z0, x0 + 16, plateY + h, z0 + 16, pillarFill);
                batch.renderOutlineBox(x0, plateY, z0, x0 + 16, plateY + h, z0 + 16, pillarLine);
            }
        }

        matrices.pop();
        batch.flush();
    }
}

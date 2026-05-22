package com.xenon.module.modules.donut;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.Setting;
import com.xenon.utils.RenderUtils;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.util.math.*;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.ChunkSection;

import java.awt.*;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AmethystLodeFinder extends Module {

    private final Setting<Integer> scanRadius   = new Setting<>("Scan Radius", 8, 1, 16);
    private final Setting<Integer> scanInterval = new Setting<>("Scan Interval", 100, 20, 400);
    private final Setting<Float>   wSpawner     = new Setting<>("Weight Spawner", 50.0f, 1.0f, 200.0f);
    private final Setting<Float>   wObsidian    = new Setting<>("Weight Obsidian", 3.0f, 0.5f, 50.0f);
    private final Setting<Float>   wAmethyst    = new Setting<>("Weight Amethyst", 8.0f, 0.5f, 100.0f);
    private final Setting<Float>   flagThresh   = new Setting<>("Flag Threshold", 80.0f, 10.0f, 500.0f);
    private final Setting<Boolean> chatNotify   = new Setting<>("Chat Notify", true);
    private final Setting<Boolean> showTracers  = new Setting<>("Tracers", true);

    private static class ChunkData {
        double score = 0;
        boolean flagged = false;
        int surfaceY = 64;
    }

    private final ConcurrentHashMap<ChunkPos, ChunkData> chunkData = new ConcurrentHashMap<>();
    private int scanTick = 0;

    public AmethystLodeFinder() {
        super("Amethyst Lode Finder", Category.DONUT);
        addSetting(scanRadius);
        addSetting(scanInterval);
        addSetting(wSpawner);
        addSetting(wObsidian);
        addSetting(wAmethyst);
        addSetting(flagThresh);
        addSetting(chatNotify);
        addSetting(showTracers);
    }

    @Override
    public void onEnable()  { chunkData.clear(); scanTick = 0; }
    @Override
    public void onDisable() { chunkData.clear(); }

    @Override
    public void onTick() {
        if (mc.world == null || mc.player == null) return;
        if (++scanTick < scanInterval.getValue()) return;
        scanTick = 0;
        runBlockScan();
    }

    private void runBlockScan() {
        int pCx = mc.player.getChunkPos().x;
        int pCz = mc.player.getChunkPos().z;
        int rad  = scanRadius.getValue();
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
                    if (secBaseY + 16 > 0) continue; // only below Y=0

                    for (int bx = 0; bx < 16; bx++) {
                        for (int by = 0; by < 16; by++) {
                            for (int bz = 0; bz < 16; bz++) {
                                var b = sec.getBlockState(bx, by, bz).getBlock();
                                if (b == Blocks.SPAWNER)         score += wSpawner.getValue();
                                else if (b == Blocks.OBSIDIAN)   score += wObsidian.getValue();
                                else if (b == Blocks.AMETHYST_CLUSTER || b == Blocks.LARGE_AMETHYST_BUD
                                      || b == Blocks.MEDIUM_AMETHYST_BUD || b == Blocks.SMALL_AMETHYST_BUD)
                                    score += wAmethyst.getValue();
                            }
                        }
                    }
                }

                if (score > 0) {
                    ChunkData data = chunkData.computeIfAbsent(cp, k -> new ChunkData());
                    data.score += score;
                    data.surfaceY = mc.world.getTopY(Heightmap.Type.WORLD_SURFACE, cp.getStartX() + 8, cp.getStartZ() + 8);
                    checkAndFlag(cp, data);
                }
            }
        }
    }

    private void checkAndFlag(ChunkPos cp, ChunkData data) {
        if (!data.flagged && data.score >= flagThresh.getValue()) {
            data.flagged = true;
            if (chatNotify.getValue()) {
                mc.execute(() -> {
                    com.xenon.gui.notification.NotificationManager.INSTANCE.push(
                            "Amethyst Lode",
                            "X:" + (cp.x * 16) + " Z:" + (cp.z * 16) + " S:" + (int)data.score,
                            new net.minecraft.item.ItemStack(net.minecraft.item.Items.AMETHYST_SHARD),
                            0xFF9900FF
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
        Color fill    = new Color(225, 25, 25, 30);
        Color outline = new Color(225, 25, 25, 220);
        Color tracer  = new Color(225, 25, 25, 200);

        RenderUtils.WorldBatch batch = RenderUtils.beginWorldBatch(matrices);
        matrices.push();
        matrices.translate(-camPos.x, -camPos.y, -camPos.z);

        for (Map.Entry<ChunkPos, ChunkData> e : chunkData.entrySet()) {
            if (!e.getValue().flagged) continue;
            ChunkPos cp = e.getKey();
            double plateY = e.getValue().surfaceY + 0.05;
            double x0 = cp.getStartX(), z0 = cp.getStartZ();
            batch.renderFilledBox(x0, plateY - 0.05, z0, x0 + 16, plateY + 0.15, z0 + 16, fill);
            batch.renderOutlineBox(x0, plateY - 0.05, z0, x0 + 16, plateY + 0.15, z0 + 16, outline);
            if (showTracers.getValue())
                batch.renderLine(tracer, camPos, new Vec3d(x0 + 8, plateY, z0 + 8), 2.0f);
        }

        matrices.pop();
        batch.flush();
    }
}

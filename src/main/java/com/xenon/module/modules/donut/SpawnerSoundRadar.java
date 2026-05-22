package com.xenon.module.modules.donut;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.Setting;
import com.xenon.utils.RenderUtils;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.util.math.Vec3d;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class SpawnerSoundRadar extends Module {

    private final Setting<Integer> confirmHits   = new Setting<>("Confirm Hits", 2, 1, 20);
    private final Setting<Float>   clusterRadius = new Setting<>("Cluster Radius", 8.0f, 1.0f, 32.0f);
    private final Setting<Integer> decaySeconds  = new Setting<>("Decay Seconds", 600, 30, 1800);
    private final Setting<Boolean> chatNotify    = new Setting<>("Chat Notify", true);
    private final Setting<Boolean> broadMatch    = new Setting<>("Broad Match", true);
    private final Setting<Float>   maxRange      = new Setting<>("Max Range", 80.0f, 8.0f, 128.0f);
    private final Setting<Boolean> showTracers   = new Setting<>("Tracers", true);
    private final Setting<Integer> pillarHeight  = new Setting<>("Pillar Height", 160, 16, 320);

    private final List<Detection> detections = Collections.synchronizedList(new ArrayList<>());
    private int tickCounter = 0;

    public SpawnerSoundRadar() {
        super("Spawner Sound Radar", Category.DONUT);
        addSetting(confirmHits);
        addSetting(clusterRadius);
        addSetting(decaySeconds);
        addSetting(chatNotify);
        addSetting(broadMatch);
        addSetting(maxRange);
        addSetting(showTracers);
        addSetting(pillarHeight);
    }

    @Override
    public void onEnable()  { detections.clear(); tickCounter = 0; }
    @Override
    public void onDisable() { detections.clear(); }

    @Override
    public void onTick() {
        if (++tickCounter % 100 == 0) {
            long cutoff = System.currentTimeMillis() - (long) decaySeconds.getValue() * 1000L;
            synchronized (detections) {
                detections.removeIf(d -> d.lastMs < cutoff);
            }
        }
    }

    @Override
    public void onPacketReceive(Packet<?> packet) {
        if (!(packet instanceof PlaySoundS2CPacket pkt)) return;
        if (mc.player == null) return;

        String key = net.minecraft.registry.Registries.SOUND_EVENT.getId(pkt.getSound().value()).toString();
        boolean matched = broadMatch.getValue() ? key.contains("spawner") : key.equals("minecraft:entity.mob_spawner.ambient");
        if (!matched) return;

        double sx = pkt.getX();
        double sy = pkt.getY();
        double sz = pkt.getZ();
        Vec3d soundPos = new Vec3d(sx, sy, sz);
        double dist = mc.player.squaredDistanceTo(soundPos.x, soundPos.y, soundPos.z); dist = Math.sqrt(dist);
        if (dist > maxRange.getValue()) return;

        double radius = clusterRadius.getValue();
        synchronized (detections) {
            Detection nearest = null;
            double minD = Double.MAX_VALUE;
            for (Detection d : detections) {
                double dd = d.pos.distanceTo(soundPos);
                if (dd < radius && dd < minD) { minD = dd; nearest = d; }
            }
            if (nearest != null) {
                boolean was = nearest.confirmed;
                nearest.addHit();
                nearest.confirmed = nearest.hits >= confirmHits.getValue();
                if (!was && nearest.confirmed && chatNotify.getValue()) {
                    Vec3d p = nearest.pos;
                    mc.execute(() -> {
                        com.xenon.gui.notification.NotificationManager.INSTANCE.push(
                                "Spawner Radar",
                                "X:" + (int)p.x + " Y:" + (int)p.y + " Z:" + (int)p.z,
                                new net.minecraft.item.ItemStack(net.minecraft.item.Items.SPAWNER),
                                0xFFFF2222
                        );
                    });
                }
            } else {
                detections.add(new Detection(soundPos));
            }
        }
    }

    @Override
    public void onRender(MatrixStack matrices, float tickDelta) {
        if (mc.player == null || detections.isEmpty()) return;

        Camera cam = RenderUtils.getCamera();
        Vec3d camPos = RenderUtils.getCameraPos(cam);

        Color fillUnconf = new Color(225, 25, 25, 30);
        Color lineUnconf = new Color(225, 25, 25, 160);
        Color fillConf   = new Color(225, 25, 25, 60);
        Color lineConf   = new Color(225, 25, 25, 220);
        Color tracerConf = new Color(225, 25, 25, 180);
        Color tracerUnconf = new Color(225, 25, 25, 120);

        RenderUtils.WorldBatch batch = RenderUtils.beginWorldBatch(matrices);
        matrices.push();
        matrices.translate(-camPos.x, -camPos.y, -camPos.z);

        synchronized (detections) {
            for (Detection d : detections) {
                Color fill = d.confirmed ? fillConf : fillUnconf;
                Color line = d.confirmed ? lineConf : lineUnconf;
                Color tracer = d.confirmed ? tracerConf : tracerUnconf;
                double x0 = Math.floor(d.pos.x);
                double y0 = Math.floor(d.pos.y);
                double z0 = Math.floor(d.pos.z);
                batch.renderFilledBox(x0, y0, z0, x0 + 1, y0 + 1, z0 + 1, fill);
                batch.renderOutlineBox(x0, y0, z0, x0 + 1, y0 + 1, z0 + 1, line);
                if (d.confirmed) {
                    int h = pillarHeight.getValue();
                    batch.renderFilledBox(x0, y0 + 1, z0, x0 + 1, y0 + h, z0 + 1, fill);
                    batch.renderOutlineBox(x0, y0 + 1, z0, x0 + 1, y0 + h, z0 + 1, line);
                }
                if (showTracers.getValue()) {
                    batch.renderLine(tracer, camPos, new Vec3d(x0 + 0.5, y0 + 0.5, z0 + 0.5), 2.0f);
                }
            }
        }

        matrices.pop();
        batch.flush();
    }

    static class Detection {
        final Vec3d pos;
        int hits;
        long lastMs;
        boolean confirmed;
        Detection(Vec3d pos) { this.pos = pos; this.hits = 1; this.lastMs = System.currentTimeMillis(); }
        void addHit() { hits++; lastMs = System.currentTimeMillis(); }
    }
}


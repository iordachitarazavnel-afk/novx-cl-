package com.xenon.module.modules.render;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.module.modules.client.XenonPlus;
import com.xenon.setting.MobsSetting;
import com.xenon.setting.Setting;
import com.xenon.utils.RenderUtils;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class MobESP extends Module {

    private static final double TRACER_START_DISTANCE = 150.0D;
    private static final double TRACER_END_DISTANCE = 24.0D;
    private static final double TRACER_BEHIND_MIN_SPREAD = 2.75D;

    private final MobsSetting mobs = new MobsSetting("Mobs");
    private final Setting<Double> alpha = new Setting<>("Alpha", 100.0, 0.0, 255.0);
    private final Setting<Double> range = new Setting<>("Range", 128.0, 16.0, 512.0);
    private final Setting<Boolean> tracers = new Setting<>("Tracers", false);
    private final Setting<Color> outlineColor = new Setting<>("Outline color", new Color(255, 80, 80));
    private final Setting<Color> fillColor = new Setting<>("Fill color", new Color(255, 80, 80));
    private final Setting<Color> tracerColor = new Setting<>("Tracer color", new Color(255, 80, 80));

    public MobESP() {
        super("Mob ESP", Category.RENDER);
        addSetting(mobs);
        addSetting(alpha);
        addSetting(range);
        addSetting(tracers);
        addSetting(outlineColor);
        addSetting(fillColor);
        addSetting(tracerColor);
    }

    @Override
    public void onRender(MatrixStack matrices, float tickDelta) {
        if (mc.world == null || mc.player == null) return;
        Set<net.minecraft.entity.EntityType<?>> targets = mobs.getSelectedMobs();
        if (targets.isEmpty()) return;

        Camera cam = RenderUtils.getCamera();
        if (cam == null) return;

        Vec3d camPos = RenderUtils.getCameraPos(cam);
        double camX = camPos.x, camY = camPos.y, camZ = camPos.z;
        double maxRangeSq = range.getValue() * range.getValue();
        int alphaValue = clampAlpha(alpha.getValue());
        boolean renderTracers = tracers.getValue();
        Color boxColor = applyOpacity(outlineColor.getValue(), alphaValue);
        Color fill = applyOpacity(fillColor.getValue(), Math.max(0, alphaValue / 3));
        Color tracer = renderTracers ? applyOpacity(tracerColor.getValue(), 255) : null;

        Vec3d cameraForward = renderTracers ? RenderUtils.getCameraForward(cam) : null;
        Vec3d cameraRight = renderTracers ? RenderUtils.getCameraRight(cam) : null;
        Vec3d cameraUp = renderTracers ? RenderUtils.getCameraUp(cameraForward, cameraRight) : null;
        Vec3d tracerStart = renderTracers ? cameraForward.multiply(TRACER_START_DISTANCE) : null;

        List<RenderData> renderData = new ArrayList<>();

        for (Entity entity : mc.world.getEntities()) {
            if (entity == mc.player) continue;
            if (entity instanceof PlayerEntity) continue;
            if (!(entity instanceof LivingEntity) || !entity.isAlive()) continue;
            if (!targets.contains(entity.getType())) continue;

            Vec3d lerped = getLerpedPosCompat(entity, tickDelta);
            double dx = lerped.x - camX;
            double dy = lerped.y - camY;
            double dz = lerped.z - camZ;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > maxRangeSq) continue;

            double halfWidth = entity.getWidth() / 2.0D;
            double height = entity.getHeight();
            double tracerTargetY = dy + height * 0.5D;
            renderData.add(new RenderData(dx, dy, dz, tracerTargetY, halfWidth, height));
        }

        if (renderData.isEmpty()) return;

        matrices.push();
        RenderUtils.WorldBatch boxBatch = RenderUtils.beginWorldBatch(matrices);
        for (RenderData d : renderData) {
            boxBatch.renderOutlineBox(
                    d.dx - d.halfWidth, d.dy, d.dz - d.halfWidth,
                    d.dx + d.halfWidth, d.dy + d.height, d.dz + d.halfWidth,
                    boxColor);
            boxBatch.renderFilledBox(
                    d.dx - d.halfWidth, d.dy, d.dz - d.halfWidth,
                    d.dx + d.halfWidth, d.dy + d.height, d.dz + d.halfWidth,
                    fill);
        }
        boxBatch.flush();

        if (renderTracers) {
            RenderUtils.WorldBatch tb = RenderUtils.beginWorldBatch(matrices);
            for (RenderData d : renderData) {
                Vec3d tracerEnd = RenderUtils.getSpreadTracerEnd(
                        d.dx, d.tracerTargetY, d.dz,
                        cameraForward, cameraRight, cameraUp,
                        TRACER_END_DISTANCE, TRACER_BEHIND_MIN_SPREAD);
                tb.renderLine(tracer, tracerStart, tracerEnd, XenonPlus.tracerLineWidth());
            }
            tb.flush();
        }

        matrices.pop();
    }

    private int clampAlpha(double value) {
        int a = (int) Math.round(value);
        return Math.max(0, Math.min(255, a));
    }

    private Color applyOpacity(Color base, int alphaValue) {
        int combined = Math.max(0, Math.min(255, Math.round((base.getAlpha() / 255.0f) * alphaValue)));
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), combined);
    }

    private Vec3d getLerpedPosCompat(Entity e, float tickDelta) {
        try {
            return e.getLerpedPos(tickDelta);
        } catch (Throwable ignored) {
            double x = MathHelper.lerp(tickDelta, e.lastRenderX, e.getX());
            double y = MathHelper.lerp(tickDelta, e.lastRenderY, e.getY());
            double z = MathHelper.lerp(tickDelta, e.lastRenderZ, e.getZ());
            return new Vec3d(x, y, z);
        }
    }

    private static final class RenderData {
        final double dx, dy, dz, tracerTargetY, halfWidth, height;
        RenderData(double dx, double dy, double dz, double tracerTargetY, double halfWidth, double height) {
            this.dx = dx; this.dy = dy; this.dz = dz;
            this.tracerTargetY = tracerTargetY;
            this.halfWidth = halfWidth; this.height = height;
        }
    }
}

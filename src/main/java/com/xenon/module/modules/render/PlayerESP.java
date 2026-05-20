package com.xenon.module.modules.render;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.module.modules.client.Friends;
import com.xenon.module.modules.client.XenonPlus;
import com.xenon.setting.Setting;
import com.xenon.utils.RenderUtils;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

public final class PlayerESP extends Module {

    private static final double TRACER_START_DISTANCE = 150.0D;
    private static final double TRACER_END_DISTANCE = 24.0D;
    private static final double TRACER_BEHIND_MIN_SPREAD = 2.75D;

    private final Setting<Double> alpha = new Setting<>("Alpha", 100.0, 0.0, 255.0);
    private final Setting<Double> range = new Setting<>("Range", 256.0, 16.0, 512.0);
    private final Setting<Boolean> tracers = new Setting<>("Tracers", false);
    private final Setting<Color> outlineColor = new Setting<>("Outline color", new Color(255, 0, 0));
    private final Setting<Color> fillColor = new Setting<>("Fill color", new Color(255, 0, 0));
    private final Setting<Color> tracerColor = new Setting<>("Tracer color", new Color(255, 0, 0));

    public PlayerESP() {
        super("Player ESP", Category.RENDER);
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

        Camera cam = RenderUtils.getCamera();
        if (cam == null) return;

        Vec3d camPos = RenderUtils.getCameraPos(cam);
        double camX = camPos.x;
        double camY = camPos.y;
        double camZ = camPos.z;
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

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !player.isAlive() || player.isSpectator() || player.isInvisibleTo(mc.player)) {
                continue;
            }
            boolean friend = Friends.isEspColor() && Friends.isFriend(getFriendLookupName(player));

            Vec3d lerped = getLerpedPosCompat(player, tickDelta);
            double worldX = lerped.x;
            double worldY = lerped.y;
            double worldZ = lerped.z;
            double dx = worldX - camX;
            double dy = worldY - camY;
            double dz = worldZ - camZ;
            double distSq = (dx * dx) + (dy * dy) + (dz * dz);
            if (distSq > maxRangeSq) {
                continue;
            }

            Color curOutline;
            Color curFill;
            Color curTracer;
            if (friend) {
                Color fc = Friends.getColor();
                curOutline = applyOpacity(fc, alphaValue);
                curFill = applyOpacity(fc, Math.max(0, alphaValue / 3));
                curTracer = renderTracers ? applyOpacity(fc, 255) : null;
            } else {
                curOutline = boxColor;
                curFill = fill;
                curTracer = tracer;
            }

            // Dynamic hitbox based on current pose (crouch/swim/etc).
            double halfWidth = player.getWidth() / 2.0D;
            double height = player.getHeight();

            // Visibility culling: distance check above is sufficient; avoid aggressive AABB visibility culling.
            boolean boxVisible = true;
            double tracerTargetY = dy + (player.getHeight() * 0.5D);
            renderData.add(new RenderData(dx, dy, dz, tracerTargetY, halfWidth, height, curOutline, curFill, curTracer, boxVisible));
        }

        if (renderData.isEmpty()) {
            return;
        }

        matrices.push();
        RenderUtils.WorldBatch boxBatch = RenderUtils.beginWorldBatch(matrices);
        for (RenderData data : renderData) {
            if (!data.boxVisible) {
                continue;
            }
            boxBatch.renderOutlineBox(
                    data.dx - data.halfWidth,
                    data.dy,
                    data.dz - data.halfWidth,
                    data.dx + data.halfWidth,
                    data.dy + data.height,
                    data.dz + data.halfWidth,
                    data.outline
            );
            boxBatch.renderFilledBox(
                    data.dx - data.halfWidth,
                    data.dy,
                    data.dz - data.halfWidth,
                    data.dx + data.halfWidth,
                    data.dy + data.height,
                    data.dz + data.halfWidth,
                    data.fill
            );
        }
        boxBatch.flush();

        if (renderTracers) {
            RenderUtils.WorldBatch tracerBatch = RenderUtils.beginWorldBatch(matrices);
            for (RenderData data : renderData) {
                if (data.tracer == null) {
                    continue;
                }
                Vec3d tracerEnd = RenderUtils.getSpreadTracerEnd(
                        data.dx,
                        data.tracerTargetY,
                        data.dz,
                        cameraForward,
                        cameraRight,
                        cameraUp,
                        TRACER_END_DISTANCE,
                        TRACER_BEHIND_MIN_SPREAD
                );
                tracerBatch.renderLine(data.tracer, tracerStart, tracerEnd, XenonPlus.tracerLineWidth());
            }
            tracerBatch.flush();
        }

        matrices.pop();
    }

    private int clampAlpha(double value) {
        int alphaValue = (int) Math.round(value);
        if (alphaValue < 0) return 0;
        if (alphaValue > 255) return 255;
        return alphaValue;
    }

    private Color applyOpacity(Color base, int alphaValue) {
        int combinedAlpha = Math.max(0, Math.min(255, Math.round((base.getAlpha() / 255.0f) * alphaValue)));
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), combinedAlpha);
    }

    private Vec3d getLerpedPosCompat(PlayerEntity player, float tickDelta) {
        try {
            // Prefer native interpolation if present in this MC version.
            return player.getLerpedPos(tickDelta);
        } catch (Throwable ignored) {
            double worldX = MathHelper.lerp(tickDelta, player.lastRenderX, player.getX());
            double worldY = MathHelper.lerp(tickDelta, player.lastRenderY, player.getY());
            double worldZ = MathHelper.lerp(tickDelta, player.lastRenderZ, player.getZ());
            return new Vec3d(worldX, worldY, worldZ);
        }
    }

    private String getFriendLookupName(PlayerEntity player) {
        if (player == null) {
            return "";
        }
        try {
            Object profile = player.getGameProfile();
            if (profile != null) {
                // Different mappings/MC versions can expose the profile name differently.
                try {
                    Object v = profile.getClass().getMethod("getName").invoke(profile);
                    if (v instanceof String s && !s.isBlank()) return s;
                } catch (Throwable ignored) {}
                try {
                    Object v = profile.getClass().getMethod("name").invoke(profile);
                    if (v instanceof String s && !s.isBlank()) return s;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return player.getName().getString();
    }

    private static final class RenderData {
        final double dx;
        final double dy;
        final double dz;
        final double tracerTargetY;
        final double halfWidth;
        final double height;
        final Color outline;
        final Color fill;
        final Color tracer;
        final boolean boxVisible;

        private RenderData(double dx, double dy, double dz, double tracerTargetY, double halfWidth, double height, Color outline, Color fill, Color tracer, boolean boxVisible) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.tracerTargetY = tracerTargetY;
            this.halfWidth = halfWidth;
            this.height = height;
            this.outline = outline;
            this.fill = fill;
            this.tracer = tracer;
            this.boxVisible = boxVisible;
        }
    }
}

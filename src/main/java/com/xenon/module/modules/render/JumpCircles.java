package com.xenon.module.modules.render;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.Setting;
import com.xenon.utils.RenderUtils;

import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class JumpCircles extends Module {

    private static final int SEGMENTS = 128;

    private final Setting<Float>   lifetime    = new Setting<>("Lifetime (s)", 1.5f, 0.1f, 5.0f);
    private final Setting<Float>   startRadius = new Setting<>("Start Radius", 0.55f, 0.1f, 3.0f);
    private final Setting<Float>   endRadius   = new Setting<>("End Radius",   1.80f, 0.2f, 6.0f);
    private final Setting<Float>   lineWidth   = new Setting<>("Line Width",   2.0f, 0.5f, 6.0f);
    private final Setting<Color>   color       = new Setting<>("Color",        new Color(120, 220, 255, 255));
    private final Setting<Boolean> glowMode    = new Setting<>("Glow Mode",    false);
    private final Setting<Boolean> glowFilled  = new Setting<>("Glow Filled",  false);

    private final List<Circle> circles = new ArrayList<>();
    private boolean wasOnGround = true;
    private double lastGroundX = 0.0, lastGroundY = 0.0, lastGroundZ = 0.0;

    public JumpCircles() {
        super("JumpCircles", Category.RENDER);
        addSetting(lifetime);
        addSetting(startRadius);
        addSetting(endRadius);
        addSetting(lineWidth);
        addSetting(color);
        addSetting(glowMode);
        addSetting(glowFilled);
    }

    @Override
    public void onEnable() {
        circles.clear();
        wasOnGround = true;
    }

    @Override
    public void onDisable() {
        circles.clear();
    }

    @Override
    public void onTick() {
        if (mc.player == null) return;

        boolean onGround = mc.player.isOnGround();

        // Capture the ground position every tick the player is grounded.
        // When the jump fires, player.getY() has already moved up by the jump velocity,
        // so we use the *previous* on-ground position to lock the circle to the floor.
        if (onGround) {
            lastGroundX = mc.player.getX();
            lastGroundY = mc.player.getY();
            lastGroundZ = mc.player.getZ();
        }

        if (wasOnGround && !onGround && mc.player.getVelocity().y > 0.0) {
            circles.add(new Circle(lastGroundX, lastGroundY + 0.02, lastGroundZ, System.currentTimeMillis()));
        }
        wasOnGround = onGround;

        
        long now = System.currentTimeMillis();
        long lifeMs = (long) (lifetime.getValue() * 1000f);
        Iterator<Circle> it = circles.iterator();
        while (it.hasNext()) {
            if (now - it.next().bornMs >= lifeMs) it.remove();
        }
    }

    @Override
    public void onRender(MatrixStack matrices, float tickDelta) {
        if (mc.world == null || mc.player == null || circles.isEmpty()) return;

        Camera cam = RenderUtils.getCamera();
        if (cam == null) return;
        Vec3d camPos = RenderUtils.getCameraPos(cam);

        long now = System.currentTimeMillis();
        long lifeMs = (long) (lifetime.getValue() * 1000f);
        double sR = startRadius.getValue();
        double eR = endRadius.getValue();
        float lw = lineWidth.getValue();
        Color base = color.getValue();
        int baseAlpha = base.getAlpha();

        matrices.push();
        RenderUtils.WorldBatch batch = RenderUtils.beginWorldBatch(matrices);

        for (Circle c : circles) {
            float age = (now - c.bornMs) / (float) lifeMs;
            if (age < 0f) age = 0f;
            if (age > 1f) age = 1f;

            
            float ease = 1f - (1f - age) * (1f - age) * (1f - age);
            double radius = sR + (eR - sR) * ease;
            int alpha = (int) (baseAlpha * (1f - ease));
            if (alpha <= 0) continue;

            Color col = new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);

            double cx = c.x - camPos.x;
            double cy = c.y - camPos.y;
            double cz = c.z - camPos.z;

            // Pre-compute the ring points once and reuse for each glow pass.
            Vec3d[] points = new Vec3d[SEGMENTS + 1];
            for (int i = 0; i <= SEGMENTS; i++) {
                double ang = (Math.PI * 2.0) * (i / (double) SEGMENTS);
                points[i] = new Vec3d(cx + Math.cos(ang) * radius, cy, cz + Math.sin(ang) * radius);
            }

            if (glowFilled.getValue()) {
                int r0 = base.getRed(), g0 = base.getGreen(), b0 = base.getBlue();
                int rings = 32;
                float maxLw = lw * 8.0f;
                for (int k = 1; k <= rings; k++) {
                    double t = k / (double) rings; // 0..1, outer to center
                    double innerRadius = radius * (1.0 - t);
                    if (innerRadius < 0.05) continue;
                    int aFill = Math.max(1, (int)(alpha * (1.0 - t * 0.6) * 0.55));
                    Color fillCol = new Color(r0, g0, b0, aFill);
                    Vec3d[] ring = new Vec3d[SEGMENTS + 1];
                    for (int i = 0; i <= SEGMENTS; i++) {
                        double ang = (Math.PI * 2.0) * (i / (double) SEGMENTS);
                        ring[i] = new Vec3d(cx + Math.cos(ang) * innerRadius, cy, cz + Math.sin(ang) * innerRadius);
                    }
                    drawRing(batch, ring, fillCol, maxLw);
                }
            }

            if (glowMode.getValue()) {
                int r = base.getRed(), g = base.getGreen(), b = base.getBlue();
                Color h1 = new Color(r, g, b, Math.max(1, alpha / 16));
                Color h2 = new Color(r, g, b, Math.max(1, alpha / 12));
                Color h3 = new Color(r, g, b, Math.max(1, alpha / 9));
                Color h4 = new Color(r, g, b, Math.max(1, alpha / 6));
                Color h5 = new Color(r, g, b, Math.max(1, alpha / 4));
                Color h6 = new Color(r, g, b, Math.max(1, alpha / 2));
                Color core1 = new Color(r, g, b, Math.min(255, (int)(alpha * 1.0f)));
                Color core2 = new Color(255, 255, 255, Math.min(255, (int)(alpha * 1.4f)));
                drawRing(batch, points, h1, lw * 18.0f);
                drawRing(batch, points, h2, lw * 14.0f);
                drawRing(batch, points, h3, lw * 11.0f);
                drawRing(batch, points, h4, lw * 8.5f);
                drawRing(batch, points, h5, lw * 6.0f);
                drawRing(batch, points, h6, lw * 4.0f);
                drawRing(batch, points, core1, lw * 2.8f);
                drawRing(batch, points, core2, lw * 1.6f);
            } else {
                drawRing(batch, points, col, lw);
            }
        }

        batch.flush();
        matrices.pop();
    }

    private static void drawRing(RenderUtils.WorldBatch batch, Vec3d[] points, Color col, float width) {
        for (int i = 1; i < points.length; i++) {
            batch.renderLine(col, points[i - 1], points[i], width);
        }
    }

    private static final class Circle {
        final double x, y, z;
        final long bornMs;
        Circle(double x, double y, double z, long bornMs) {
            this.x = x; this.y = y; this.z = z; this.bornMs = bornMs;
        }
    }
}

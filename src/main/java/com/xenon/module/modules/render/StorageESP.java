package com.xenon.module.modules.render;

import net.minecraft.block.entity.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.module.modules.client.XenonPlus;
import com.xenon.setting.Setting;

import com.xenon.utils.RenderUtils;

import java.awt.Color;
import net.minecraft.client.render.*;

public final class StorageESP extends Module {

    private static final double TRACER_START_DISTANCE = 150.0D;
    private static final double TRACER_END_DISTANCE = 24.0D;
    private static final double TRACER_BEHIND_MIN_SPREAD = 2.75D;
    private static final float TRACER_LINE_WIDTH = 1.0f;

    private final Setting<Double> alpha = new Setting<>("Alpha", 125.0, 0.0, 255.0);
    private final Setting<Boolean> tracers = new Setting<>("Tracers", true);

    private final Setting<Boolean> chests = new Setting<>("Chests", true);
    private final Setting<Boolean> enderChests = new Setting<>("Ender chests", true);
    private final Setting<Boolean> spawners = new Setting<>("Spawners", true);
    private final Setting<Boolean> shulkerBoxes = new Setting<>("Shulker boxes", true);
    private final Setting<Boolean> furnaces = new Setting<>("Furnaces", true);
    private final Setting<Boolean> barrels = new Setting<>("Barrels", true);
    private final Setting<Boolean> enchant = new Setting<>("Enchanting tables", true);
    private final Setting<Boolean> pistons = new Setting<>("Pistons", true);
    private final Setting<Boolean> hoppers = new Setting<>("Hoppers", false);

    private final Setting<Color> chestColor = new Setting<>("Chest color", new Color(156, 91, 0));
    private final Setting<Color> trappedColor = new Setting<>("Trapped chest", new Color(200, 91, 0));
    private final Setting<Color> enderColor = new Setting<>("Ender chest", new Color(117, 0, 255));
    private final Setting<Color> spawnerColor = new Setting<>("Spawner", new Color(138, 126, 166));
    private final Setting<Color> shulkerColor = new Setting<>("Shulker", new Color(134, 0, 158));
    private final Setting<Color> furnaceColor = new Setting<>("Furnace", new Color(125, 125, 125));
    private final Setting<Color> barrelColor = new Setting<>("Barrel", new Color(255, 140, 140));
    private final Setting<Color> enchantColor = new Setting<>("Enchant", new Color(80, 80, 255));
    private final Setting<Color> pistonColor = new Setting<>("Piston", new Color(35, 226, 0));
    private final Setting<Color> hopperColor = new Setting<>("Hopper", new Color(100, 200, 255));

    public StorageESP() {
        super("Storage ESP", Category.RENDER);
        addSetting(alpha);
        addSetting(tracers);
        addSetting(chests);
        addSetting(enderChests);
        addSetting(spawners);
        addSetting(shulkerBoxes);
        addSetting(furnaces);
        addSetting(barrels);
        addSetting(enchant);
        addSetting(pistons);
        addSetting(hoppers);
        addSetting(chestColor);
        addSetting(trappedColor);
        addSetting(enderColor);
        addSetting(spawnerColor);
        addSetting(shulkerColor);
        addSetting(furnaceColor);
        addSetting(barrelColor);
        addSetting(enchantColor);
        addSetting(pistonColor);
        addSetting(hopperColor);
    }

    @Override
    public void onRender(net.minecraft.client.util.math.MatrixStack matrices, float tickDelta) {
        if (mc.world == null || mc.player == null) return;

        Camera cam = RenderUtils.getCamera();
        if (cam == null) return;

        Vec3d camPos = RenderUtils.getCameraPos(cam);
        Vec3d cameraForward = RenderUtils.getCameraForward(cam);
        Vec3d cameraRight = RenderUtils.getCameraRight(cam);
        Vec3d cameraUp = RenderUtils.getCameraUp(cameraForward, cameraRight);
        Vec3d tracerStart = cameraForward.multiply(TRACER_START_DISTANCE);
        matrices.push();

        java.util.List<RenderData> boxesToRender = new java.util.ArrayList<>();

        int viewDist = mc.options.getClampedViewDistance();
        net.minecraft.util.math.ChunkPos center = mc.player.getChunkPos();

        for (int cx = -viewDist; cx <= viewDist; cx++) {
            for (int cz = -viewDist; cz <= viewDist; cz++) {
                net.minecraft.world.chunk.WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(center.x + cx, center.z + cz, false);
                if (chunk == null) continue;

                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    Color color = getBlockEntityColor(blockEntity, clampAlpha(alpha.getValue()));
                    if (color.getAlpha() == 0) continue;

                    BlockPos blockPos = blockEntity.getPos();
                    double dx = blockPos.getX() - camPos.x;
                    double dy = blockPos.getY() - camPos.y;
                    double dz = blockPos.getZ() - camPos.z;

                    boxesToRender.add(new RenderData(dx, dy, dz, color));
                }
            }
        }

        if (boxesToRender.isEmpty()) {
            matrices.pop();
            return;
        }

        for (RenderData data : boxesToRender) {
            Color solidColor = data.color;
            RenderUtils.renderOutlineBox(matrices, data.dx + 0.0625, data.dy, data.dz + 0.0625, data.dx + 0.9375, data.dy + 0.875, data.dz + 0.9375, solidColor);
            RenderUtils.renderFilledBox(matrices, data.dx + 0.0625, data.dy, data.dz + 0.0625, data.dx + 0.9375, data.dy + 0.875, data.dz + 0.9375, data.color);
        }

        //tracer or something idk
        if (tracers.getValue()) {
            for (RenderData data : boxesToRender) {
                Vec3d relativeTarget = new Vec3d(data.dx + 0.5D, data.dy + 0.5D, data.dz + 0.5D);
                Vec3d tracerEnd = RenderUtils.getSpreadTracerEnd(
                        relativeTarget,
                        cameraForward,
                        cameraRight,
                        cameraUp,
                        TRACER_END_DISTANCE,
                        TRACER_BEHIND_MIN_SPREAD
                );
                RenderUtils.renderLine(matrices, data.color, tracerStart, tracerEnd, XenonPlus.tracerLineWidth());
            }
        }

        matrices.pop();
    }

    private Color getBlockEntityColor(final BlockEntity blockEntity, final int a) {
        Color c = null;
        if (blockEntity instanceof TrappedChestBlockEntity && this.chests.getValue()) c = trappedColor.getValue();
        else if (blockEntity instanceof ChestBlockEntity && this.chests.getValue()) c = chestColor.getValue();
        else if (blockEntity instanceof EnderChestBlockEntity && this.enderChests.getValue()) c = enderColor.getValue();
        else if (blockEntity instanceof MobSpawnerBlockEntity && this.spawners.getValue()) c = spawnerColor.getValue();
        else if (blockEntity instanceof ShulkerBoxBlockEntity && this.shulkerBoxes.getValue()) c = shulkerColor.getValue();
        else if ((blockEntity instanceof FurnaceBlockEntity || blockEntity instanceof BlastFurnaceBlockEntity || blockEntity instanceof SmokerBlockEntity) && this.furnaces.getValue()) c = furnaceColor.getValue();
        else if (blockEntity instanceof BarrelBlockEntity && this.barrels.getValue()) c = barrelColor.getValue();
        else if (blockEntity instanceof EnchantingTableBlockEntity && this.enchant.getValue()) c = enchantColor.getValue();
        else if (blockEntity instanceof PistonBlockEntity && this.pistons.getValue()) c = pistonColor.getValue();
        else if (blockEntity instanceof HopperBlockEntity && this.hoppers.getValue()) c = hopperColor.getValue();

        if (c != null) return applyOpacity(c, a);
        return new Color(255, 255, 255, 0);
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

    private static class RenderData {
        double dx, dy, dz;
        Color color;

        RenderData(double dx, double dy, double dz, Color color) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.color = color;
        }
    }
}

package com.xenon.module.modules.render;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.Setting;
import com.xenon.utils.RenderUtils;
import net.minecraft.block.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.client.render.Camera;

import java.awt.*;
import java.util.HashSet;
import java.util.Set;

public class ActiveRedstone extends Module {

    private final Setting<Integer> range      = new Setting<>("Range", 8, 1, 32);
    private final Setting<Boolean> showFill   = new Setting<>("Fill", true);

    private final Set<BlockPos> activeBlocks = new HashSet<>();
    private long lastScan = 0L;

    public ActiveRedstone() {
        super("Active Redstone", Category.RENDER);
        addSetting(range);
        addSetting(showFill);
    }

    @Override
    public void onEnable()  { activeBlocks.clear(); lastScan = 0L; }
    @Override
    public void onDisable() { activeBlocks.clear(); }

    @Override
    public void onTick() {
        if (mc.world == null || mc.player == null) return;
        long now = System.currentTimeMillis();
        if (now - lastScan < 100L) return;
        lastScan = now;

        activeBlocks.clear();
        BlockPos center = mc.player.getBlockPos();
        int r = range.getValue();

        for (int x = center.getX() - r; x <= center.getX() + r; x++) {
            for (int y = center.getY() - r; y <= center.getY() + r; y++) {
                for (int z = center.getZ() - r; z <= center.getZ() + r; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (isActive(mc.world.getBlockState(pos))) {
                        activeBlocks.add(pos.toImmutable());
                    }
                }
            }
        }
    }

    private boolean isActive(net.minecraft.block.BlockState state) {
        Block b = state.getBlock();
        if (b instanceof RedstoneWireBlock)
            return state.contains(Properties.POWER) && state.get(Properties.POWER) > 0;
        if (b instanceof AbstractRedstoneGateBlock)
            return state.contains(Properties.POWERED) && state.get(Properties.POWERED);
        if (b instanceof LeverBlock)
            return state.contains(Properties.POWERED) && state.get(Properties.POWERED);
        if (b instanceof ButtonBlock)
            return state.contains(Properties.POWERED) && state.get(Properties.POWERED);
        if (b instanceof ObserverBlock)
            return state.contains(Properties.POWERED) && state.get(Properties.POWERED);
        if (b instanceof RedstoneLampBlock)
            return state.contains(Properties.LIT) && state.get(Properties.LIT);
        if (b instanceof PistonBlock)
            return state.contains(Properties.EXTENDED) && state.get(Properties.EXTENDED);
        return false;
    }

    @Override
    public void onRender(MatrixStack matrices, float tickDelta) {
        if (mc.world == null || mc.player == null || activeBlocks.isEmpty()) return;

        Camera cam = RenderUtils.getCamera();
        Vec3d camPos = RenderUtils.getCameraPos(cam);
        Color lineCol = new Color(255, 50, 50, 220);
        Color fillCol = new Color(255, 50, 50, 40);

        RenderUtils.WorldBatch batch = RenderUtils.beginWorldBatch(matrices);
        matrices.push();
        matrices.translate(-camPos.x, -camPos.y, -camPos.z);

        for (BlockPos pos : activeBlocks) {
            if (showFill.getValue())
                batch.renderFilledBox(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1, fillCol);
            batch.renderOutlineBox(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1, lineCol);
        }

        matrices.pop();
        batch.flush();
    }
}

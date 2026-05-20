package com.xenon.module.modules.combat;

import com.xenon.module.Category;
import com.xenon.module.ActivatableModule;
import com.xenon.setting.Setting;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public final class AutoCrystal extends ActivatableModule {
    private static final double RANGE = 5.0;

    private final Setting<Float> placeDelay = new Setting<>("Place Delay", 0.0f, 0.0f, 20.0f);
    private final Setting<Float> breakDelay = new Setting<>("Break Delay", 0.0f, 0.0f, 20.0f);

    private int placeDelayCounter;
    private int breakDelayCounter;

    public AutoCrystal() {
        super("Auto Crystal", Category.COMBAT);
        addSetting(placeDelay);
        addSetting(breakDelay);
    }

    @Override
    public void onEnable() {
        resetCounters();
        super.onEnable();
    }

    @Override
    public void onTick() {
        if (mc.currentScreen != null) return;
        if (mc.player == null || mc.world == null) return;

        updateCounters();

        
        if (!isRightClickHeld()) {
            resetCounters();
            return;
        }

        
        Entity nearestCrystal = null;
        double minDistance = Double.MAX_VALUE;

        for (Entity e : mc.world.getEntities()) {
            if (e instanceof EndCrystalEntity) {
                double d = mc.player.distanceTo(e);
                if (d <= RANGE && d < minDistance) {
                    minDistance = d;
                    nearestCrystal = e;
                }
            }
        }

        if (nearestCrystal != null && breakDelayCounter == 0) {
            mc.interactionManager.attackEntity(mc.player, nearestCrystal);
            mc.player.swingHand(Hand.MAIN_HAND);
            breakDelayCounter = Math.max(0, breakDelay.getValue().intValue());
        }

        
        if (!(mc.crosshairTarget instanceof BlockHitResult blockHitResult)) return;
        if (blockHitResult.getType() != HitResult.Type.BLOCK) return;

        BlockPos pos = blockHitResult.getBlockPos();
        if (mc.player.squaredDistanceTo(pos.toCenterPos()) > RANGE * RANGE) return;

        
        
        
        if (!isValidCrystalPlacement(pos)) return;

        
        mc.options.useKey.setPressed(false);

        int crystalSlot = findHotbarSlot(Items.END_CRYSTAL);
        if (crystalSlot == -1) return;

        
        if (mc.player.getMainHandStack().getItem() != Items.END_CRYSTAL) {
            mc.player.getInventory().setSelectedSlot(crystalSlot);
        }

        if (placeDelayCounter == 0) {
            interactWithBlock(blockHitResult);
            placeDelayCounter = Math.max(0, placeDelay.getValue().intValue());
        }
    }

    private boolean isRightClickHeld() {
        return mc.getWindow() != null
                && GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
    }

    private void resetCounters() {
        placeDelayCounter = 0;
        breakDelayCounter = 0;
    }

    private void updateCounters() {
        if (placeDelayCounter > 0) placeDelayCounter--;
        if (breakDelayCounter > 0) breakDelayCounter--;
    }

    private int findHotbarSlot(net.minecraft.item.Item item) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    private boolean isValidCrystalPlacement(BlockPos blockPos) {
        if (!mc.world.getBlockState(blockPos).isOf(Blocks.OBSIDIAN)
                && !mc.world.getBlockState(blockPos).isOf(Blocks.BEDROCK)) {
            return false;
        }

        BlockPos up = blockPos.up();
        if (!mc.world.isAir(up)) return false;

        int getX = up.getX();
        int getY = up.getY();
        int compareTo = up.getZ();

        Box box = new Box(getX, getY, compareTo, getX + 1.0, getY + 2.0, compareTo + 1.0);
        return mc.world.getOtherEntities(null, box).isEmpty();
    }

    private void interactWithBlock(BlockHitResult hit) {
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
    }
}

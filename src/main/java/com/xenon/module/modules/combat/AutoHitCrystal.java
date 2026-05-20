package com.xenon.module.modules.combat;

import com.xenon.module.ActivatableModule;
import com.xenon.module.Category;
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
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public final class AutoHitCrystal extends ActivatableModule {

    private final Setting<Integer> delay = new Setting<>("Delay (ticks)", 1, 0, 10);

    private int cooldown = 0;

    public AutoHitCrystal() {
        super("AutoHitCrystal", Category.COMBAT);
        addSetting(delay);
    }

    @Override
    public void onEnable() {
        cooldown = 0;
    }

    @Override
    public void onActivationKeyPressed() {
        // Activation key is "hold to activate" — don't toggle the module
    }

    private boolean isActivationHeld() {
        int key = getActivationKey();
        if (key == 0) return true; // no activation bind set → always active while module enabled
        if (mc.getWindow() == null) return false;
        try {
            return org.lwjgl.glfw.GLFW.glfwGetKey(mc.getWindow().getHandle(), key) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onTick() {
        if (cooldown > 0) { cooldown--; return; }
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (mc.currentScreen != null) return;
        if (!isActivationHeld()) return;
        if (mc.crosshairTarget == null || mc.crosshairTarget.getType() != HitResult.Type.BLOCK) return;

        BlockHitResult lookHit = (BlockHitResult) mc.crosshairTarget;
        BlockPos surface = lookHit.getBlockPos();
        BlockPos obsidianPos = surface.up();
        BlockPos crystalPos = obsidianPos.up();

        // Step 5: destroy any existing crystal on top of the obsidian
        EndCrystalEntity existing = findCrystalAt(crystalPos);
        if (existing != null) {
            mc.interactionManager.attackEntity(mc.player, existing);
            mc.player.swingHand(Hand.MAIN_HAND);
            cooldown = delay.getValue();
            return;
        }

        // Step 1+2: switch + place obsidian (only if it isn't there yet)
        if (!isObsidianLike(obsidianPos)) {
            int obsSlot = findHotbarSlot(Items.OBSIDIAN);
            if (obsSlot < 0) return;
            if (mc.player.getInventory().getSelectedSlot() != obsSlot) {
                mc.player.getInventory().setSelectedSlot(obsSlot);
            }
            BlockHitResult placeObs = new BlockHitResult(
                    Vec3d.ofCenter(surface).add(0, 0.5, 0),
                    Direction.UP, surface, false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, placeObs);
            mc.player.swingHand(Hand.MAIN_HAND);
            cooldown = delay.getValue();
            return;
        }

        // Step 3+4: switch + place crystal on top of the obsidian
        if (!canPlaceCrystal(crystalPos)) return;
        int crystalSlot = findHotbarSlot(Items.END_CRYSTAL);
        if (crystalSlot < 0) return;
        if (mc.player.getInventory().getSelectedSlot() != crystalSlot) {
            mc.player.getInventory().setSelectedSlot(crystalSlot);
        }
        BlockHitResult placeCrystal = new BlockHitResult(
                Vec3d.ofCenter(obsidianPos).add(0, 0.5, 0),
                Direction.UP, obsidianPos, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, placeCrystal);
        mc.player.swingHand(Hand.MAIN_HAND);
        cooldown = delay.getValue();
    }

    private boolean isObsidianLike(BlockPos pos) {
        var s = mc.world.getBlockState(pos);
        return s.isOf(Blocks.OBSIDIAN) || s.isOf(Blocks.BEDROCK);
    }

    private boolean canPlaceCrystal(BlockPos crystalPos) {
        if (!mc.world.isAir(crystalPos)) return false;
        Box box = new Box(crystalPos.getX(), crystalPos.getY(), crystalPos.getZ(),
                crystalPos.getX() + 1.0, crystalPos.getY() + 2.0, crystalPos.getZ() + 1.0);
        return mc.world.getOtherEntities(null, box).isEmpty();
    }

    private EndCrystalEntity findCrystalAt(BlockPos pos) {
        Box box = new Box(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0, pos.getY() + 2.0, pos.getZ() + 1.0);
        for (Entity e : mc.world.getOtherEntities(null, box)) {
            if (e instanceof EndCrystalEntity crystal && crystal.isAlive()) return crystal;
        }
        return null;
    }

    private int findHotbarSlot(net.minecraft.item.Item item) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(item)) return i;
        }
        return -1;
    }
}

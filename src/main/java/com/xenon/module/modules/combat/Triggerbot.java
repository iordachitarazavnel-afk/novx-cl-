package com.xenon.module.modules.combat;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.module.modules.client.Friends;
import com.xenon.setting.Setting;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.entity.player.PlayerEntity;

public final class Triggerbot extends Module {
    private static final int FIXED_DELAY_TICKS = 9;

    private final Setting<Boolean> onlyCrit = new Setting<>("Only Crit", false);
    private final Setting<Boolean> checkShield = new Setting<>("Check Shield", false);

    private int delayCounter = 0;

    public Triggerbot() {
        super("Triggerbot", Category.COMBAT);
        addSetting(onlyCrit);
        addSetting(checkShield);
    }

    @Override
    public void onEnable() {
        delayCounter = 0;
    }

    @Override
    public void onDisable() {
        delayCounter = 0;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) return;
        if (mc.currentScreen != null) return;

        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        if (mc.crosshairTarget == null || mc.crosshairTarget.getType() != HitResult.Type.ENTITY) return;
        if (!(mc.crosshairTarget instanceof EntityHitResult entityHitResult)) return;

        Entity entity = entityHitResult.getEntity();
        if (!(entity instanceof LivingEntity)) return;
        if (entity == mc.player) return;
        if (entity instanceof PlayerEntity p
                && Friends.isAntiTriggerbot()
                && Friends.isFriend(p.getName().getString())) {
            return;
        }

        
        if (mc.options.attackKey.isPressed()) return;

        if (!passesFilters((LivingEntity) entity)) return;

        performAttack(entity);
        delayCounter = FIXED_DELAY_TICKS;
    }

    private boolean passesFilters(LivingEntity entity) {
        if (onlyCrit.getValue() && !isValidCrit(mc.player)) return false;

        if (checkShield.getValue()) {
            if (isHoldingShield(entity)) return false;
        }

        return true;
    }

    private boolean isHoldingShield(LivingEntity entity) {
        ItemStack main = entity.getMainHandStack();
        ItemStack off = entity.getOffHandStack();
        return main.getItem() == Items.SHIELD || off.getItem() == Items.SHIELD;
    }

    
    private boolean isValidCrit(PlayerEntity p) {
        if (p.fallDistance <= 0.05f) return false;
        if (p.isOnGround()) return false;
        if (p.isTouchingWater() || p.isInLava() || p.isClimbing() || p.hasVehicle()) return false;
        return !p.isSprinting();
    }

    private void performAttack(Entity target) {
        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);
    }
}


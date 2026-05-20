package com.xenon.module.modules.combat;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.Setting;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;




public final class ShieldBreaker extends Module {

    private final Setting<Boolean> switchBack = new Setting<>("Switch Back", true);
    private final Setting<Float> switchDelayMs = new Setting<>("Switch Delay", 0.0f, 0.0f, 500.0f);

    
    private boolean isBlockingState = false;
    private long firstDetectedTime = -1L;

    
    private boolean hasAttacked = false;
    private boolean needsSwitchBack = false;
    private int previousSlot = -1;

    public ShieldBreaker() {
        super("Shield Breaker", Category.COMBAT);
        addSetting(switchBack);
        addSetting(switchDelayMs);
    }

    @Override
    public void onEnable() {
        resetState();
        super.onEnable();
    }

    @Override
    public void onDisable() {
        if (hasAttacked && switchBack.getValue() && needsSwitchBack && mc.player != null) {
            swapToSlot(previousSlot);
        }
        resetState();
        super.onDisable();
    }

    private void resetState() {
        isBlockingState = false;
        firstDetectedTime = -1L;
        hasAttacked = false;
        needsSwitchBack = false;
        previousSlot = -1;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) return;

        final PlayerEntity target = getTargetPlayer();

        
        if (target != null && target.isBlocking()) {
            if (!isBlockingState) {
                
                isBlockingState = true;
                firstDetectedTime = System.currentTimeMillis();
            }

            
            long delay = switchDelayMs.getValue().longValue();
            if (!hasAttacked && firstDetectedTime >= 0 && System.currentTimeMillis() - firstDetectedTime >= delay) {
                final int bestAxeSlot = findAxeSlot();
                if (bestAxeSlot != -1) {
                        previousSlot = mc.player.getInventory().getSelectedSlot();
                    if (previousSlot != bestAxeSlot) {
                        swapToSlot(bestAxeSlot);
                        needsSwitchBack = true;
                    }

                    
                    mc.interactionManager.attackEntity(mc.player, target);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    hasAttacked = true;
                }
            }
        } else {
            
            if (isBlockingState) {
                isBlockingState = false;
                firstDetectedTime = -1L;
            }

            if (hasAttacked) {
                if (switchBack.getValue() && needsSwitchBack && previousSlot != -1) {
                    swapToSlot(previousSlot);
                }
                hasAttacked = false;
                needsSwitchBack = false;
                previousSlot = -1;
            }
        }
    }

    private PlayerEntity getTargetPlayer() {
        if (mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.ENTITY) {
            final Entity entity = ((EntityHitResult) mc.crosshairTarget).getEntity();
            if (entity instanceof PlayerEntity player && player != mc.player) {
                return player;
            }
        }
        return null;
    }

    private int findAxeSlot() {
        int bestScore = -1;
        int bestSlot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() instanceof AxeItem) {
                int score = getAxeStrengthScore(stack);
                if (score > bestScore) {
                    bestScore = score;
                    bestSlot = i;
                }
            }
        }
        return bestSlot;
    }

    private int getAxeStrengthScore(ItemStack stack) {
        
        if (stack.isOf(Items.NETHERITE_AXE)) return 6;
        if (stack.isOf(Items.DIAMOND_AXE)) return 5;
        if (stack.isOf(Items.IRON_AXE)) return 4;
        if (stack.isOf(Items.GOLDEN_AXE)) return 3;
        if (stack.isOf(Items.STONE_AXE)) return 2;
        if (stack.isOf(Items.WOODEN_AXE)) return 1;
        return 0;
    }

    private void swapToSlot(int slot) {
        if (mc.player == null) return;
        if (slot < 0 || slot > 8) return;
        mc.player.getInventory().setSelectedSlot(slot);
    }
}


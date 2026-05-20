package com.xenon.module.modules.combat;

import com.xenon.module.Category;
import com.xenon.module.ActivatableModule;
import com.xenon.setting.Setting;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import net.minecraft.network.packet.Packet;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;









public final class DoubleAnchor extends ActivatableModule {

    private final Setting<Float> switchDelay = new Setting<>("Delay", 0.0f, 0.0f, 20.0f);
    
    private final Setting<Float> totemSlot = new Setting<>("Totem Slot", 1.0f, 1.0f, 9.0f);
    
    private final Setting<Boolean> switchBack = new Setting<>("Switch Back", false);

    private int delayCounter = 0;
    private int step = 0;
    private boolean isAnchoring = false;
    
    private BlockPos lastAnchorPos = null;
    
    private boolean waitingForPop = false;

    public DoubleAnchor() {
        super("Double Anchor", Category.COMBAT);
        addSetting(switchDelay);
        addSetting(totemSlot);
        addSetting(switchBack);
    }

    @Override
    public void onEnable() {
        resetState();
        isAnchoring = false;
        waitingForPop = false;
        lastAnchorPos = null;
        super.onEnable();
    }

    @Override
    public void onDisable() {
        resetState();
        isAnchoring = false;
        waitingForPop = false;
        lastAnchorPos = null;
        super.onDisable();
    }

    


    @Override
    public void onBindPressed() {
        super.onBindPressed();
    }

    


    @Override
    public void onActivationKeyPressed() {
        
        if (!isEnabled()) return;

        resetState();
        isAnchoring = true;
        waitingForPop = false;
        lastAnchorPos = null;
    }

    @Override
    public void onPacketReceive(Packet<?> packet) {
        
        
        if (switchBack.getValue() && waitingForPop && packet instanceof HealthUpdateS2CPacket) {
            waitingForPop = false;
            if (mc.player != null) {
                int anchorSlot = findItemInHotbar(Items.RESPAWN_ANCHOR);
                if (anchorSlot != -1) {
                    mc.player.getInventory().setSelectedSlot(anchorSlot);
                }
            }
        }
    }

    @Override
    public void onTick() {
        
        if (!isAnchoring) return;

        if (mc.currentScreen != null) return;
        if (mc.player == null || mc.world == null) return;
        if (!hasRequiredItems()) {
            isAnchoring = false;
            resetState();
            return;
        }

        if (!(mc.crosshairTarget instanceof BlockHitResult blockHitResult)) {
            isAnchoring = false;
            resetState();
            return;
        }

        
        if (mc.world.getBlockState(blockHitResult.getBlockPos()).isOf(Blocks.AIR)) {
            isAnchoring = false;
            resetState();
            return;
        }

        int switchDelayTicks = Math.max(0, switchDelay.getValue().intValue());
        if (delayCounter < switchDelayTicks) {
            delayCounter++;
            return;
        }

        
        if (step == 0) {
            swapToItem(Items.RESPAWN_ANCHOR);
        } else if (step == 1) {
            interactWithBlock(blockHitResult);
        } else if (step == 2) {
            swapToItem(Items.GLOWSTONE);
        } else if (step == 3) {
            interactWithBlock(blockHitResult);
        } else if (step == 4) {
            swapToItem(Items.RESPAWN_ANCHOR);
        } else if (step == 5) {
            
            interactWithBlock(blockHitResult);
            interactWithBlock(blockHitResult);
        } else if (step == 6) {
            swapToItem(Items.GLOWSTONE);
        } else if (step == 7) {
            interactWithBlock(blockHitResult);
        } else if (step == 8) {
            int desiredSlot = totemSlot.getValue().intValue() - 1;
            swapToHotbarSlot(desiredSlot);
            
            lastAnchorPos = blockHitResult.getBlockPos();
        } else if (step == 9) {
            interactWithBlock(blockHitResult);
            
            if (switchBack.getValue()) {
                waitingForPop = true;
            }
        } else if (step == 10) {
            
            isAnchoring = false;
            resetState();
            return;
        }

        step++;
    }

    private void resetState() {
        delayCounter = 0;
        step = 0;
    }

    private boolean hasRequiredItems() {
        boolean hasAnchor = false;
        boolean hasGlowstone = false;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(Items.RESPAWN_ANCHOR)) hasAnchor = true;
            if (stack.isOf(Items.GLOWSTONE)) hasGlowstone = true;
        }
        return hasAnchor && hasGlowstone;
    }

    private int findItemInHotbar(Item item) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    private void swapToItem(Item item) {
        int slot = findItemInHotbar(item);
        if (slot != -1) mc.player.getInventory().setSelectedSlot(slot);
    }

    private void swapToHotbarSlot(int slot) {
        if (slot < 0 || slot > 8) return;
        mc.player.getInventory().setSelectedSlot(slot);
    }

    private void interactWithBlock(BlockHitResult hit) {
        
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
    }
}

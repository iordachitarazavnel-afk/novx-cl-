package com.xenon.module.modules.combat;

import com.xenon.module.Category;
import com.xenon.module.ActivatableModule;
import com.xenon.setting.Setting;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.item.ShieldItem;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;












public final class AnchorMacro extends ActivatableModule {

    private final Setting<Float> switchDelay =
            new Setting<>("Switch Delay", 0.0f, 0.0f, 20.0f);
    private final Setting<Float> glowstoneDelay =
            new Setting<>("Glowstone Delay", 0.0f, 0.0f, 20.0f);
    private final Setting<Float> explodeDelay =
            new Setting<>("Explode Delay", 0.0f, 0.0f, 20.0f);
    private final Setting<Float> totemSlot =
            new Setting<>("Totem Slot", 1.0f, 1.0f, 9.0f);
    private final Setting<Boolean> switchBack =
            new Setting<>("Switch Back", false);

    private int switchCounter;
    private int glowstoneDelayCounter;
    private int explodeDelayCounter;
    
    private boolean waitingForPop = false;

    public AnchorMacro() {
        super("Anchor Macro", Category.COMBAT);
        addSetting(switchDelay);
        addSetting(glowstoneDelay);
        addSetting(explodeDelay);
        addSetting(totemSlot);
        addSetting(switchBack);
    }

    @Override
    public void onEnable() {
        resetCounters();
        waitingForPop = false;
        super.onEnable();
    }

    @Override
    public void onDisable() {
        resetCounters();
        waitingForPop = false;
        super.onDisable();
    }

    @Override
    public void onPacketReceive(Packet<?> packet) {
        
        if (switchBack.getValue() && waitingForPop && packet instanceof HealthUpdateS2CPacket) {
            waitingForPop = false;
            if (mc.player != null) {
                int anchorSlot = findItemSlot(Items.RESPAWN_ANCHOR);
                if (anchorSlot != -1) {
                    mc.player.getInventory().setSelectedSlot(anchorSlot);
                }
            }
        }
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (mc.currentScreen != null) return;

        
        if (isShieldOrFoodActive()) return;

        
        if (!isRightClickHeld()) {
            resetCounters();
            return;
        }

        handleAnchorInteraction();
    }

    



    private boolean isShieldOrFoodActive() {
        boolean isFood =
                mc.player.getMainHandStack().getItem().getComponents().contains(DataComponentTypes.FOOD)
                || mc.player.getOffHandStack().getItem().getComponents().contains(DataComponentTypes.FOOD);

        boolean isShield =
                mc.player.getMainHandStack().getItem() instanceof ShieldItem
                || mc.player.getOffHandStack().getItem() instanceof ShieldItem;

        boolean rightClickPressed = isRightClickHeld();
        return (isFood || isShield) && rightClickPressed;
    }

    


    private boolean isRightClickHeld() {
        return mc.getWindow() != null
                && GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
    }

    


    private void handleAnchorInteraction() {
        
        if (!(mc.crosshairTarget instanceof BlockHitResult blockHitResult)) return;
        if (blockHitResult.getType() != HitResult.Type.BLOCK) return;

        BlockPos pos = blockHitResult.getBlockPos();
        BlockState state = mc.world.getBlockState(pos);

        
        if (!state.isOf(Blocks.RESPAWN_ANCHOR)) return;

        
        mc.options.useKey.setPressed(false);

        int charges = state.get(RespawnAnchorBlock.CHARGES);

        if (charges == 0) {
            
            placeGlowstone(blockHitResult);
        } else {
            
            explodeAnchor(blockHitResult);
        }
    }

    


    private void placeGlowstone(BlockHitResult blockHitResult) {
        
        if (!mc.player.getMainHandStack().isOf(Items.GLOWSTONE)) {
            if (switchCounter < switchDelay.getValue().intValue()) {
                switchCounter++;
                return;
            }
            switchCounter = 0;

            if (!swapToItem(Items.GLOWSTONE)) return; 
        }

        
        if (mc.player.getMainHandStack().isOf(Items.GLOWSTONE)) {
            if (glowstoneDelayCounter < glowstoneDelay.getValue().intValue()) {
                glowstoneDelayCounter++;
                return;
            }
            glowstoneDelayCounter = 0;
            interactWith(blockHitResult);
        }
    }

    


    private void explodeAnchor(BlockHitResult blockHitResult) {
        int selectedSlot = Math.max(0, Math.min(8, totemSlot.getValue().intValue() - 1));

        
        if (mc.player.getInventory().getSelectedSlot() != selectedSlot) {
            if (switchCounter < switchDelay.getValue().intValue()) {
                switchCounter++;
                return;
            }
            switchCounter = 0;
            mc.player.getInventory().setSelectedSlot(selectedSlot);
        }

        
        if (mc.player.getInventory().getSelectedSlot() == selectedSlot) {
            if (explodeDelayCounter < explodeDelay.getValue().intValue()) {
                explodeDelayCounter++;
                return;
            }
            explodeDelayCounter = 0;
            interactWith(blockHitResult);
            
            if (switchBack.getValue()) {
                waitingForPop = true;
            }
        }
    }

    

    private int findItemSlot(Item item) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    



    private boolean swapToItem(Item item) {
        int slot = findItemSlot(item);
        if (slot != -1) {
            mc.player.getInventory().setSelectedSlot(slot);
            return true;
        }
        return false;
    }

    private void interactWith(BlockHitResult hit) {
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private void resetCounters() {
        switchCounter = 0;
        glowstoneDelayCounter = 0;
        explodeDelayCounter = 0;
    }
}

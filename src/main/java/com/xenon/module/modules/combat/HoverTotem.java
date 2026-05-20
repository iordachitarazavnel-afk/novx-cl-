package com.xenon.module.modules.combat;

import com.xenon.mixin.HandledScreenAccessor;
import com.xenon.module.Category;
import com.xenon.module.Module;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;




public final class HoverTotem extends Module {

    
    private int lastSwapAttemptHandlerSlotId = -1;

    public HoverTotem() {
        super("Hover Totem", Category.COMBAT);
    }

    @Override
    public void onDisable() {
        lastSwapAttemptHandlerSlotId = -1;
        super.onDisable();
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.interactionManager == null) return;

        if (!(mc.currentScreen instanceof HandledScreen<?> handled)) {
            lastSwapAttemptHandlerSlotId = -1;
            return;
        }

        Slot focused = ((HandledScreenAccessor) handled).xenon$getFocusedSlot();
        if (focused == null || focused.getStack().isEmpty()) {
            lastSwapAttemptHandlerSlotId = -1;
            return;
        }

        if (!focused.getStack().isOf(Items.TOTEM_OF_UNDYING)) {
            lastSwapAttemptHandlerSlotId = -1;
            return;
        }

        if (mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            return;
        }

        if (focused.id == lastSwapAttemptHandlerSlotId) {
            return;
        }

        int syncId = mc.player.currentScreenHandler.syncId;
        mc.interactionManager.clickSlot(syncId, focused.id, 40, SlotActionType.SWAP, mc.player);
        lastSwapAttemptHandlerSlotId = focused.id;
    }
}

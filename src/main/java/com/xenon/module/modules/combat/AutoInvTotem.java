package com.xenon.module.modules.combat;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.Setting;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;

import java.util.Random;

public final class AutoInvTotem extends Module {
    private final Setting<Float> delay = new Setting<>("Delay", 2.0f, 0.0f, 20.0f);
    private final Setting<Boolean> hotbar = new Setting<>("Hotbar", false);
    private final Setting<Float> totemSlot = new Setting<>("Totem Slot", 1.0f, 1.0f, 9.0f);
    private final Setting<Boolean> forceTotem = new Setting<>("Force Totem", false);
    private final Setting<Boolean> autoOpen = new Setting<>("Auto Open", false);
    private final Setting<Float> closeDelay = new Setting<>("Close Delay", 3.0f, 0.0f, 20.0f);

    
    private static final int STATE_IDLE = 0;
    private static final int STATE_WAIT_OPEN = 1; 
    private static final int STATE_INV_OPEN = 2; 
    private static final int STATE_SWAPPED = 3; 

    private int state = STATE_IDLE;
    private int tickCounter = 0;
    private boolean wasTotemInOffhand = true; 
    private final Random random = new Random();

    public AutoInvTotem() {
        super("Auto Inv Totem", Category.COMBAT);
        this.addSetting(this.delay);
        this.addSetting(this.hotbar);
        this.addSetting(this.totemSlot);
        this.addSetting(this.forceTotem);
        this.addSetting(this.autoOpen);
        this.addSetting(this.closeDelay);
    }

    @Override
    public void onEnable() {
        this.state = STATE_IDLE;
        this.tickCounter = 0;
        this.wasTotemInOffhand = true;
        super.onEnable();
    }

    @Override
    public void onDisable() {
        this.state = STATE_IDLE;
        this.tickCounter = 0;
        super.onDisable();
    }

    @Override
    public void onTick() {
        if (this.mc.player == null || this.mc.interactionManager == null) {
            return;
        }

        final PlayerInventory inv = this.mc.player.getInventory();
        final boolean totemInOffhand = this.mc.player.getOffHandStack().getItem() == Items.TOTEM_OF_UNDYING;

        
        if (this.autoOpen.getValue()) {
            this.handleAutoOpen(inv, totemInOffhand);
            this.wasTotemInOffhand = totemInOffhand;
            return;
        }

        
        this.wasTotemInOffhand = totemInOffhand;

        if (!(this.mc.currentScreen instanceof InventoryScreen)) {
            this.tickCounter = 0;
            return;
        }

        
        if (this.tickCounter < this.delay.getValue().intValue() + randomJitter()) {
            this.tickCounter++;
            return;
        }

        
        if (!totemInOffhand) {
            if (this.swapTotemToOffhand(inv)) {
                this.tickCounter = 0;
                return;
            }
        }

        
        if (this.hotbar.getValue()) {
            this.tryHotbarTotem(inv);
        }

        this.tickCounter = 0;
    }

    




    private void handleAutoOpen(final PlayerInventory inv, final boolean totemInOffhand) {
        switch (this.state) {
            case STATE_IDLE:
                
                if (this.wasTotemInOffhand && !totemInOffhand) {
                    
                    if (this.findTotemSlot(inv) != -1) {
                        this.state = STATE_WAIT_OPEN;
                        
                        
                        this.tickCounter = this.delay.getValue().intValue() <= 0 ? 1 + random.nextInt(2)
                                : 1 + random.nextInt(3);
                    }
                }

                
                if (!totemInOffhand && this.state == STATE_IDLE
                        && !(this.mc.currentScreen instanceof InventoryScreen)) {
                    if (this.findTotemSlot(inv) != -1) {
                        this.state = STATE_WAIT_OPEN;
                        this.tickCounter = this.delay.getValue().intValue() <= 0 ? 1 + random.nextInt(2)
                                : 1 + random.nextInt(3);
                    }
                }
                break;

            case STATE_WAIT_OPEN:
                if (this.tickCounter > 0) {
                    this.tickCounter--;
                    return;
                }
                
                if (!(this.mc.currentScreen instanceof InventoryScreen)) {
                    
                    
                    
                    
                    this.mc.setScreen(new InventoryScreen(this.mc.player));
                }
                this.state = STATE_INV_OPEN;
                
                this.tickCounter = this.delay.getValue().intValue() + randomJitter();
                break;

            case STATE_INV_OPEN:
                
                if (!(this.mc.currentScreen instanceof InventoryScreen)) {
                    
                    this.state = STATE_IDLE;
                    return;
                }

                if (this.tickCounter > 0) {
                    this.tickCounter--;
                    return;
                }

                
                boolean didSwapOffhand = false;
                if (!totemInOffhand) {
                    didSwapOffhand = this.swapTotemToOffhand(inv);
                }

                boolean didSwapHotbar = false;
                if (this.hotbar.getValue()) {
                    didSwapHotbar = this.tryHotbarTotem(inv);
                }

                if (didSwapOffhand || didSwapHotbar || totemInOffhand) {
                    this.state = STATE_SWAPPED;
                    
                    this.tickCounter = this.closeDelay.getValue().intValue() + randomJitter();
                } else {
                    
                    this.state = STATE_SWAPPED;
                    this.tickCounter = 1;
                }
                break;

            case STATE_SWAPPED:
                if (this.tickCounter > 0) {
                    this.tickCounter--;
                    return;
                }

                
                if (this.mc.currentScreen instanceof InventoryScreen) {
                    this.mc.player.closeHandledScreen();
                    this.mc.setScreen(null);
                }

                this.state = STATE_IDLE;
                break;
        }
    }

    












    private boolean swapTotemToOffhand(final PlayerInventory inv) {
        final int invSlot = this.findTotemSlot(inv);
        if (invSlot == -1) {
            return false;
        }

        
        final int handlerSlot = convertToHandlerSlot(invSlot);

        
        this.mc.interactionManager.clickSlot(
                this.mc.player.currentScreenHandler.syncId,
                handlerSlot,
                40, 
                SlotActionType.SWAP,
                this.mc.player);

        return true;
    }

    


    private boolean tryHotbarTotem(final PlayerInventory inv) {
        final int preferredSlot = this.totemSlot.getValue().intValue() - 1; 

        
        if (inv.getStack(preferredSlot).getItem() == Items.TOTEM_OF_UNDYING) {
            return false;
        }

        
        if (!inv.getStack(preferredSlot).isEmpty() && !this.forceTotem.getValue()) {
            return false;
        }

        
        final int totemSlotIdx = this.findTotemSlotMainOnly(inv);
        if (totemSlotIdx == -1) {
            return false;
        }

        final int handlerSlot = convertToHandlerSlot(totemSlotIdx);

        this.mc.interactionManager.clickSlot(
                this.mc.player.currentScreenHandler.syncId,
                handlerSlot,
                preferredSlot, 
                SlotActionType.SWAP,
                this.mc.player);

        return true;
    }

    



    private int findTotemSlot(final PlayerInventory inv) {
        
        for (int i = 9; i < 36; i++) {
            if (inv.getStack(i).getItem() == Items.TOTEM_OF_UNDYING) {
                return i;
            }
        }
        
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).getItem() == Items.TOTEM_OF_UNDYING) {
                return i;
            }
        }
        return -1;
    }

    


    private int findTotemSlotMainOnly(final PlayerInventory inv) {
        for (int i = 9; i < 36; i++) {
            if (inv.getStack(i).getItem() == Items.TOTEM_OF_UNDYING) {
                return i;
            }
        }
        return -1;
    }

    




    private static int convertToHandlerSlot(final int invSlot) {
        if (invSlot < 9) {
            return 36 + invSlot;
        }
        return invSlot;
    }

    


    private int randomJitter() {
        
        if (this.delay.getValue().intValue() <= 0) {
            return 0;
        }
        return random.nextInt(2);
    }
}

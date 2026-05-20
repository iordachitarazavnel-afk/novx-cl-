package com.xenon.module.modules.combat;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.Setting;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;

public final class AutoDoubleHand extends Module {

    private final Setting<Boolean> onTotemPop = new Setting<>("On Totem Pop", true);
    private final Setting<Boolean> onHealth = new Setting<>("On Health", true);
    private final Setting<Float> healthThreshold = new Setting<>("Health Threshold", 6.0f, 1.0f, 20.0f);
    private final Setting<Float> cooldown = new Setting<>("Cooldown", 5.0f, 0.0f, 40.0f);

    private boolean hadTotemHeldLastTick = false;
    private int cooldownTicks = 0;
    private int previousSlot = -1;

    public AutoDoubleHand() {
        super("AutoDoubleHand", Category.COMBAT);
        addSetting(onTotemPop);
        addSetting(onHealth);
        addSetting(healthThreshold);
        addSetting(cooldown);
    }

    @Override
    public void onEnable() {
        hadTotemHeldLastTick = false;
        cooldownTicks = 0;
        previousSlot = -1;
    }

    @Override
    public void onDisable() {
        hadTotemHeldLastTick = false;
        cooldownTicks = 0;
        previousSlot = -1;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.interactionManager == null) return;

        if (cooldownTicks > 0) cooldownTicks--;

        PlayerInventory inv = mc.player.getInventory();
        boolean totemMain = mc.player.getMainHandStack().getItem() == Items.TOTEM_OF_UNDYING;
        boolean totemOff  = mc.player.getOffHandStack().getItem() == Items.TOTEM_OF_UNDYING;
        boolean totemHeld = totemMain || totemOff;

        boolean popped = hadTotemHeldLastTick && !totemHeld;
        hadTotemHeldLastTick = totemHeld;

        if (cooldownTicks > 0) return;

        boolean shouldSwitch = false;
        if (onTotemPop.getValue() && popped) shouldSwitch = true;
        if (onHealth.getValue() && mc.player.getHealth() <= healthThreshold.getValue()) shouldSwitch = true;

        if (!shouldSwitch) return;

        if (totemMain) return;

        int hotbarSlot = findHotbarTotemSlot();
        if (hotbarSlot < 0) return;
        if (inv.getSelectedSlot() == hotbarSlot) return;

        previousSlot = inv.getSelectedSlot();
        inv.setSelectedSlot(hotbarSlot);
        cooldownTicks = cooldown.getValue().intValue();
    }

    private int findHotbarTotemSlot() {
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return i;
        }
        return -1;
    }
}

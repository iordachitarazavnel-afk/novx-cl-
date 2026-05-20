package com.xenon.module.modules.combat;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.Setting;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;

public final class SpearSwap extends Module {

    public static SpearSwap INSTANCE;

    private final Setting<Boolean> lunge = new Setting<>("Lunge", true);
    private final Setting<Boolean> sharpness = new Setting<>("Sharpness", false);
    private final Setting<Boolean> onlySword = new Setting<>("Only Sword", false);
    private final Setting<Boolean> onlyAxe = new Setting<>("Only Axe", false);
    private final Setting<Boolean> switchBack = new Setting<>("Switch Back", true);
    private final Setting<Float> switchDelay = new Setting<>("Switch Delay", 1.0f, 1.0f, 20.0f);

    private int previousSlot = -1;
    private int countdown = 0;
    private boolean attackHeldLastCheck = false;

    public SpearSwap() {
        super("SpearSwap", Category.COMBAT);
        addSetting(lunge);
        addSetting(sharpness);
        addSetting(onlySword);
        addSetting(onlyAxe);
        addSetting(switchBack);
        addSetting(switchDelay);
        INSTANCE = this;
    }

    /** Called from MinecraftClientAttackMixin every frame while attack key is held.
     *  Only swaps on the rising edge (just-pressed) and waits for countdown between swaps. */
    public void preAttack() {
        if (mc.player == null) return;
        boolean wasHeld = attackHeldLastCheck;
        attackHeldLastCheck = true;
        if (wasHeld) return;
        if (countdown > 0) return;
        PlayerInventory inv = mc.player.getInventory();
        int slot = findBestWeaponSlot();
        if (slot < 0) return;
        if (inv.getSelectedSlot() == slot) return;
        previousSlot = inv.getSelectedSlot();
        inv.setSelectedSlot(slot);
        countdown = Math.max(1, switchDelay.getValue().intValue());
    }

    /** Called when attack key is NOT held — resets the rising-edge tracker. */
    public void noAttack() {
        attackHeldLastCheck = false;
    }

@Override
    public void onEnable() {
        previousSlot = -1;
        countdown = 0;
    }

    @Override
    public void onDisable() {
        previousSlot = -1;
        countdown = 0;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.options == null) return;
        PlayerInventory inv = mc.player.getInventory();

        // Switch-back countdown
        if (countdown > 0) {
            countdown--;
            if (countdown == 0) {
                if (switchBack.getValue() && previousSlot >= 0 && previousSlot < 9
                        && inv.getSelectedSlot() != previousSlot) {
                    inv.setSelectedSlot(previousSlot);
                }
                previousSlot = -1;
            }
        }

        // Backup trigger via tick polling, in case the mixin doesn't fire on this client.
        if (mc.options.attackKey.isPressed()) {
            preAttack();
        } else {
            noAttack();
        }
    }

    private int findBestWeaponSlot() {
        PlayerInventory inv = mc.player.getInventory();
        boolean swordOnly = onlySword.getValue();
        boolean axeOnly = onlyAxe.getValue();
        boolean wantLunge = lunge.getValue();
        boolean wantSharp = sharpness.getValue();

        int bestSlot = -1;
        int bestScore = Integer.MIN_VALUE;

        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;

            String path = Registries.ITEM.getId(s.getItem()).getPath();
            String displayName = "";
            try { displayName = s.getName().getString().toLowerCase(); } catch (Throwable ignored) {}

            boolean isSword = path.endsWith("_sword");
            boolean isAxe = s.getItem() instanceof AxeItem;
            boolean isSpear = s.getItem() == Items.TRIDENT
                    || s.getItem() == Items.MACE
                    || path.contains("spear")
                    || displayName.contains("spear");
            boolean hasLunge = hasLungeEnchant(s);

            // Anything that has a lunge enchant counts as a valid swap target.
            boolean lungeItem = wantLunge && hasLunge;

            if (swordOnly && !isSword) continue;
            if (axeOnly && !isAxe) continue;
            if (!swordOnly && !axeOnly && !isSword && !isAxe && !isSpear && !lungeItem) continue;

            int score = 0;
            if (lungeItem) score += 500;
            if (isSpear) score += 300;
            else if (isSword) score += 200;
            else if (isAxe) score += 100;
            if (wantSharp) score += sharpnessLevel(s) * 60;

            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private boolean hasLungeEnchant(ItemStack s) {
        try {
            ItemEnchantmentsComponent comp = s.get(DataComponentTypes.ENCHANTMENTS);
            if (comp == null) return false;
            for (RegistryEntry<Enchantment> entry : comp.getEnchantments()) {
                // Vanilla enchants
                RegistryKey<Enchantment> key = entry.getKey().orElse(null);
                if (key != null) {
                    if (key.equals(Enchantments.WIND_BURST)
                            || key.equals(Enchantments.DENSITY)
                            || key.equals(Enchantments.RIPTIDE)) {
                        return true;
                    }
                    // Custom enchants on servers (e.g. donut "lunge")
                    String idPath = key.getValue().toString().toLowerCase();
                    if (idPath.contains("lunge")) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private int sharpnessLevel(ItemStack s) {
        try {
            ItemEnchantmentsComponent comp = s.get(DataComponentTypes.ENCHANTMENTS);
            if (comp == null) return 0;
            for (RegistryEntry<Enchantment> entry : comp.getEnchantments()) {
                RegistryKey<Enchantment> key = entry.getKey().orElse(null);
                if (key != null && key.equals(Enchantments.SHARPNESS)) {
                    return comp.getLevel(entry);
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }
}

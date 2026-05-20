package com.xenon.module.modules.misc;

import com.xenon.module.Category;
import com.xenon.module.Module;
import net.minecraft.block.BambooBlock;
import net.minecraft.block.BambooShootBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.LeavesBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ShearsItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

import java.util.function.Predicate;

public final class AutoTool extends Module {

    public AutoTool() {
        super("Auto Tool", Category.MISC);
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            return;
        }
        if (!mc.options.attackKey.isPressed() || mc.crosshairTarget == null) {
            return;
        }

        HitResult crosshairTarget = mc.crosshairTarget;
        if (crosshairTarget.getType() == HitResult.Type.ENTITY && crosshairTarget instanceof EntityHitResult) {
            switchToBestWeapon();
            return;
        }

        if (crosshairTarget.getType() == HitResult.Type.BLOCK && crosshairTarget instanceof BlockHitResult blockHitResult) {
            switchToBestTool(blockHitResult.getBlockPos());
        }
    }

    private void switchToBestTool(net.minecraft.util.math.BlockPos blockPos) {
        BlockState blockState = mc.world.getBlockState(blockPos);
        ItemStack currentStack = mc.player.getMainHandStack();
        int bestSlot = -1;
        double bestEfficiency = -1.0;

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            double efficiency = calculateToolEfficiency(stack, blockState, itemStack -> true);
            if (efficiency > bestEfficiency) {
                bestEfficiency = efficiency;
                bestSlot = slot;
            }
        }

        if (bestSlot == -1) {
            return;
        }

        double currentEfficiency = calculateToolEfficiency(currentStack, blockState, itemStack -> true);
        if (bestEfficiency > currentEfficiency || !isToolItemStack(currentStack)) {
            swapToSlot(bestSlot);
        }
    }

    private void switchToBestWeapon() {
        int bestSlot = -1;
        double bestDamage = Double.NEGATIVE_INFINITY;

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }

            double damage = getAttackDamage(stack);
            if (damage > bestDamage) {
                bestDamage = damage;
                bestSlot = slot;
            }
        }

        if (bestSlot != -1) {
            swapToSlot(bestSlot);
        }
    }

    private double getAttackDamage(ItemStack stack) {
        AttributeModifiersComponent modifiers = stack.get(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        double damage = 0.0;

        if (modifiers != null) {
            for (AttributeModifiersComponent.Entry entry : modifiers.modifiers()) {
                if (entry.attribute().toString().contains("attack_damage")) {
                    damage += entry.modifier().value();
                }
            }
        }

        if (damage > 0.0) {
            return damage;
        }

        String itemPath = Registries.ITEM.getId(stack.getItem()).getPath();
        if (itemPath.endsWith("_sword")) {
            return 10.0 + getMaterialRank(itemPath);
        }
        if (itemPath.endsWith("_axe")) {
            return 5.0 + getMaterialRank(itemPath);
        }
        return 0.0;
    }

    private void swapToSlot(int slot) {
        if (slot < 0 || slot > 8) {
            return;
        }
        if (mc.player.getInventory().getSelectedSlot() != slot) {
            mc.player.getInventory().setSelectedSlot(slot);
        }
    }

    public static double calculateToolEfficiency(
            ItemStack itemStack,
            BlockState blockState,
            Predicate<ItemStack> predicate
    ) {
        if (!predicate.test(itemStack) || !isToolItemStack(itemStack)) {
            return -1.0;
        }
        String itemPath = Registries.ITEM.getId(itemStack.getItem()).getPath();
        boolean isSword = itemPath.endsWith("_sword");
        if (!itemStack.isSuitableFor(blockState)
                && (!isSword
                || (!(blockState.getBlock() instanceof BambooBlock) && !(blockState.getBlock() instanceof BambooShootBlock)))
                && (!(itemStack.getItem() instanceof ShearsItem) || !(blockState.getBlock() instanceof LeavesBlock))
                && !blockState.isIn(BlockTags.WOOL)) {
            return -1.0;
        }
        return itemStack.getMiningSpeedMultiplier(blockState) * 1000.0f;
    }

    public static boolean isToolItemStack(ItemStack itemStack) {
        return isToolItem(itemStack.getItem());
    }

    public static boolean isToolItem(Item item) {
        if (item instanceof ShearsItem) {
            return true;
        }

        String itemPath = Registries.ITEM.getId(item).getPath();
        return itemPath.endsWith("_pickaxe")
                || itemPath.endsWith("_axe")
                || itemPath.endsWith("_shovel")
                || itemPath.endsWith("_hoe")
                || itemPath.endsWith("_sword");
    }

    private double getMaterialRank(String itemPath) {
        if (itemPath.startsWith("netherite_")) {
            return 6.0;
        }
        if (itemPath.startsWith("diamond_")) {
            return 5.0;
        }
        if (itemPath.startsWith("iron_")) {
            return 4.0;
        }
        if (itemPath.startsWith("golden_")) {
            return 3.0;
        }
        if (itemPath.startsWith("stone_")) {
            return 2.0;
        }
        if (itemPath.startsWith("wooden_")) {
            return 1.0;
        }
        return 0.0;
    }
}

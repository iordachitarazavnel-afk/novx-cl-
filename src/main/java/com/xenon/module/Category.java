package com.xenon.module;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

public enum Category {
    MISC("Misc"),
    RENDER("Render"),
    COMBAT("Combat"),
    DONUT("Donut"),
    CLIENT("Client");

    private final String name;

    Category(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public ItemStack getIcon() {
        return switch (this) {
            case MISC   -> new ItemStack(Items.COMPASS);
            case RENDER -> new ItemStack(Items.SPYGLASS);
            case COMBAT -> new ItemStack(Items.NETHERITE_SWORD);
            case DONUT  -> new ItemStack(Items.HEART_OF_THE_SEA);
            case CLIENT -> new ItemStack(Items.NETHER_STAR);
        };
    }
}

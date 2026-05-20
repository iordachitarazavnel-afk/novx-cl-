package com.xenon.setting;

import net.minecraft.item.ItemStack;

public record OptionEntry(String value, String label, ItemStack previewStack) {
    public ItemStack getPreviewStack() {
        return previewStack == null ? ItemStack.EMPTY : previewStack.copy();
    }
}

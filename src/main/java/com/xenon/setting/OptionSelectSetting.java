package com.xenon.setting;

import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class OptionSelectSetting extends Setting<String> {
    private final String defaultValue;
    private final List<OptionEntry> options;

    public OptionSelectSetting(String name, String defaultValue, OptionEntry... options) {
        super(name, defaultValue);
        this.defaultValue = defaultValue;
        this.options = List.of(options);
    }

    public List<OptionEntry> getOptions() {
        return options;
    }

    public List<OptionEntry> filter(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return options;
        }

        List<OptionEntry> filtered = new ArrayList<>();
        for (OptionEntry option : options) {
            String value = option.value() == null ? "" : option.value().toLowerCase(Locale.ROOT);
            String label = option.label() == null ? "" : option.label().toLowerCase(Locale.ROOT);
            if (value.contains(normalized) || label.contains(normalized)) {
                filtered.add(option);
            }
        }
        return filtered;
    }

    public void select(String value) {
        if (value == null) {
            reset();
            return;
        }

        for (OptionEntry option : options) {
            if (option.value().equalsIgnoreCase(value)) {
                setValue(option.value());
                return;
            }
        }
    }

    public void reset() {
        setValue(defaultValue);
    }

    public boolean isSelected(OptionEntry option) {
        return option != null && getValue() != null && option.value().equalsIgnoreCase(getValue());
    }

    public OptionEntry getSelectedOption() {
        for (OptionEntry option : options) {
            if (isSelected(option)) {
                return option;
            }
        }
        return options.isEmpty() ? null : options.getFirst();
    }

    public String getSummary() {
        OptionEntry selected = getSelectedOption();
        return selected == null ? "Choose" : selected.label();
    }

    public ItemStack getPreviewStack() {
        OptionEntry selected = getSelectedOption();
        return selected == null ? ItemStack.EMPTY : selected.getPreviewStack();
    }
}

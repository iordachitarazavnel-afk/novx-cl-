package com.xenon.setting;

import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class OptionMultiSelectSetting extends Setting<Set<String>> {
    private final List<OptionEntry> options;

    public OptionMultiSelectSetting(String name, OptionEntry... options) {
        super(name, new LinkedHashSet<>());
        this.options = List.of(options);
    }

    @Override
    public void setValue(Set<String> value) {
        LinkedHashSet<String> next = new LinkedHashSet<>();
        if (value != null) {
            for (String rawValue : value) {
                if (rawValue == null) {
                    continue;
                }
                for (OptionEntry option : options) {
                    if (option.value().equalsIgnoreCase(rawValue)) {
                        next.add(option.value());
                        break;
                    }
                }
            }
        }
        super.setValue(next);
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

    public void toggle(String value) {
        if (value == null) {
            return;
        }

        LinkedHashSet<String> next = new LinkedHashSet<>(getValue());
        String canonical = null;
        for (OptionEntry option : options) {
            if (option.value().equalsIgnoreCase(value)) {
                canonical = option.value();
                break;
            }
        }
        if (canonical == null) {
            return;
        }

        if (!next.add(canonical)) {
            next.remove(canonical);
        }
        setValue(next);
    }

    public void clear() {
        if (getValue().isEmpty()) {
            return;
        }
        setValue(Collections.emptySet());
    }

    public boolean contains(String value) {
        if (value == null) {
            return false;
        }
        for (String selected : getValue()) {
            if (selected.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    public boolean isSelected(OptionEntry option) {
        return option != null && contains(option.value());
    }

    public int size() {
        return getValue().size();
    }

    public List<OptionEntry> getSelectedOptions() {
        List<OptionEntry> selected = new ArrayList<>();
        for (OptionEntry option : options) {
            if (contains(option.value())) {
                selected.add(option);
            }
        }
        return selected;
    }

    public String getSummary() {
        List<OptionEntry> selected = getSelectedOptions();
        if (selected.isEmpty()) {
            return "Choose";
        }
        OptionEntry first = selected.getFirst();
        int extra = selected.size() - 1;
        return extra > 0 ? first.label() + " +" + extra : first.label();
    }

    public ItemStack getPreviewStack() {
        List<OptionEntry> selected = getSelectedOptions();
        if (selected.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return selected.getFirst().getPreviewStack();
    }
}

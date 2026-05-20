package com.xenon.setting;

import java.util.List;

public final class ModeSetting extends Setting<String> {
    private final List<String> modes;
    private final List<String> legacyNames;

    public ModeSetting(String name, String defaultValue, String... modes) {
        this(name, defaultValue, new String[0], modes);
    }

    public ModeSetting(String name, String defaultValue, String[] legacyNames, String... modes) {
        super(name, defaultValue);
        if (modes == null || modes.length == 0) {
            throw new IllegalArgumentException("ModeSetting requires at least one mode");
        }
        this.modes = List.of(modes);
        this.legacyNames = legacyNames == null ? List.of() : List.of(legacyNames);
        setValue(defaultValue);
    }

    public List<String> getModes() {
        return modes;
    }

    public void cycleNext() {
        setValue(getModeRelativeToCurrent(1));
    }

    public void cyclePrevious() {
        setValue(getModeRelativeToCurrent(-1));
    }

    public boolean is(String mode) {
        return normalize(mode).equalsIgnoreCase(getValue());
    }

    @Override
    public void setValue(String value) {
        super.setValue(resolveMode(value));
    }

    @Override
    public boolean matchesName(String settingName) {
        if (super.matchesName(settingName)) {
            return true;
        }

        String normalized = normalize(settingName);
        for (String legacyName : legacyNames) {
            if (legacyName.equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        return false;
    }

    private String getModeRelativeToCurrent(int direction) {
        int size = modes.size();
        if (size == 0) {
            return "";
        }

        String current = getValue();
        for (int i = 0; i < size; i++) {
            if (!modes.get(i).equalsIgnoreCase(current)) {
                continue;
            }
            int nextIndex = Math.floorMod(i + direction, size);
            return modes.get(nextIndex);
        }
        return modes.getFirst();
    }

    private String resolveMode(String value) {
        String normalized = normalize(value);
        for (String mode : modes) {
            if (mode.equalsIgnoreCase(normalized)) {
                return mode;
            }
        }
        return modes.getFirst();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}

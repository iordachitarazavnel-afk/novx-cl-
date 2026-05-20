package com.xenon.setting;

import com.xenon.module.ModuleManager;

/**
 * Couples an enabled flag with an integer threshold so the GUI can render
 * compact "toggle + slider" rows for tracked block thresholds.
 */
public final class ThresholdSetting extends Setting<Integer> {

    private boolean enabled;

    public ThresholdSetting(String name, boolean enabled, int value, int min, int max) {
        super(name, value, min, max);
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }

        this.enabled = enabled;
        ModuleManager.INSTANCE.onSettingChanged();
    }

    public String serialize() {
        return enabled + "|" + getValue();
    }

    public void deserialize(String serialized) {
        if (serialized == null || serialized.isBlank()) {
            return;
        }

        String[] parts = serialized.split("\\|", 2);
        try {
            if (parts.length == 2) {
                enabled = Boolean.parseBoolean(parts[0]);
                setValue(Integer.parseInt(parts[1]));
                return;
            }

            setValue(Integer.parseInt(serialized));
        } catch (NumberFormatException ignored) {
        }
    }
}

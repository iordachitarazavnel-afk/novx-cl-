package com.xenon.setting;

import com.xenon.module.ModuleManager;

import java.util.function.Supplier;

public class Setting<T> {
    private final String name;
    private final T defaultValue;
    private T value;
    private T min;
    private T max;
    private Supplier<Boolean> visibility = () -> true;

    public Setting(String name, T value) {
        this.name = name;
        this.value = value;
        this.defaultValue = value;
    }

    public Setting(String name, T value, T min, T max) {
        this.name = name;
        this.value = value;
        this.defaultValue = value;
        this.min = min;
        this.max = max;
    }

    public String getName() {
        return name;
    }

    public T getValue() {
        return value;
    }

    public T getDefaultValue() {
        return defaultValue;
    }

    public boolean matchesName(String settingName) {
        return name.equalsIgnoreCase(settingName);
    }

    public Setting<T> visibleWhen(Supplier<Boolean> visibility) {
        this.visibility = visibility == null ? () -> true : visibility;
        return this;
    }

    public boolean isVisible() {
        try {
            return visibility == null || visibility.get();
        } catch (Exception ignored) {
            return true;
        }
    }

    public void setValue(T value) {
        this.value = value;
        ModuleManager.INSTANCE.onSettingChanged();
    }

    public T getMin() {
        return min;
    }

    public T getMax() {
        return max;
    }
}
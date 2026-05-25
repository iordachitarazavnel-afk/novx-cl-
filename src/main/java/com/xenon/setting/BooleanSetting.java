package com.xenon.setting;

public class BooleanSetting extends Setting<Boolean> {

    public BooleanSetting(String name, boolean defaultValue) {
        super(name, defaultValue);
    }

    public boolean getValue() {
        return super.getValue();
    }

    public void toggle() {
        setValue(!getValue());
    }
}

package com.xenon.setting;

public class NumberSetting extends Setting<Double> {

    private final double step;

    public NumberSetting(String name, double min, double max, double defaultValue, double step) {
        super(name, defaultValue, min, max);
        this.step = step;
    }

    public double getStep() {
        return step;
    }

    public int getIntValue() {
        return getValue().intValue();
    }

    public float getFloatValue() {
        return getValue().floatValue();
    }

    public void setValueClamped(double value) {
        double clamped = Math.max(getMin(), Math.min(getMax(), value));
        setValue(clamped);
    }
}

package com.xenon.module.modules.render;

import com.xenon.module.Category;
import com.xenon.module.Module;
import net.minecraft.client.option.SimpleOption;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public final class FullBright extends Module {

    private static final double FULL_BRIGHT_GAMMA = 10.0;

    private double previousGamma = 1.0;
    private boolean gammaApplied = false;

    private static Field valueField;

    public FullBright() {
        super("FullBright", Category.RENDER);
    }

    @Override
    public void onEnable() {
        if (mc.options == null) return;
        previousGamma = mc.options.getGamma().getValue();
        setGamma(FULL_BRIGHT_GAMMA);
        gammaApplied = true;
    }

    @Override
    public void onDisable() {
        if (!gammaApplied) return;
        setGamma(previousGamma);
        gammaApplied = false;
    }

    @Override
    public void onTick() {
        if (!gammaApplied || mc.options == null) {
            return;
        }

        try {
            double current = mc.options.getGamma().getValue();
            if (Math.abs(current - FULL_BRIGHT_GAMMA) > 1.0E-4D) {
                setGamma(FULL_BRIGHT_GAMMA);
            }
        } catch (Exception ignored) {
        }
    }

    private void setGamma(double gamma) {
        SimpleOption<Double> opt = mc.options.getGamma();
        Field field = valueField;
        if (field == null) {
            field = resolveValueField(opt);
            valueField = field;
        }
        if (field != null) {
            try {
                field.set(opt, gamma);
                Double applied = opt.getValue();
                if (applied != null && Math.abs(applied - gamma) <= 1.0E-4D) {
                    return;
                }
                valueField = null;
            } catch (Exception ignored) {
            }
        }
        try {
            opt.setValue(gamma);
        } catch (Exception ignored) {
        }
    }

    private Field resolveValueField(SimpleOption<?> option) {
        Object current;
        try {
            current = option.getValue();
        } catch (Exception ignored) {
            current = null;
        }

        Field fallback = null;
        for (Field field : SimpleOption.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if ("value".equals(field.getName())) {
                fallback = field;
            }
            field.setAccessible(true);
            try {
                Object value = field.get(option);
                if (current == null ? value == null : current.equals(value)) {
                    return field;
                }
            } catch (Exception ignored) {
            }
        }

        if (fallback != null) {
            fallback.setAccessible(true);
            return fallback;
        }
        return null;
    }
}

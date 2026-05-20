package com.xenon.module.modules.client;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.ModeSetting;
import com.xenon.setting.Setting;

import java.awt.Color;

public final class XenonPlus extends Module {

    private static XenonPlus INSTANCE;

    private final Setting<Color>   accentColor = new Setting<>("Color",      new Color(107, 211, 165, 255));
    private final Setting<Color>   bgColor     = new Setting<>("Background", new Color(18, 24, 39, 255));
    private final Setting<Boolean> animations  = new Setting<>("Animations", true);
    private final Setting<Double>  tracerWidth = new Setting<>("Tracer Width", 1.0, 1.0, 6.0);
    private final ModeSetting menuSize    = new ModeSetting("Menu Size",  "5",
            "1","2","3","4","5","6","7","8","9","10");

    public XenonPlus() {
        super("Xenon +", Category.CLIENT);
        addSetting(accentColor);
        addSetting(bgColor);
        addSetting(animations);
        addSetting(tracerWidth);
        addSetting(menuSize);
        INSTANCE = this;
    }

    

    public static int getAccentARGB() {
        Color c = getAccentColor();
        return (c.getAlpha() << 24) | (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
    }

    public static Color getAccentColor() {
        Color themed = Themes.getAccent();
        if (themed != null) return themed;
        if (INSTANCE == null) return new Color(107, 211, 165, 255);
        return INSTANCE.accentColor.getValue();
    }

    public static int getBackgroundARGB() {
        Color c = getBackgroundColor();
        return (c.getAlpha() << 24) | (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
    }

    public static Color getBackgroundColor() {
        if (INSTANCE == null) return new Color(18, 24, 39, 255);
        Color c = INSTANCE.bgColor.getValue();
        if (c == null) return new Color(18, 24, 39, 255);
        if (c.getAlpha() < 200) return new Color(c.getRed(), c.getGreen(), c.getBlue(), 255);
        return c;
    }

    public static boolean animationsEnabled() {
        if (INSTANCE == null) return true;
        return INSTANCE.animations.getValue();
    }

    public static int menuSizePercent() {
        if (INSTANCE == null) return 5;
        try {
            return Integer.parseInt(INSTANCE.menuSize.getValue());
        } catch (Exception ignored) {
            return 5;
        }
    }

    public static float tracerLineWidth() {
        if (INSTANCE == null) return 1.0f;
        double v = 1.0;
        try {
            v = INSTANCE.tracerWidth.getValue();
        } catch (Exception ignored) {}
        if (Double.isNaN(v) || Double.isInfinite(v)) v = 1.0;
        if (v < 1.0) v = 1.0;
        if (v > 6.0) v = 6.0;
        return (float) v;
    }
}

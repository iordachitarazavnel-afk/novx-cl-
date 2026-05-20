package com.xenon.module.modules.client;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.ModeSetting;

import java.awt.Color;

public final class Themes extends Module {

    public static Themes INSTANCE;

    private final ModeSetting theme = new ModeSetting("Theme", "Xenon Green",
            "Xenon Green",
            "Sunset Orange",
            "Crimson Red",
            "Ocean Blue",
            "Royal Purple",
            "Hot Pink",
            "Cyber Cyan",
            "Sunshine Yellow",
            "Mint Fresh",
            "Midnight Gray");

    public Themes() {
        super("Themes", Category.CLIENT);
        addSetting(theme);
        INSTANCE = this;
    }

    public static Color getAccent() {
        if (INSTANCE == null || !INSTANCE.isEnabled()) return null;
        return switch (INSTANCE.theme.getValue()) {
            case "Sunset Orange"    -> new Color(255, 140, 50);
            case "Crimson Red"      -> new Color(229, 75, 75);
            case "Ocean Blue"       -> new Color(72, 160, 255);
            case "Royal Purple"     -> new Color(170, 110, 240);
            case "Hot Pink"         -> new Color(255, 100, 180);
            case "Cyber Cyan"       -> new Color(80, 220, 230);
            case "Sunshine Yellow"  -> new Color(250, 210, 80);
            case "Mint Fresh"       -> new Color(120, 230, 180);
            case "Midnight Gray"    -> new Color(160, 170, 185);
            case "Xenon Green"      -> new Color(107, 211, 165);
            default                 -> null;
        };
    }
}

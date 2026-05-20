package com.xenon.module.modules.misc;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.Setting;

public final class SwingSpeed extends Module {

    public static SwingSpeed instance;

    private final Setting<Float> swingSpeed = new Setting<>("Swing Speed", 1.0f, 0.1f, 2.0f);

    public SwingSpeed() {
        super("SwingSpeed", Category.MISC);
        instance = this;
        addSetting(swingSpeed);
    }

    public float getSwingSpeed() {
        float value = swingSpeed.getValue() == null ? 1.0f : swingSpeed.getValue();
        if (value < 0.1f) {
            return 0.1f;
        }
        return Math.min(value, 2.0f);
    }
}

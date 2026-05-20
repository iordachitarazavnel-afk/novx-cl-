package com.xenon.module.modules.client;

import com.xenon.gui.CloudConfigScreen;
import com.xenon.module.Category;
import com.xenon.module.Module;

public final class CloudConfigs extends Module {

    public CloudConfigs() {
        super("Cloud Configs", Category.CLIENT);
    }

    @Override
    public void onEnable() {
        if (mc == null) return;
        mc.execute(() -> mc.setScreen(new CloudConfigScreen(this)));
    }

    @Override
    public void onDisable() {
        if (mc != null && mc.currentScreen instanceof CloudConfigScreen) {
            mc.execute(() -> mc.setScreen(null));
        }
    }
}

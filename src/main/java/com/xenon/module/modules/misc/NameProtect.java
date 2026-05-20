package com.xenon.module.modules.misc;

import com.xenon.module.Category;
import com.xenon.module.Module;

import com.xenon.setting.Setting;

public class NameProtect extends Module {

    public final Setting<String> fakeName = new Setting<>("FakeName", "Player");

    public static NameProtect instance;

    public NameProtect() {
        super("NameProtect", Category.MISC);
        instance = this;
        addSetting(fakeName);
    }

    public String getFakeName() {
        return this.fakeName.getValue();
    }
}

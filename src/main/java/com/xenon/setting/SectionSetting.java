package com.xenon.setting;

/**
 * Non-interactive label row used to group settings in the ClickGUI.
 */
public final class SectionSetting extends Setting<String> {

    public SectionSetting(String name) {
        super(name, name);
    }
}

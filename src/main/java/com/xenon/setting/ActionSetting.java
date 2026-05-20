package com.xenon.setting;

public final class ActionSetting extends Setting<String> {
    private final Runnable action;

    public ActionSetting(String name, String label, Runnable action) {
        super(name, label);
        this.action = action;
    }

    public void trigger() {
        if (action != null) {
            action.run();
        }
    }
}

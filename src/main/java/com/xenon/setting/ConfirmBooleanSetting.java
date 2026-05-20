package com.xenon.setting;

public final class ConfirmBooleanSetting extends Setting<Boolean> {
    private final String confirmTitle;
    private final String confirmMessage;

    public ConfirmBooleanSetting(String name, boolean value, String confirmTitle, String confirmMessage) {
        super(name, value);
        this.confirmTitle = confirmTitle;
        this.confirmMessage = confirmMessage;
    }

    public String getConfirmTitle() {
        return confirmTitle;
    }

    public String getConfirmMessage() {
        return confirmMessage;
    }
}

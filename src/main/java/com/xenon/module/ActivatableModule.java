package com.xenon.module;

public abstract class ActivatableModule extends Module {
    private int activationKey = 0;
    public boolean wasActivationKeyPressed = false;

    public ActivatableModule(String name, Category category) {
        super(name, category);
    }

    public void onActivationKeyPressed() {
        toggle();
    }

    public int getActivationKey() {
        return activationKey;
    }

    public void setActivationKey(int activationKey) {
        this.activationKey = activationKey;
        ModuleManager.INSTANCE.saveConfig();
    }

    void applyActivationKey(int activationKey) {
        this.activationKey = activationKey;
    }
}


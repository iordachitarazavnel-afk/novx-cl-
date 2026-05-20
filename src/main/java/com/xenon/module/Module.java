package com.xenon.module;

import com.xenon.setting.Setting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import java.util.ArrayList;
import java.util.List;

public abstract class Module {
    private final String name;
    private final Category category;
    private boolean enabled;
    private int bind = 0;
    private boolean expanded = false;
    public boolean wasBindPressed = false;
    private final List<Setting<?>> settings = new ArrayList<>();
    protected static final MinecraftClient mc = MinecraftClient.getInstance();

    public Module(String name, Category category) {
        this.name = name;
        this.category = category;
        this.enabled = false;
    }

    public void addSetting(Setting<?> setting) {
        settings.add(setting);
    }

    public List<Setting<?>> getSettings() {
        return settings;
    }

    public String getName() {
        return name;
    }

    public Category getCategory() {
        return category;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            onEnable();
        } else {
            onDisable();
        }
        com.xenon.module.ModuleManager.INSTANCE.saveConfig();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void toggle() {
        setEnabled(!enabled);
        com.xenon.gui.notification.NotificationManager.INSTANCE.pushToggle(name, enabled, category.getIcon());
    }

    /**
     * Called when the user's bound key is pressed.
     * Default behavior matches the old system: toggle enabled state.
     */
    public void onBindPressed() {
        toggle();
    }

    public int getBind() {
        return bind;
    }

    public void setBind(int bind) {
        this.bind = bind;
        com.xenon.module.ModuleManager.INSTANCE.saveConfig();
    }

    void applyBind(int bind) {
        this.bind = bind;
    }

    void applyEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean expanded) {
        this.expanded = expanded;
    }

    public void onEnable() {}
    public void onDisable() {}
    public void onTick() {}
    public void onRender(MatrixStack matrices, float tickDelta) {}
    public void onPacketReceive(net.minecraft.network.packet.Packet<?> packet) {}
    public boolean onPacketSend(net.minecraft.network.packet.Packet<?> packet) { return false; }
}

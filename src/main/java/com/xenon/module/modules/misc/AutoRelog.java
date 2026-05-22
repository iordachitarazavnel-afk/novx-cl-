package com.xenon.module.modules.misc;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.Setting;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.text.Text;

public class AutoRelog extends Module {

    private final Setting<Integer> minHeight = new Setting<>("Relog Height", -64, -128, 320);

    private int loginTicksDelay = 0;
    private boolean wasInWorld = false;
    private boolean triggered = false;

    public AutoRelog() {
        super("Auto Relog", Category.MISC);
        addSetting(minHeight);
    }

    @Override
    public void onEnable() {
        loginTicksDelay = 200;
        wasInWorld = mc.world != null;
        triggered = false;
    }

    @Override
    public void onDisable() {
        wasInWorld = false;
        triggered = false;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) {
            wasInWorld = false;
            return;
        }

        if (!wasInWorld) {
            loginTicksDelay = 200;
            wasInWorld = true;
            triggered = false;
        }

        if (loginTicksDelay > 0) {
            loginTicksDelay--;
            return;
        }

        if (!triggered && mc.player.getY() < minHeight.getValue()) {
            triggered = true;
            triggerDisconnect();
        }
    }

    private void triggerDisconnect() {
        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().getConnection().disconnect(
                Text.literal("AutoRelog: Disconnected")
            );
        }
    }
}

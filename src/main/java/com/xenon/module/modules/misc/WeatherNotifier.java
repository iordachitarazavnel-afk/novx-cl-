package com.xenon.module.modules.misc;

import com.xenon.gui.notification.NotificationManager;
import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.ModeSetting;
import com.xenon.setting.Setting;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

public final class WeatherNotifier extends Module {

    private final ModeSetting notificationMode = new ModeSetting("Notification Mode", "Both", "Chat", "Toast", "Both");
    private final Setting<Boolean> notifyThunder = new Setting<>("Notify Thunder", true);

    private Boolean wasRaining = null;
    private Boolean wasThundering = null;

    public WeatherNotifier() {
        super("WeatherNotifier", Category.MISC);
        addSetting(notificationMode);
        addSetting(notifyThunder);
    }

    @Override
    public void onEnable() {
        wasRaining = null;
        wasThundering = null;
    }

    @Override
    public void onDisable() {
        wasRaining = null;
        wasThundering = null;
    }

    @Override
    public void onTick() {
        if (mc.world == null || mc.player == null) return;

        boolean raining = mc.world.isRaining();
        boolean thundering = mc.world.isThundering();

        if (wasRaining == null) {
            wasRaining = raining;
            wasThundering = thundering;
            return;
        }

        if (raining && !wasRaining) {
            notify("The rain started.", "Rain Started", 0xFF5AA9E6);
        } else if (!raining && wasRaining) {
            notify("The rain stopped.", "Rain Stopped", 0xFFFACC15);
        }

        if (notifyThunder.getValue()) {
            if (thundering && !wasThundering) {
                notify("A thunderstorm started.", "Thunder Started", 0xFFB58CFF);
            } else if (!thundering && wasThundering) {
                notify("The thunderstorm ended.", "Thunder Ended", 0xFFFACC15);
            }
        }

        wasRaining = raining;
        wasThundering = thundering;
    }

    private void notify(String chatMessage, String toastTitle, int accent) {
        String mode = notificationMode.getValue();
        boolean toChat = "Chat".equalsIgnoreCase(mode) || "Both".equalsIgnoreCase(mode);
        boolean toToast = "Toast".equalsIgnoreCase(mode) || "Both".equalsIgnoreCase(mode);

        if (toChat) {
            try {
                mc.inGameHud.getChatHud().addMessage(Text.literal("[WeatherNotifier] " + chatMessage));
            } catch (Throwable ignored) {}
        }
        if (toToast) {
            NotificationManager.INSTANCE.push("WeatherNotifier", toastTitle, ItemStack.EMPTY, accent);
        }
    }
}

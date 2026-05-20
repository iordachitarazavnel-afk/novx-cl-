package com.xenon.module.modules.misc;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.Setting;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.text.Text;

public final class HomeSetter extends Module {

    private final Setting<Boolean> chatFeedback = new Setting<>("Chat Feedback", true);
    private final Setting<Float> homeSlot = new Setting<>("Home Slot", 1.0f, 1.0f, 5.0f);

    private volatile boolean running = false;

    public HomeSetter() {
        super("HomeSetter", Category.MISC);
        this.addSetting(chatFeedback);
        this.addSetting(homeSlot);
    }

    @Override
    public void onEnable() {
        super.onEnable();
        if (running) return;
        if (mc == null || mc.player == null || mc.world == null) {
            this.toggle();
            return;
        }

        running = true;
        final int slot = Math.round(homeSlot.getValue());
        final int wait = 750;

        mc.execute(() -> {
            sendServerCommand("/delhome " + slot);
            new Thread(() -> {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ignored) {}
                mc.execute(() -> {
                    sendServerCommand("/sethome " + slot);
                    if (chatFeedback.getValue()) {
                        try {
                            mc.inGameHud.getChatHud().addMessage(
                                    Text.literal("§aHome " + slot + " deleted and set successfully!"));
                        } catch (Exception ignored) {}
                    }
                    running = false;
                    this.toggle();
                });
            }, "HomeSetter-DelayThread").start();
        });
    }

    @Override
    public void onDisable() {
        super.onDisable();
        running = false;
    }

    private void sendServerCommand(String command) {
        if (mc == null) return;
        ClientPlayNetworkHandler nh = null;
        try {
            if (mc.player != null) nh = mc.player.networkHandler;
        } catch (Throwable ignored) {}
        if (nh == null) {
            try { nh = mc.getNetworkHandler(); } catch (Throwable ignored) {}
        }
        if (nh == null) return;

        String cmdNoSlash = command.startsWith("/") ? command.substring(1) : command;
        try {
            nh.sendChatCommand(cmdNoSlash);
            return;
        } catch (Throwable ignored) {}
        try {
            nh.sendChatMessage(command);
        } catch (Throwable ignored) {}
    }
}
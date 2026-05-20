package com.xenon;

import com.xenon.gui.notification.NotificationManager;
import com.xenon.module.ModuleManager;
import com.xenon.module.modules.client.Hud;
import com.xenon.module.modules.client.SpotifyHud;
import com.xenon.module.modules.misc.NameTags;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class XenonClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("Xenon");
    private static KeyBinding rightShiftKey;

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initialisiere Xenon Client (Yarn Mappings)");

        ModuleManager.INSTANCE.init();

        net.minecraft.client.option.KeyBinding.Category xenonCategory = new net.minecraft.client.option.KeyBinding.Category(net.minecraft.util.Identifier.of("xenon", "category"));

        rightShiftKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.xenon.toggle_menu",
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                xenonCategory
        ));

        HudRenderCallback.EVENT.register((context, tickCounter) -> {
            NameTags.renderHud(context, tickCounter.getTickProgress(false));
            Hud.renderHud(context);
            SpotifyHud.renderHud(context);
            NotificationManager.INSTANCE.render(context);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ModuleManager.INSTANCE.onTick();

            while (rightShiftKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new com.xenon.gui.ClickGUI());
                }
            }

            if (client.currentScreen == null && client.getWindow() != null) {
                for (com.xenon.module.Module m : ModuleManager.INSTANCE.getModules()) {
                    int bind = m.getBind();
                    com.xenon.module.ActivatableModule activatable = m instanceof com.xenon.module.ActivatableModule a ? a : null;
                    int activationKey = activatable != null ? activatable.getActivationKey() : 0;
                    if (bind != 0) {
                        try {
                            boolean pressed = org.lwjgl.glfw.GLFW.glfwGetKey(client.getWindow().getHandle(), bind) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
                            if (pressed && !m.wasBindPressed) {
                                // If activation key matches bind key, avoid toggling twice.
                                if (activationKey != bind) {
                                    m.onBindPressed();
                                }
                            }
                            m.wasBindPressed = pressed;
                        } catch (Exception e) {}
                    }

                    if (activatable != null && activationKey != 0) {
                        try {
                            boolean pressed = org.lwjgl.glfw.GLFW.glfwGetKey(client.getWindow().getHandle(), activationKey) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
                            if (pressed && !activatable.wasActivationKeyPressed) {
                                activatable.onActivationKeyPressed();
                            }
                            activatable.wasActivationKeyPressed = pressed;
                        } catch (Exception e) {}
                    }
                }
            }
        });
    }
}

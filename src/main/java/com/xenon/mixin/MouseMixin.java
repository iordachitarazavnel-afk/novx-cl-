package com.xenon.mixin;

import com.xenon.module.modules.render.Freecam;
import com.xenon.gui.HudEditor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.ChatScreen;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public class MouseMixin {

    private static double toScaledX(MinecraftClient mc, double rawX) {
        if (mc == null || mc.getWindow() == null) return rawX;
        double w = mc.getWindow().getWidth();
        if (w <= 0.0) return rawX;
        return rawX * (mc.getWindow().getScaledWidth() / w);
    }

    private static double toScaledY(MinecraftClient mc, double rawY) {
        if (mc == null || mc.getWindow() == null) return rawY;
        double h = mc.getWindow().getHeight();
        if (h <= 0.0) return rawY;
        return rawY * (mc.getWindow().getScaledHeight() / h);
    }

    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true, require = 0)
    private void xenon$hudEditorMouseButtonClick(long window, @Coerce Object click, int actionOrMods, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || !(mc.currentScreen instanceof ChatScreen)) return;
        if (!(click instanceof Click c)) return;

        if (actionOrMods == 1) {
            if (HudEditor.INSTANCE.onMouseClick(c.x(), c.y(), c.button())) {
                ci.cancel();
            }
        } else if (actionOrMods == 0) {
            HudEditor.INSTANCE.onMouseRelease();
        }
    }

    // HUD drag logic only — crash fix is handled by MouseCrashFixMixin
    @Inject(method = "onCursorPos", at = @At("HEAD"), require = 0)
    private void xenon$hudEditorCursorPos(long window, double x, double y, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return;
        if (!(mc.currentScreen instanceof ChatScreen)) return;
        if (!HudEditor.INSTANCE.isDragging()) return;

        if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            HudEditor.INSTANCE.onMouseRelease();
            return;
        }

        HudEditor.INSTANCE.onMouseDrag(toScaledX(mc, x), toScaledY(mc, y));
    }

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void xenon$useScrollForFreecamSpeed(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (Freecam.instance == null || !Freecam.instance.isEnabled()) return;
        if (MinecraftClient.getInstance().currentScreen != null) return;

        double scrollAmount = vertical != 0.0 ? vertical : horizontal;
        if (scrollAmount == 0.0) return;

        Freecam.instance.adjustSpeed(scrollAmount);
        ci.cancel();
    }

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void xenon$hudEditorMouseScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || !(mc.currentScreen instanceof ChatScreen)) return;

        double amount = vertical != 0.0 ? vertical : horizontal;
        if (amount == 0.0) return;

        double sx = toScaledX(mc, mc.mouse.getX());
        double sy = toScaledY(mc, mc.mouse.getY());
        if (HudEditor.INSTANCE.onMouseScroll(sx, sy, amount)) {
            ci.cancel();
        }
    }
}
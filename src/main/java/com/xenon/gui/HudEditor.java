package com.xenon.gui;

import com.xenon.module.modules.client.Hud;
import com.xenon.module.modules.client.XenonPlus;
import com.xenon.utils.renderer.RenderUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;

public final class HudEditor {

    public static final HudEditor INSTANCE = new HudEditor();
    private HudEditor() {}

    private String dragging = null;
    private int grabOffsetX, grabOffsetY;
    private boolean isDragging = false;

    public boolean onMouseClick(double mouseX, double mouseY, int button) {
        if (button != 0) { dragging = null; isDragging = false; return false; }
        for (Hud.HudElement el : Hud.HudElement.values()) {
            if (!Hud.isElementVisible(el)) continue;
            int[] pos = handlePos(el);
            int w = handleW(el);
            int h = handleH(el);
            if (mouseX >= pos[0] && mouseX <= pos[0] + w
                    && mouseY >= pos[1] && mouseY <= pos[1] + h) {
                dragging = el.name();
                // Use pos (from handlePos) so hit-detection and grab offset share the same coordinate source
                grabOffsetX = (int) (mouseX - pos[0]);
                grabOffsetY = (int) (mouseY - pos[1]);
                isDragging = true;
                return true;
            }
        }
        dragging = null;
        isDragging = false;
        return false;
    }

    public boolean isDragging() { return dragging != null && isDragging; }

    public void onMouseDrag(double mouseX, double mouseY) {
        if (dragging == null || !isDragging) return;

        // Safety: never keep dragging unless LMB is currently held down.
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.getWindow() != null) {
            long handle = mc.getWindow().getHandle();
            if (handle != 0L && GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
                onMouseRelease();
                return;
            }
        }
        try {
            Hud.HudElement el = Hud.HudElement.valueOf(dragging);

            int newLeft = (int) Math.round(mouseX - grabOffsetX);
            int newTop = (int) Math.round(mouseY - grabOffsetY);

            // Clamp so the HUD editor outlines cannot leave the screen.
            if (mc != null && mc.getWindow() != null) {
                int sw = mc.getWindow().getScaledWidth();
                int sh = mc.getWindow().getScaledHeight();
                int w = handleW(el);
                int h = handleH(el);
                if (w < 1) w = 1;
                if (h < 1) h = 1;
                if (sw > 0) {
                    int maxX = Math.max(0, sw - w);
                    if (newLeft < 0) newLeft = 0;
                    else if (newLeft > maxX) newLeft = maxX;
                }
                if (sh > 0) {
                    int maxY = Math.max(0, sh - h);
                    if (newTop < 0) newTop = 0;
                    else if (newTop > maxY) newTop = maxY;
                }
            }

            // Module list is stored as a right-edge anchor in Hud.renderHud.
            if (el == Hud.HudElement.MODULE_LIST) {
                Hud.setElementPos(el, newLeft + handleW(el), newTop);
            } else {
                Hud.setElementPos(el, newLeft, newTop);
            }
        } catch (Exception ignored) {}
    }

    public void onMouseRelease() { dragging = null; isDragging = false; }

    public boolean onMouseScroll(double mouseX, double mouseY, double amount) {
        if (amount == 0.0) return false;
        if (!Hud.isElementVisible(Hud.HudElement.SPOTIFY_HUD)) return false;
        int[] pos = Hud.getElementPos(Hud.HudElement.SPOTIFY_HUD);
        int w = com.xenon.module.modules.client.SpotifyHud.getCardW();
        int h = com.xenon.module.modules.client.SpotifyHud.getCardH();
        if (mouseX < pos[0] || mouseX > pos[0] + w || mouseY < pos[1] || mouseY > pos[1] + h) return false;
        float delta = (float) (amount * 0.08);
        com.xenon.module.modules.client.SpotifyHud.setScale(com.xenon.module.modules.client.SpotifyHud.getScale() + delta);
        return true;
    }

    private static int[] handlePos(Hud.HudElement el) {
        if (el == Hud.HudElement.MODULE_LIST) {
            int[] b = Hud.getModuleListBounds();
            return new int[]{ b[0], b[1] };
        }
        int[] b = Hud.getElementBounds(el);
        return new int[]{ b[0], b[1] };
    }

    private static int handleW(Hud.HudElement el) {
        if (el == Hud.HudElement.MODULE_LIST) return Hud.getModuleListBounds()[2];
        if (el == Hud.HudElement.SPOTIFY_HUD) return com.xenon.module.modules.client.SpotifyHud.getCardW();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return 80;
        return Hud.getElementBounds(el)[2];
    }

    private static int handleH(Hud.HudElement el) {
        if (el == Hud.HudElement.MODULE_LIST) return Hud.getModuleListBounds()[3];
        if (el == Hud.HudElement.SPOTIFY_HUD) return com.xenon.module.modules.client.SpotifyHud.getCardH();
        return Hud.getElementBounds(el)[3];
    }

    public void render(DrawContext context, int mouseX, int mouseY) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;

        int accent = XenonPlus.getAccentARGB();

        for (Hud.HudElement el : Hud.HudElement.values()) {
            if (!Hud.isElementVisible(el)) continue;
            int[] pos = handlePos(el);
            int w = handleW(el);
            int h = handleH(el);
            boolean active = el.name().equals(dragging);

            if (el == Hud.HudElement.MODULE_LIST) {
                var tr = mc.textRenderer;
                java.util.List<com.xenon.module.Module> enabled = new java.util.ArrayList<>();
                for (com.xenon.module.Module m : com.xenon.module.ModuleManager.INSTANCE.getModules()) {
                    if (m.isEnabled() && m.getCategory() != com.xenon.module.Category.CLIENT) enabled.add(m);
                }
                if (enabled.isEmpty()) {
                    if (active) {
                        RenderUtil.drawOutline(context, pos[0], pos[1], w, h, 6f, 1f, accent, false);
                    }
                    continue;
                }
                enabled.sort(java.util.Comparator.comparingInt((com.xenon.module.Module m) -> tr.getWidth(m.getName())).reversed());
                int[] elPos = Hud.getElementPos(Hud.HudElement.MODULE_LIST);
                int ey = elPos[1];
                for (com.xenon.module.Module m : enabled) {
                    String label = m.getName();
                    int bw = tr.getWidth(label) + 14;
                    int bh = 14;
                    int bx = elPos[0] - bw;
                    RenderUtil.drawOutline(context, bx, ey, bw, bh, 6f, 1f, accent, false);
                    ey += bh + 4;
                }
            } else if (el == Hud.HudElement.POTION_EFFECTS) {
                var tr = mc.textRenderer;
                java.util.List<net.minecraft.entity.effect.StatusEffectInstance> effects = new java.util.ArrayList<>(mc.player.getStatusEffects());
                if (!effects.isEmpty()) {
                    effects.sort(java.util.Comparator.comparingInt(e -> tr.getWidth(com.xenon.module.modules.client.Hud.getEffectName(e))));
                    int[] elPos = Hud.getElementPos(Hud.HudElement.POTION_EFFECTS);
                    int by = elPos[1];
                    for (net.minecraft.entity.effect.StatusEffectInstance eff : effects) {
                        String name = com.xenon.module.modules.client.Hud.getEffectName(eff);
                        int bw = tr.getWidth(name) + 14;
                        int bh = 14;
                        RenderUtil.drawOutline(context, elPos[0], by, bw, bh, 5f, 1f, accent, false);
                        by += bh + 3;
                    }
                }
            } else {
                float radius = el == Hud.HudElement.WATERMARK ? 6f : 5f;
                if (el == Hud.HudElement.SPOTIFY_HUD) radius = 2f * com.xenon.module.modules.client.SpotifyHud.getScale();
                RenderUtil.drawOutline(context, pos[0], pos[1], w, h, radius, 1f, accent, false);
            }
        }
    }
}

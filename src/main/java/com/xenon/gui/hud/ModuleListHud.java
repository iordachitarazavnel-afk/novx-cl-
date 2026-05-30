package com.xenon.gui.hud;

import com.xenon.module.Module;
import com.xenon.module.ModuleManager;
import com.xenon.utils.renderer.RenderUtil;
import com.xenon.gui.MenuScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ModuleListHud {

    public static final ModuleListHud INSTANCE = new ModuleListHud();

    private static final int PANEL_COLOR = 0xD9141C28;
    private static final int OUTLINE_COLOR = 0xCC304055;
    private static final int TEXT_COLOR = 0xFFF2F6FB;
    private static final int ACCENT_COLOR = 0xFF6BD3A5;
    private static final float RADIUS = 6.0f;
    private static final int TOP_OFFSET = 72;
    private ModuleListHud() {
    }

    public void render(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null || client.getDebugHud().shouldShowDebugHud()) {
            return;
        }
        if (client.currentScreen instanceof ClickGUI) {
            return;
        }

        TextRenderer textRenderer = client.textRenderer;
        List<Module> enabledModules = new ArrayList<>();
        for (Module module : ModuleManager.INSTANCE.getModules()) {
            if (module.isEnabled()) {
                enabledModules.add(module);
            }
        }

        if (enabledModules.isEmpty()) {
            return;
        }

        enabledModules.sort(Comparator.comparingInt((Module module) -> textRenderer.getWidth(module.getName())).reversed());

        int x = client.getWindow().getScaledWidth() - 10;
        int y = TOP_OFFSET;

        for (Module module : enabledModules) {
            String label = module.getName();
            int textWidth = textRenderer.getWidth(label);
            int boxWidth = textWidth + 14;
            int boxHeight = 14;
            int boxX = x - boxWidth;

            RenderUtil.drawRoundedRect(context, boxX, y, boxWidth, boxHeight, RADIUS, PANEL_COLOR, false);
            RenderUtil.drawOutline(context, boxX, y, boxWidth, boxHeight, RADIUS, 1.0f, OUTLINE_COLOR, false);
            RenderUtil.drawRoundedRect(context, boxX, y, 2.0f, boxHeight, 2.0f, ACCENT_COLOR, false);
            context.drawText(textRenderer, label, boxX + 6, y + 3, TEXT_COLOR, false);
            y += boxHeight + 4;
        }
    }
}

package com.xenon.gui.notification;

import com.xenon.utils.renderer.RenderUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class NotificationManager {

    public static final NotificationManager INSTANCE = new NotificationManager();

    private static final long DISPLAY_MS = 3500L;
    private static final long ANIMATION_MS = 180L;
    private static final int MAX_NOTIFICATIONS = 5;
    private static final float CARD_WIDTH = 160.0f;
    private static final float CARD_HEIGHT = 32.0f;
    private static final float CARD_GAP = 4.0f;

    private final List<NotificationEntry> notifications = new CopyOnWriteArrayList<>();

    private NotificationManager() {
    }

    public void pushToggle(String moduleName, boolean enabled) {
        pushToggle(moduleName, enabled, ItemStack.EMPTY);
    }

    public void pushToggle(String moduleName, boolean enabled, ItemStack icon) {
        push(
                moduleName,
                enabled ? "Turned On" : "Turned Off",
                icon,
                enabled ? com.xenon.module.modules.client.XenonPlus.getAccentARGB() : 0xFFE05060
        );
    }

    public void renderToasts(DrawContext context) {
        render(context);
    }

    public void push(String message, String details, ItemStack stack, int accentColor) {
        notifications.add(0, new NotificationEntry(
                message == null ? "" : message,
                details == null ? "" : details,
                stack == null ? ItemStack.EMPTY : stack.copy(),
                accentColor,
                Util.getMeasuringTimeMs()
        ));

        while (notifications.size() > MAX_NOTIFICATIONS) {
            notifications.remove(notifications.size() - 1);
        }
    }

    public void render(DrawContext context) {
        if (notifications.isEmpty()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer textRenderer = client.textRenderer;
        long now = Util.getMeasuringTimeMs();

        notifications.removeIf(entry -> entry.isExpired(now));
        if (notifications.isEmpty()) {
            return;
        }

        context.createNewRootLayer();

        float baseX = client.getWindow().getScaledWidth() - CARD_WIDTH - 6.0f;
        float baseY = client.getWindow().getScaledHeight() - CARD_HEIGHT - 6.0f;

        for (int index = 0; index < notifications.size(); index++) {
            NotificationEntry entry = notifications.get(index);
            float visibility = entry.getVisibility(now);
            if (visibility <= 0.0f) {
                continue;
            }

            float x = baseX + (1.0f - visibility) * (CARD_WIDTH + 8.0f);
            float y = baseY - index * (CARD_HEIGHT + CARD_GAP);
            int outerColor = withAlpha(0xFF555555, visibility);
            int innerColor = withAlpha(0xFF1E1E1E, visibility * 0.96f);
            int shadowColor = withAlpha(0xFF000000, visibility * 0.9f);
            int titleColor = withAlpha(0xFFFFFF55, visibility);
            int detailsColor = withAlpha(0xFFFFFFFF, visibility);
            int slotColor = withAlpha(0xFF101010, visibility);

            RenderUtil.drawRoundedRect(context, x + 1.0f, y + 1.0f, CARD_WIDTH, CARD_HEIGHT, 0.0f, shadowColor, false);
            RenderUtil.drawRoundedRect(context, x, y, CARD_WIDTH, CARD_HEIGHT, 0.0f, outerColor, false);
            RenderUtil.drawRoundedRect(context, x + 1.0f, y + 1.0f, CARD_WIDTH - 2.0f, CARD_HEIGHT - 2.0f, 0.0f, innerColor, false);
            RenderUtil.drawRoundedRect(context, x + 5.0f, y + 5.0f, 22.0f, 22.0f, 0.0f, slotColor, false);
            RenderUtil.drawOutline(context, x + 5.0f, y + 5.0f, 22.0f, 22.0f, 0.0f, 1.0f, withAlpha(0xFF383838, visibility), false);

            if (!entry.stack.isEmpty()) {
                context.drawItem(entry.stack, (int) x + 8, (int) y + 8);
            } else {
                String fallback = entry.message.isEmpty() ? "?" : entry.message.substring(0, 1).toUpperCase();
                context.drawText(textRenderer, fallback, (int) x + 13, (int) y + 12, titleColor, false);
            }

            int textX = (int) x + 36;
            int textWidth = Math.max(40, (int) CARD_WIDTH - 42);
            String message = textRenderer.trimToWidth(entry.message, textWidth);
            String details = textRenderer.trimToWidth(entry.details, textWidth);

            context.drawText(textRenderer, message, textX, (int) y + 6, titleColor, false);
            context.drawText(textRenderer, details, textX, (int) y + 17, detailsColor, false);
        }
    }

    private static int withAlpha(int color, float alphaScale) {
        int alpha = Math.max(0, Math.min(255, Math.round(((color >>> 24) & 0xFF) * alphaScale)));
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static final class NotificationEntry {
        private final String message;
        private final String details;
        private final ItemStack stack;
        private final int accentColor;
        private final long createdAt;

        private NotificationEntry(String message, String details, ItemStack stack, int accentColor, long createdAt) {
            this.message = message;
            this.details = details;
            this.stack = stack;
            this.accentColor = accentColor | 0xFF000000;
            this.createdAt = createdAt;
        }

        private boolean isExpired(long now) {
            return now - createdAt >= DISPLAY_MS;
        }

        private float getVisibility(long now) {
            long age = now - createdAt;
            if (age <= 0L) {
                return 0.0f;
            }
            if (age < ANIMATION_MS) {
                return easeOutCubic(age / (float) ANIMATION_MS);
            }

            long fadeStart = DISPLAY_MS - ANIMATION_MS;
            if (age > fadeStart) {
                float progress = 1.0f - ((age - fadeStart) / (float) ANIMATION_MS);
                return easeOutCubic(Math.max(0.0f, progress));
            }

            return 1.0f;
        }

        private float easeOutCubic(float value) {
            float inverse = 1.0f - Math.max(0.0f, Math.min(1.0f, value));
            return 1.0f - inverse * inverse * inverse;
        }
    }
}

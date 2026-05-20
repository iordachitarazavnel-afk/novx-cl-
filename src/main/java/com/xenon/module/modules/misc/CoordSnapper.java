package com.xenon.module.modules.misc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.xenon.XenonClient;
import com.xenon.gui.notification.NotificationManager;
import com.xenon.module.Category;
import com.xenon.module.ActivatableModule;
import com.xenon.setting.Setting;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class CoordSnapper extends ActivatableModule {
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(8);
    private static final int SUCCESS_COLOR = 0xFF55D4A2;
    private static final int ERROR_COLOR = 0xFFE26A6A;
    private static final int WARNING_COLOR = 0xFFF0B35A;
    private static final ItemStack NOTIFICATION_STACK = new ItemStack(Items.RECOVERY_COMPASS);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ROOT);
    private static final long TRIGGER_COOLDOWN_MS = 250L;
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Setting<String> webhookUrl = new Setting<>("Webhook", "") {
        @Override
        public boolean matchesName(String settingName) {
            return super.matchesName(settingName) || "Webhook URL".equalsIgnoreCase(settingName);
        }
    };
    private final Setting<Boolean> notification = new Setting<>("Notification", true);
    private volatile long lastTriggerAt;

    public CoordSnapper() {
        super("CoordSnapper", Category.MISC);
        addSetting(webhookUrl);
        addSetting(notification);
    }

    @Override
    public void onActivationKeyPressed() {
        if (!isEnabled() || mc.player == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastTriggerAt < TRIGGER_COOLDOWN_MS) {
            return;
        }
        lastTriggerAt = now;

        String webhook = normalizeWebhook(webhookUrl.getValue());
        if (!isValidWebhook(webhook)) {
            showNotification("Webhook invalid", "Set a Discord webhook URL.", WARNING_COLOR);
            return;
        }

        CoordSnapshot snapshot = captureSnapshot(webhook);
        CompletableFuture
                .runAsync(() -> sendWebhook(snapshot), Util.getIoWorkerExecutor().named("coordsnapper-send"))
                .whenComplete((ignored, throwable) -> mc.execute(() -> {
                    if (throwable != null) {
                        showNotification("Send failed", getRootMessage(throwable), ERROR_COLOR);
                        return;
                    }
                    showNotification("Coords sent", snapshot.coordsInline(), SUCCESS_COLOR);
                }));
    }

    private CoordSnapshot captureSnapshot(String webhook) {
        int blockX = mc.player.getBlockX();
        int blockY = mc.player.getBlockY();
        int blockZ = mc.player.getBlockZ();
        String playerName = mc.player.getName().getString();
        String serverIp = resolveServerIp();
        String time = TIME_FORMATTER.format(ZonedDateTime.now());
        String encodedName = encodeSkinName(playerName);
        String skinRenderUrl = "https://mc-heads.net/body/" + encodedName;
        return new CoordSnapshot(
                webhook,
                playerName,
                blockX,
                blockY,
                blockZ,
                serverIp,
                time,
                skinRenderUrl
        );
    }

    private void sendWebhook(CoordSnapshot snapshot) {
        JsonObject payload = new JsonObject();
        payload.addProperty("username", "CoordSnapper");

        JsonObject embed = new JsonObject();
        embed.addProperty("title", "CoordSnapper");
        embed.addProperty("color", 0x55D4A2);

        JsonArray fields = new JsonArray();
        fields.add(createField("Name", snapshot.playerName(), false));
        fields.add(createField("Coords", snapshot.coordsField(), false));
        fields.add(createField("IP", snapshot.serverIp(), true));
        fields.add(createField("Time", snapshot.time(), true));
        embed.add("fields", fields);

        JsonObject thumbnail = new JsonObject();
        thumbnail.addProperty("url", snapshot.skinRenderUrl());
        embed.add("thumbnail", thumbnail);

        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        payload.add("embeds", embeds);

        HttpRequest request = HttpRequest.newBuilder(buildWebhookUri(snapshot.webhook()))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "Xenon-CoordSnapper")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> response;
        try {
            response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception exception) {
            XenonClient.LOGGER.error("CoordSnapper webhook request failed", exception);
            throw new IllegalStateException("Webhook request failed", exception);
        }

        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            XenonClient.LOGGER.info("CoordSnapper sent coords for {}", snapshot.playerName());
            return;
        }

        String body = response.body();
        if (body != null && !body.isBlank()) {
            XenonClient.LOGGER.warn("CoordSnapper webhook rejected with status {} and body {}", status, trimSingleLine(body, 240));
            throw new IllegalStateException("HTTP " + status + ": " + trimSingleLine(body, 120));
        }
        XenonClient.LOGGER.warn("CoordSnapper webhook rejected with status {}", status);
        throw new IllegalStateException("HTTP " + status);
    }

    private JsonObject createField(String name, String value, boolean inline) {
        JsonObject field = new JsonObject();
        field.addProperty("name", name);
        field.addProperty("value", value == null || value.isBlank() ? "-" : value);
        field.addProperty("inline", inline);
        return field;
    }

    private String resolveServerIp() {
        ServerInfo serverInfo = mc.getCurrentServerEntry();
        if (serverInfo == null || serverInfo.address == null || serverInfo.address.isBlank()) {
            return "Singleplayer";
        }

        String host = normalizeHost(serverInfo.address);
        return host.isEmpty() ? "Singleplayer" : host;
    }

    private String normalizeHost(String address) {
        String normalized = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        int slashIndex = normalized.indexOf('/');
        if (slashIndex >= 0) {
            normalized = normalized.substring(0, slashIndex);
        }
        int colonIndex = normalized.indexOf(':');
        if (colonIndex >= 0) {
            normalized = normalized.substring(0, colonIndex);
        }
        return normalized;
    }

    private String normalizeWebhook(String value) {
        return value == null ? "" : value.trim();
    }

    private String encodeSkinName(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return "Steve";
        }
        return playerName.trim();
    }

    private URI buildWebhookUri(String webhook) {
        URI uri = URI.create(webhook);
        String query = uri.getQuery();
        if (query == null || query.isBlank()) {
            return URI.create(webhook + "?wait=true");
        }
        if (query.contains("wait=")) {
            return uri;
        }
        return URI.create(webhook + "&wait=true");
    }

    private boolean isValidWebhook(String webhook) {
        if (webhook.isEmpty()) {
            return false;
        }

        try {
            URI uri = URI.create(webhook);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String path = uri.getPath();
            return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    && host != null
                    && !host.isBlank()
                    && path != null
                    && path.contains("/api/webhooks/");
        } catch (Exception ignored) {
            return false;
        }
    }

    private void showNotification(String message, String details, int accentColor) {
        if (mc == null || !notification.getValue()) {
            return;
        }
        mc.execute(() -> NotificationManager.INSTANCE.push(message, details, NOTIFICATION_STACK, accentColor));
    }

    private String getRootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : trimSingleLine(message, 120);
    }

    private String trimSingleLine(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private record CoordSnapshot(
            String webhook,
            String playerName,
            int x,
            int y,
            int z,
            String serverIp,
            String time,
            String skinRenderUrl
    ) {
        private String coordsField() {
            return "X: " + x + " Y: " + y + " Z: " + z;
        }

        private String coordsInline() {
            return x + ", " + y + ", " + z;
        }
    }
}

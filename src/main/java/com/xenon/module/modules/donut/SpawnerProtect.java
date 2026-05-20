package com.xenon.module.modules.donut;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.xenon.XenonClient;
import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.module.modules.client.Friends;
import com.xenon.setting.Setting;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.block.Blocks;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class SpawnerProtect extends Module {
    private static final String SILK_TOUCH_REQUIRED = "Need a Silk Touch pickaxe in hotbar";
    private static final int SCAN_RADIUS = 32;
    private static final double MAX_BREAK_REACH = 5.0;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(8);
    private static final int SUCCESS_COLOR = 0x55D4A2;
    private static final int ERROR_COLOR = 0xE26A6A;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Setting<Integer> criticalDistance = new Setting<>("Critical Distance", 5, 1, 20);
    private final Setting<String> webhookUrl = new Setting<>("Webhook", "") {
        @Override
        public boolean matchesName(String settingName) {
            return super.matchesName(settingName) || "Webhook URL".equalsIgnoreCase(settingName);
        }
    };

    private BlockPos currentTarget;
    private boolean disconnectScheduled;
    private int minedSpawnerCount;

    public SpawnerProtect() {
        super("SpawnerProtect", Category.DONUT);
        addSetting(criticalDistance);
        addSetting(webhookUrl);
    }

    @Override
    public void onEnable() {
        resetRuntimeState();
        if (!hasSilkTouchPickaxeInHotbar()) {
            disconnectAndDisable(SILK_TOUCH_REQUIRED);
        }
    }

    @Override
    public void onDisable() {
        stopMining();
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            return;
        }

        if (disconnectScheduled) {
            return;
        }

        if (!hasSilkTouchPickaxeInHotbar()) {
            disconnectAndDisable(SILK_TOUCH_REQUIRED);
            return;
        }

        EnemyStatus enemyStatus = getEnemyStatus();
        if (enemyStatus.hasCriticalThreat()) {
            fireWebhookThenDisconnect(captureCriticalSnapshot(enemyStatus.threat(), enemyStatus.distance()));
            return;
        }

        if (!enemyStatus.hasAnyEnemy()) {
            stopMining();
            return;
        }

        int silkSlot = findSilkTouchPickaxeHotbarSlot();
        if (silkSlot == -1) {
            disconnectAndDisable(SILK_TOUCH_REQUIRED);
            return;
        }

        selectHotbarSlot(silkSlot);
        applySneak();

        if (currentTarget == null || !isSpawner(currentTarget) || !mc.player.canInteractWithBlockAt(currentTarget, MAX_BREAK_REACH)) {
            currentTarget = findClosestSpawner();
            if (currentTarget == null) {
                fireWebhookThenDisconnect(captureSuccessSnapshot());
                return;
            }
        }

        Direction side = getClosestFace(currentTarget);
        lookAtSpawner(currentTarget, side);
        mc.interactionManager.updateBlockBreakingProgress(currentTarget, side);
        mc.world.spawnBlockBreakingParticle(currentTarget, side);
        mc.player.swingHand(Hand.MAIN_HAND);

        if (!isSpawner(currentTarget)) {
            minedSpawnerCount++;
            currentTarget = null;
        }
    }

    private EnemyStatus getEnemyStatus() {
        double criticalDistanceSq = square(criticalDistance.getValue());
        boolean anyEnemy = false;
        boolean criticalThreat = false;
        PlayerEntity threat = null;
        double threatDistance = -1.0;

        for (PlayerEntity other : mc.world.getPlayers()) {
            if (other == mc.player || other.isSpectator() || other.isTeammate(mc.player)) {
                continue;
            }
            if (Friends.isSpawnerProtect() && Friends.isFriend(other.getName().getString())) {
                continue;
            }

            anyEnemy = true;
            double distanceSq = mc.player.squaredDistanceTo(other);
            if (distanceSq <= criticalDistanceSq) {
                criticalThreat = true;
                double distance = Math.sqrt(distanceSq);
                if (threat == null || distance < threatDistance) {
                    threat = other;
                    threatDistance = distance;
                }
                break;
            }
        }

        return new EnemyStatus(anyEnemy, criticalThreat, threat, threatDistance);
    }

    private BlockPos findClosestSpawner() {
        List<BlockPos> spawners = new ArrayList<>();
        BlockPos center = mc.player.getBlockPos();
        int radiusSq = SCAN_RADIUS * SCAN_RADIUS;

        for (int x = -SCAN_RADIUS; x <= SCAN_RADIUS; x++) {
            for (int y = -SCAN_RADIUS; y <= SCAN_RADIUS; y++) {
                for (int z = -SCAN_RADIUS; z <= SCAN_RADIUS; z++) {
                    int distSq = x * x + y * y + z * z;
                    if (distSq > radiusSq) {
                        continue;
                    }

                    BlockPos pos = center.add(x, y, z);
                    if (isSpawner(pos) && mc.player.canInteractWithBlockAt(pos, MAX_BREAK_REACH)) {
                        spawners.add(pos.toImmutable());
                    }
                }
            }
        }

        return spawners.stream()
                .min(Comparator.comparingDouble(this::distanceSqTo))
                .orElse(null);
    }

    private boolean hasSilkTouchPickaxeInHotbar() {
        return findSilkTouchPickaxeHotbarSlot() != -1;
    }

    private int findSilkTouchPickaxeHotbarSlot() {
        for (int slot = 0; slot < 9; slot++) {
            if (isSilkTouchPickaxe(mc.player.getInventory().getStack(slot))) {
                return slot;
            }
        }
        return -1;
    }

    private boolean isSilkTouchPickaxe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        String itemPath = Registries.ITEM.getId(stack.getItem()).getPath();
        if (!itemPath.endsWith("_pickaxe")) {
            return false;
        }

        RegistryEntry<net.minecraft.enchantment.Enchantment> silkTouch = mc.world.getRegistryManager()
                .getOrThrow(RegistryKeys.ENCHANTMENT)
                .getEntry(mc.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT).get(Enchantments.SILK_TOUCH));
        return silkTouch != null && EnchantmentHelper.getLevel(silkTouch, stack) > 0;
    }

    private boolean isSpawner(BlockPos pos) {
        return mc.world != null && mc.world.getBlockState(pos).isOf(Blocks.SPAWNER);
    }

    private Direction getClosestFace(BlockPos pos) {
        Vec3d delta = mc.player.getEyePos().subtract(Vec3d.ofCenter(pos));
        return Direction.getFacing(delta.x, delta.y, delta.z);
    }

    private void lookAtSpawner(BlockPos pos, Direction direction) {
        Vec3d eyePos = mc.player.getEyePos();
        Vec3d target = Vec3d.ofCenter(pos);
        double dx = target.x - eyePos.x;
        double dy = target.y - eyePos.y;
        double dz = target.z - eyePos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontalDist)));

        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
        mc.crosshairTarget = new BlockHitResult(target, direction, pos, false);
    }

    private void selectHotbarSlot(int slot) {
        if (slot >= 0 && slot < 9 && mc.player.getInventory().getSelectedSlot() != slot) {
            mc.player.getInventory().setSelectedSlot(slot);
        }
    }

    private void applySneak() {
        mc.options.sneakKey.setPressed(true);
        mc.player.setSneaking(true);
    }

    private void stopMining() {
        currentTarget = null;
        if (mc.interactionManager != null && mc.interactionManager.isBreakingBlock()) {
            mc.interactionManager.cancelBlockBreaking();
        }
        if (mc.player != null) {
            mc.options.sneakKey.setPressed(false);
            mc.player.setSneaking(false);
        }
    }

    private void fireWebhookThenDisconnect(WebhookSnapshot snapshot) {
        stopMining();

        String webhook = normalizeWebhook(webhookUrl.getValue());
        if (!isValidWebhook(webhook)) {
            disconnectScheduled = false;
            disconnectAndDisable(snapshot.disconnectReason());
            return;
        }

        disconnectScheduled = true;
        CompletableFuture
                .runAsync(() -> sendWebhook(snapshot), Util.getIoWorkerExecutor().named("coordsnapper-send"))
                .whenComplete((ignored, throwable) -> mc.execute(() -> {
                    if (throwable != null) {
                        XenonClient.LOGGER.error("SpawnerProtect webhook failed", throwable);
                    }
                    disconnectScheduled = false;
                    disconnectAndDisable(snapshot.disconnectReason());
                }));
    }

    private WebhookSnapshot captureSuccessSnapshot() {
        String webhook = normalizeWebhook(webhookUrl.getValue());
        return new WebhookSnapshot(
                webhook,
                "[SpawnerProtect]",
                "All your spawners have been collected.",
                SUCCESS_COLOR,
                mc.player.getName().getString(),
                "",
                "",
                countSpawnersInInventory(),
                true,
                resolveServerIp(),
                TIME_FORMATTER.format(LocalTime.now()),
                "https://mc-heads.net/body/" + encodeSkinName(mc.player.getName().getString()),
                "SpawnerProtect finished"
        );
    }

    private WebhookSnapshot captureCriticalSnapshot(PlayerEntity threat, double distance) {
        String webhook = normalizeWebhook(webhookUrl.getValue());
        String threatName = threat != null ? threat.getName().getString() : "Unknown";
        return new WebhookSnapshot(
                webhook,
                "[SpawnerProtect]",
                threatName + " came too close.",
                ERROR_COLOR,
                mc.player.getName().getString(),
                threatName,
                String.format(Locale.ROOT, "%.1f", distance),
                countSpawnersInInventory(),
                false,
                resolveServerIp(),
                TIME_FORMATTER.format(LocalTime.now()),
                "https://mc-heads.net/body/" + encodeSkinName(threatName),
                "Enemy within critical distance"
        );
    }

    private void sendWebhook(WebhookSnapshot snapshot) {
        JsonObject payload = new JsonObject();
        payload.addProperty("username", "SpawnerProtect");

        JsonObject embed = new JsonObject();
        embed.addProperty("title", snapshot.title());
        embed.addProperty("description", snapshot.description());
        embed.addProperty("color", snapshot.color());

        JsonArray fields = new JsonArray();
        fields.add(createField("Player", snapshot.playerName(), false));
        fields.add(createField("Time", snapshot.time(), true));
        fields.add(createField("Server", snapshot.serverIp(), true));
        fields.add(createField("All spawners mined", snapshot.allMined() ? "✅ Yes" : "❌ No", false));
        fields.add(createField("Spawners in bag", snapshot.spawnersInInventory() + " spawners", false));
        if (!snapshot.threatName().isBlank()) {
            fields.add(createField("Threat", snapshot.threatName() + " (" + snapshot.distance() + " blocks)", false));
        }
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
            XenonClient.LOGGER.error("SpawnerProtect webhook request failed", exception);
            throw new IllegalStateException("Webhook request failed", exception);
        }

        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            XenonClient.LOGGER.info("SpawnerProtect webhook sent");
            return;
        }

        String body = response.body();
        if (body != null && !body.isBlank()) {
            XenonClient.LOGGER.warn("SpawnerProtect webhook rejected with status {} and body {}", status, trimSingleLine(body, 240));
            throw new IllegalStateException("HTTP " + status + ": " + trimSingleLine(body, 120));
        }
        XenonClient.LOGGER.warn("SpawnerProtect webhook rejected with status {}", status);
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

    private int countSpawnersInInventory() {
        int count = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(Blocks.SPAWNER.asItem())) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private void disconnectAndDisable(String reason) {
        if (mc.getNetworkHandler() != null && mc.getNetworkHandler().getConnection() != null) {
            mc.getNetworkHandler().getConnection().disconnect(Text.literal(reason));
        }
        setEnabled(false);
    }

    private double distanceSqTo(BlockPos pos) {
        return mc.player.squaredDistanceTo(Vec3d.ofCenter(pos));
    }

    private static double square(int value) {
        return (double) value * value;
    }

    private void resetRuntimeState() {
        currentTarget = null;
        disconnectScheduled = false;
        minedSpawnerCount = 0;
    }

    private record EnemyStatus(boolean hasAnyEnemy, boolean hasCriticalThreat, PlayerEntity threat, double distance) {
    }

    private record WebhookSnapshot(
            String webhook,
            String title,
            String description,
            int color,
            String playerName,
            String threatName,
            String distance,
            int spawnersInInventory,
            boolean allMined,
            String serverIp,
            String time,
            String skinRenderUrl,
            String disconnectReason
    ) {
    }
}

package com.xenon.module.modules.misc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.Setting;
import net.minecraft.client.texture.PlayerSkinTextureDownloader;
import net.minecraft.entity.player.PlayerSkinType;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.text.Text;
import net.minecraft.util.AssetInfo;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public final class SkinChanger extends Module {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final long INPUT_DEBOUNCE_MS = 600L;
    private static final String PRIMARY_LOOKUP_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String FALLBACK_LOOKUP_URL = "https://api.minecraftservices.com/minecraft/profile/lookup/name/";
    private static final String PROFILE_LOOKUP_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static volatile SkinTextures overrideSkin;
    private static volatile AssetInfo.TextureAsset overrideTextureAsset;

    private final Setting<String> playerName = new Setting<>("Player Name", "");
    private final AtomicInteger requestGeneration = new AtomicInteger();

    private PlayerSkinTextureDownloader skinDownloader;
    private String lastObservedName = "";
    private String lastRequestedName = "";
    private long lastNameEditAt;

    public SkinChanger() {
        super("SkinChanger", Category.MISC);
        this.addSetting(playerName);
    }

    @Override
    public void onEnable() {
        super.onEnable();

        lastObservedName = normalizeName(playerName.getValue());
        lastRequestedName = "";
        lastNameEditAt = System.currentTimeMillis();

        if (lastObservedName.isEmpty()) {
            clearOverride();
            return;
        }

        requestSkin(lastObservedName);
    }

    @Override
    public void onDisable() {
        super.onDisable();
        requestGeneration.incrementAndGet();
        lastRequestedName = "";
        clearOverride();
    }

    @Override
    public void onTick() {
        String currentName = normalizeName(playerName.getValue());
        if (!Objects.equals(currentName, lastObservedName)) {
            lastObservedName = currentName;
            lastNameEditAt = System.currentTimeMillis();
            return;
        }

        if (currentName.isEmpty()) {
            if (overrideSkin != null || overrideTextureAsset != null) {
                lastRequestedName = "";
                clearOverride();
            }
            return;
        }

        if (!Objects.equals(currentName, lastRequestedName)
                && System.currentTimeMillis() - lastNameEditAt >= INPUT_DEBOUNCE_MS) {
            requestSkin(currentName);
        }
    }

    public static SkinTextures getOverrideSkin(UUID playerUuid) {
        if (overrideSkin == null || playerUuid == null) {
            return null;
        }

        UUID localUuid = getLocalPlayerUuid();
        return localUuid != null && localUuid.equals(playerUuid) ? overrideSkin : null;
    }

    private void requestSkin(String name) {
        lastRequestedName = name;
        int generation = requestGeneration.incrementAndGet();

        CompletableFuture
                .supplyAsync(() -> lookupSkin(name), Util.getIoWorkerExecutor().named("skinchanger-lookup"))
                .thenCompose(result -> getSkinDownloader()
                        .downloadAndRegisterTexture(createTextureId(result), getCacheFile(result.uuid()), result.textureUrl(), true)
                        .thenApply(textureAsset -> new ResolvedSkin(result, textureAsset)))
                .whenComplete((resolvedSkin, throwable) -> mc.execute(() -> {
                    if (generation != requestGeneration.get() || !isEnabled()) {
                        if (resolvedSkin != null) {
                            destroyTexture(resolvedSkin.textureAsset());
                        }
                        return;
                    }

                    if (throwable != null) {
                        sendFeedback("Failed to apply skin for " + name + ": " + getRootMessage(throwable));
                        return;
                    }

                    applyOverride(resolvedSkin.textureAsset(), resolvedSkin.lookup().skinType());
                    sendFeedback("Applied skin from " + resolvedSkin.lookup().playerName() + ".");
                }));
    }

    private PlayerSkinTextureDownloader getSkinDownloader() {
        if (skinDownloader == null) {
            skinDownloader = new PlayerSkinTextureDownloader(mc.getNetworkProxy(), mc.getTextureManager(), mc::execute);
        }
        return skinDownloader;
    }

    private SkinLookup lookupSkin(String playerName) {
        try {
            UUID uuid = lookupUuid(playerName);
            TexturePayload payload = lookupTexturePayload(uuid);
            return new SkinLookup(playerName, uuid, payload.textureUrl(), payload.skinType());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Request interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    private UUID lookupUuid(String playerName) throws IOException, InterruptedException {
        JsonObject lookup = requestJson(PRIMARY_LOOKUP_URL + encodeName(playerName));
        if (lookup == null) {
            lookup = requestJson(FALLBACK_LOOKUP_URL + encodeName(playerName));
        }
        if (lookup == null || !lookup.has("id")) {
            throw new IOException("Player not found");
        }
        return parseUuid(lookup.get("id").getAsString());
    }

    private TexturePayload lookupTexturePayload(UUID uuid) throws IOException, InterruptedException {
        JsonObject profile = requestJson(PROFILE_LOOKUP_URL + uuid.toString().replace("-", ""));
        if (profile == null || !profile.has("properties")) {
            throw new IOException("Skin profile not found");
        }

        JsonArray properties = profile.getAsJsonArray("properties");
        for (JsonElement element : properties) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject property = element.getAsJsonObject();
            if (!"textures".equalsIgnoreCase(getString(property, "name")) || !property.has("value")) {
                continue;
            }

            String decoded = new String(Base64.getDecoder().decode(property.get("value").getAsString()), StandardCharsets.UTF_8);
            JsonObject textureRoot = JsonParser.parseString(decoded).getAsJsonObject();
            JsonObject textures = textureRoot.getAsJsonObject("textures");
            JsonObject skin = textures != null ? textures.getAsJsonObject("SKIN") : null;
            if (skin == null || !skin.has("url")) {
                break;
            }

            String modelName = null;
            JsonObject metadata = skin.getAsJsonObject("metadata");
            if (metadata != null && metadata.has("model")) {
                modelName = metadata.get("model").getAsString();
            }

            PlayerSkinType skinType = "slim".equalsIgnoreCase(modelName) ? PlayerSkinType.SLIM : PlayerSkinType.WIDE;
            return new TexturePayload(skin.get("url").getAsString(), skinType);
        }

        throw new IOException("No usable skin texture found");
    }

    private JsonObject requestJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "Xenon-SkinChanger")
                .GET()
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status == 404 || status == 204) {
            return null;
        }
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status);
        }

        String body = response.body();
        if (body == null || body.isBlank()) {
            return null;
        }
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private static synchronized void applyOverride(AssetInfo.TextureAsset textureAsset, PlayerSkinType skinType) {
        destroyTexture(overrideTextureAsset);
        overrideTextureAsset = textureAsset;
        overrideSkin = SkinTextures.create(textureAsset, null, null, skinType);
    }

    private static synchronized void clearOverride() {
        destroyTexture(overrideTextureAsset);
        overrideTextureAsset = null;
        overrideSkin = null;
    }

    private static void destroyTexture(AssetInfo.TextureAsset textureAsset) {
        if (textureAsset == null || mc == null) {
            return;
        }

        try {
            mc.getTextureManager().destroyTexture(textureAsset.texturePath());
        } catch (Throwable ignored) {
        }
        try {
            if (!textureAsset.id().equals(textureAsset.texturePath())) {
                mc.getTextureManager().destroyTexture(textureAsset.id());
            }
        } catch (Throwable ignored) {
        }
    }

    private void sendFeedback(String message) {
        if (mc == null || mc.inGameHud == null) {
            return;
        }
        try {
            mc.inGameHud.getChatHud().addMessage(Text.literal("[SkinChanger] " + message));
        } catch (Throwable ignored) {
        }
    }

    private Identifier createTextureId(SkinLookup lookup) {
        String safeName = lookup.playerName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        if (safeName.isEmpty()) {
            safeName = "player";
        }
        return Identifier.of("xenon", "skins/" + safeName + "_" + lookup.uuid().toString().replace("-", ""));
    }

    private Path getCacheFile(UUID uuid) {
        return mc.runDirectory.toPath()
                .resolve("xenon-cache")
                .resolve("skins")
                .resolve(uuid.toString().replace("-", "") + ".png");
    }

    private String encodeName(String playerName) {
        return URLEncoder.encode(playerName, StandardCharsets.UTF_8);
    }

    private static UUID parseUuid(String rawUuid) {
        String normalized = rawUuid.replace("-", "");
        return UUID.fromString(normalized.replaceFirst(
                "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                "$1-$2-$3-$4-$5"));
    }

    private static String normalizeName(String value) {
        return value == null ? "" : value.trim();
    }

    private static String getString(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsString() : "";
    }

    private static String getRootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static UUID getLocalPlayerUuid() {
        if (mc == null) {
            return null;
        }
        if (mc.player != null) {
            return mc.player.getUuid();
        }
        return mc.getSession() != null ? mc.getSession().getUuidOrNull() : null;
    }

    private record TexturePayload(String textureUrl, PlayerSkinType skinType) {
    }

    private record SkinLookup(String playerName, UUID uuid, String textureUrl, PlayerSkinType skinType) {
    }

    private record ResolvedSkin(SkinLookup lookup, AssetInfo.TextureAsset textureAsset) {
    }
}

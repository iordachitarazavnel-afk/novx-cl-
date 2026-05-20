package com.xenon.utils;

import com.xenon.module.Module;
import com.xenon.module.ModuleManager;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class ConfigStore {

    private static final String EXT = ".txt";
    private static final Pattern SAFE = Pattern.compile("[^A-Za-z0-9_\\- ]");

    private ConfigStore() {}

    public static Path configsDir() {
        Path dir = MinecraftClient.getInstance().runDirectory.toPath().resolve("xenon_configs");
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
        return dir;
    }

    private static Path liveConfig() {
        return MinecraftClient.getInstance().runDirectory.toPath().resolve("xenon_config.txt");
    }

    public static String sanitize(String name) {
        if (name == null) return "";
        return SAFE.matcher(name.trim()).replaceAll("_");
    }

    public static List<String> list() {
        List<String> out = new ArrayList<>();
        Path dir = configsDir();
        if (!Files.isDirectory(dir)) return out;
        try {
            Files.list(dir).forEach(p -> {
                String fn = p.getFileName().toString();
                if (fn.endsWith(EXT)) out.add(fn.substring(0, fn.length() - EXT.length()));
            });
        } catch (IOException ignored) {}
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public static boolean saveAs(String name) {
        String safe = sanitize(name);
        if (safe.isEmpty()) return false;
        ModuleManager.INSTANCE.saveConfig();
        Path src = liveConfig();
        Path dst = configsDir().resolve(safe + EXT);
        try {
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean load(String name) {
        Path src = configsDir().resolve(sanitize(name) + EXT);
        if (!Files.isRegularFile(src)) return false;
        return applyFromPath(src);
    }

    public static boolean delete(String name) {
        try {
            return Files.deleteIfExists(configsDir().resolve(sanitize(name) + EXT));
        } catch (IOException e) {
            return false;
        }
    }

    public static String generateShareCode() {
        try {
            ModuleManager.INSTANCE.saveConfig();
            byte[] data = Files.readAllBytes(liveConfig());
            return "XCFG-" + Base64.getUrlEncoder().withoutPadding().encodeToString(data);
        } catch (IOException e) {
            return null;
        }
    }

    public static boolean redeemShareCode(String code) {
        if (code == null) return false;
        String c = code.trim();
        if (c.startsWith("XCFG-")) c = c.substring(5);
        try {
            byte[] data = Base64.getUrlDecoder().decode(c);
            Path tmp = configsDir().resolve(".__shared_tmp" + EXT);
            Files.write(tmp, data);
            boolean ok = applyFromPath(tmp);
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            return ok;
        } catch (IllegalArgumentException | IOException e) {
            return false;
        }
    }

    private static boolean applyFromPath(Path src) {
        Map<String, Boolean> before = snapshotEnabled();
        try {
            Files.copy(src, liveConfig(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            return false;
        }
        ModuleManager.INSTANCE.loadConfig();
        Map<String, Boolean> after = snapshotEnabled();
        for (Module m : ModuleManager.INSTANCE.getModules()) {
            boolean was = before.getOrDefault(m.getName(), false);
            boolean now = after.getOrDefault(m.getName(), false);
            if (was == now) continue;
            try {
                if (now) m.onEnable();
                else m.onDisable();
            } catch (Throwable ignored) {}
        }
        ModuleManager.INSTANCE.saveConfig();
        return true;
    }

    private static Map<String, Boolean> snapshotEnabled() {
        Map<String, Boolean> map = new HashMap<>();
        for (Module m : ModuleManager.INSTANCE.getModules()) {
            map.put(m.getName(), m.isEnabled());
        }
        return map;
    }

    public static String readClipboard() {
        try {
            return MinecraftClient.getInstance().keyboard.getClipboard();
        } catch (Throwable e) {
            return "";
        }
    }

    public static void writeClipboard(String s) {
        try {
            MinecraftClient.getInstance().keyboard.setClipboard(s == null ? "" : s);
        } catch (Throwable ignored) {}
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}

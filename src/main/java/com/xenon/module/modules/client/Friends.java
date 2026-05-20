package com.xenon.module.modules.client;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.Setting;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class Friends extends Module {

    private static Friends INSTANCE;

    // Persisted as text for config compatibility; ClickGUI provides a proper list editor.
    // Accept both commas and newlines as separators.
    private final Setting<String>  nameList        = new Setting<>("Names", "");
    private final Setting<Boolean> antiTriggerbot  = new Setting<>("Anti Triggerbot", true);
    private final Setting<Boolean> espColor        = new Setting<>("ESP Color", true);
    private final Setting<Boolean> autoLog         = new Setting<>("Auto Log", true);
    private final Setting<Boolean> spawnerProtect  = new Setting<>("Spawner Protect", true);
    private final Setting<Color>   friendColor     = new Setting<>("Friend Color", new Color(0, 200, 255));

    public Friends() {
        super("Friends", Category.CLIENT);
        addSetting(nameList);
        addSetting(antiTriggerbot);
        addSetting(espColor);
        addSetting(autoLog);
        addSetting(spawnerProtect);
        addSetting(friendColor);
        INSTANCE = this;
    }

    public static boolean isFriend(String name) {
        if (INSTANCE == null || !INSTANCE.isEnabled() || name == null || name.isEmpty()) return false;
        String lower = name.trim().toLowerCase(Locale.ROOT);
        for (String n : parseNames(INSTANCE.nameList.getValue())) {
            if (n.equalsIgnoreCase(lower)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isAntiTriggerbot() {
        return INSTANCE != null && INSTANCE.antiTriggerbot.getValue();
    }

    public static boolean isEspColor() {
        return INSTANCE != null && INSTANCE.espColor.getValue();
    }

    public static boolean isAutoLog() {
        return INSTANCE != null && INSTANCE.autoLog.getValue();
    }

    public static boolean isSpawnerProtect() {
        return INSTANCE != null && INSTANCE.spawnerProtect.getValue();
    }

    public static Color getColor() {
        if (INSTANCE == null) return new Color(0, 200, 255);
        Color c = INSTANCE.friendColor.getValue();
        if (c == null) return new Color(0, 200, 255);
        // Fallback: if alpha is 0 (invisible), treat it as fully opaque for friend ESP visuals.
        if (c.getAlpha() == 0) {
            return new Color(c.getRed(), c.getGreen(), c.getBlue(), 255);
        }
        return c;
    }

    public static void refreshCache() { }

    public static List<String> getNames() {
        if (INSTANCE == null) return List.of();
        return parseNames(INSTANCE.nameList.getValue());
    }

    public static void setNames(List<String> names) {
        if (INSTANCE == null) return;
        INSTANCE.nameList.setValue(serializeNames(names));
    }

    private static List<String> parseNames(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String normalized = raw.replace('\n', ',').replace('\r', ',');
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String part : normalized.split(",")) {
            String t = part == null ? "" : part.trim();
            if (t.isEmpty()) continue;
            out.add(t.toLowerCase(Locale.ROOT));
        }
        return new ArrayList<>(out);
    }

    private static String serializeNames(List<String> names) {
        if (names == null || names.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            String t = name == null ? "" : name.trim();
            if (t.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(t);
        }
        return sb.toString();
    }
}

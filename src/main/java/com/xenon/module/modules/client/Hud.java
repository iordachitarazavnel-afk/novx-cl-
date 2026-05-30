package com.xenon.module.modules.client;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.module.ModuleManager;
import com.xenon.setting.Setting;
import com.xenon.utils.renderer.RenderUtil;
import com.xenon.gui.MenuScreen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.Registries;

import java.awt.Color;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Hud extends Module {

    private static Hud INSTANCE;

    private final Setting<Boolean> watermark    = new Setting<>("Watermark",     true);
    private final Setting<Boolean> coordinates  = new Setting<>("Coordinates",   true);
    private final Setting<Boolean> info         = new Setting<>("Info",          true);
    private final Setting<Boolean> moduleList   = new Setting<>("Module List",   true);
    private final Setting<Boolean> potionEffects= new Setting<>("Potion Effects",true);
    private final Setting<Boolean> armor        = new Setting<>("Armor",         true);
    private final Setting<Boolean> keybinds     = new Setting<>("Keybinds",      true);
    private final Setting<Boolean> notifications= new Setting<>("Notifications", true);

    public enum HudElement {
        WATERMARK    ("Watermark"),
        COORDINATES  ("Coordinates"),
        INFO         ("Info"),
        MODULE_LIST  ("Module List"),
        POTION_EFFECTS("Potion Effects"),
        ARMOR        ("Armor"),
        KEYBINDS     ("Keybinds"),
        SPOTIFY_HUD  ("Spotify HUD");

        public final String label;
        HudElement(String label) { this.label = label; }
    }

    private static final java.util.EnumMap<HudElement, int[]> positions = new java.util.EnumMap<>(HudElement.class);

    public static int[] getElementPos(HudElement el) {
        return positions.computeIfAbsent(el, k -> defaultPos(k));
    }

    public static void setElementPos(HudElement el, int x, int y) {
        positions.put(el, new int[]{x, y});
        ModuleManager.INSTANCE.onSettingChanged();
    }

    public static int getElementW(HudElement el) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return 100;
        return switch (el) {
            case WATERMARK     -> mc.textRenderer.getWidth("Hova +") + 20;
            case COORDINATES   -> mc.textRenderer.getWidth("XYZ: -00000.0 / -256.0 / -00000.0") + 14;
            case INFO          -> mc.textRenderer.getWidth("999 FPS | 999ms | 23:59:59") + 14;
            case MODULE_LIST   -> 120;
            case POTION_EFFECTS-> 110;
            case ARMOR         -> mc.textRenderer.getWidth(" 100%") + 22;
            case KEYBINDS      -> 120;
            case SPOTIFY_HUD   -> SpotifyHud.getCardW();
        };
    }

    public static int[] getElementBounds(HudElement el) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) {
            int[] pos = getElementPos(el);
            return new int[]{pos[0], pos[1], getElementW(el), 14};
        }
        var tr = mc.textRenderer;
        int[] pos = getElementPos(el);
        return switch (el) {
            case WATERMARK -> {
                int nameW = tr.getWidth("Xenon");
                int plusW = tr.getWidth(" +");
                int bw = nameW + plusW + 16;
                yield new int[]{pos[0], pos[1], bw, 16};
            }
            case COORDINATES -> {
                String coords = String.format("%.1f / %.1f / %.1f", mc.player.getX(), mc.player.getY(), mc.player.getZ());
                int bw = tr.getWidth(coords) + 14;
                yield new int[]{pos[0], pos[1], bw, 14};
            }
            case INFO -> {
                int fps = mc.getCurrentFps();
                int ping = 0;
                try {
                    if (mc.getNetworkHandler() != null) {
                        var entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
                        if (entry != null) ping = entry.getLatency();
                    }
                } catch (Exception ignored) {}
                String time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
                String infoText = fps + " FPS | " + ping + "ms | " + time;
                int bw = tr.getWidth(infoText) + 14;
                yield new int[]{pos[0], pos[1], bw, 14};
            }
            case MODULE_LIST -> getModuleListBounds();
            case POTION_EFFECTS -> {
                List<StatusEffectInstance> effects = new ArrayList<>(mc.player.getStatusEffects());
                if (effects.isEmpty()) {
                    yield new int[]{pos[0], pos[1], getElementW(el), 14};
                }
                int maxW = 0;
                for (StatusEffectInstance eff : effects) {
                    int w = tr.getWidth(getEffectName(eff)) + 14;
                    if (w > maxW) maxW = w;
                }
                int totalH = effects.size() * 14 + (effects.size() - 1) * 3;
                yield new int[]{pos[0], pos[1], Math.max(1, maxW), Math.max(1, totalH)};
            }
            case ARMOR -> {
                int maxTextW = 0;
                int rows = 0;
                net.minecraft.entity.EquipmentSlot[] slots = {
                        net.minecraft.entity.EquipmentSlot.HEAD,
                        net.minecraft.entity.EquipmentSlot.CHEST,
                        net.minecraft.entity.EquipmentSlot.LEGS,
                        net.minecraft.entity.EquipmentSlot.FEET
                };
                for (net.minecraft.entity.EquipmentSlot sl : slots) {
                    net.minecraft.item.ItemStack stack = mc.player.getEquippedStack(sl);
                    if (stack == null || stack.isEmpty()) continue;
                    rows++;
                    int pct;
                    if (stack.isDamageable() && stack.getMaxDamage() > 0) {
                        pct = (int) Math.round((1.0 - (double) stack.getDamage() / stack.getMaxDamage()) * 100.0);
                    } else {
                        pct = 100;
                    }
                    int w = tr.getWidth(pct + "%");
                    if (w > maxTextW) maxTextW = w;
                }
                if (rows == 0) yield new int[]{pos[0], pos[1], getElementW(el), 18};
                int bw = 22 + maxTextW + 6;
                int bh = rows * 18 + (rows - 1) * 2;
                yield new int[]{pos[0], pos[1], bw, bh};
            }
            case KEYBINDS -> {
                java.util.List<Module> bound = collectBoundModules();
                if (bound.isEmpty()) yield new int[]{pos[0], pos[1], getElementW(el), 14};
                int maxW = 0;
                for (Module m : bound) {
                    int w = tr.getWidth(formatBindLine(m)) + 14;
                    if (w > maxW) maxW = w;
                }
                int totalH = bound.size() * 14 + (bound.size() - 1) * 3;
                yield new int[]{pos[0], pos[1], Math.max(1, maxW), Math.max(1, totalH)};
            }
            case SPOTIFY_HUD -> new int[]{pos[0], pos[1], SpotifyHud.getCardW(), SpotifyHud.getCardH()};
        };
    }

    private static java.util.List<Module> collectBoundModules() {
        java.util.List<Module> bound = new ArrayList<>();
        for (Module m : ModuleManager.INSTANCE.getModules()) {
            if (m.getBind() != 0) bound.add(m);
        }
        bound.sort(Comparator.comparing(Module::getName));
        return bound;
    }

    private static String formatBindLine(Module m) {
        return m.getName() + " [" + com.xenon.gui.MenuScreen.getKeyDisplayNameStatic(m.getBind()) + "]";
    }

    public static boolean isElementVisible(HudElement el) {
        if (INSTANCE == null || !INSTANCE.isEnabled()) return false;
        return switch (el) {
            case WATERMARK      -> INSTANCE.watermark.getValue();
            case COORDINATES    -> INSTANCE.coordinates.getValue();
            case INFO           -> INSTANCE.info.getValue();
            case MODULE_LIST    -> INSTANCE.moduleList.getValue();
            case POTION_EFFECTS -> INSTANCE.potionEffects.getValue();
            case ARMOR          -> INSTANCE.armor.getValue();
            case KEYBINDS       -> INSTANCE.keybinds.getValue();
            case SPOTIFY_HUD    -> SpotifyHud.isActive();
        };
    }

    private static int[] defaultPos(HudElement el) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int W = mc != null ? mc.getWindow().getScaledWidth() : 800;
        return switch (el) {
            case WATERMARK      -> new int[]{8, 8};
            case COORDINATES    -> new int[]{8, 30};
            case INFO           -> new int[]{8, 48};
            case MODULE_LIST    -> new int[]{W - 130, 72};
            case POTION_EFFECTS -> new int[]{8, 66};
            case ARMOR          -> new int[]{W - 60, 200};
            case KEYBINDS       -> new int[]{8, 120};
            case SPOTIFY_HUD    -> new int[]{8, 50};
        };
    }

    public Hud() {
        super("Hud", Category.CLIENT);
        addSetting(watermark);
        addSetting(coordinates);
        addSetting(info);
        addSetting(moduleList);
        addSetting(potionEffects);
        addSetting(armor);
        addSetting(keybinds);
        addSetting(notifications);
        INSTANCE = this;
    }

    public static boolean showModuleList()    { return INSTANCE != null && INSTANCE.isEnabled() && INSTANCE.moduleList.getValue(); }
    public static boolean showNotifications() { return INSTANCE != null && INSTANCE.isEnabled() && INSTANCE.notifications.getValue(); }

    public static int[] getModuleListBounds() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return new int[]{0, 0, 120, 14};
        TextRenderer tr = mc.textRenderer;
        int[] pos = getElementPos(HudElement.MODULE_LIST);
        List<Module> enabled = new ArrayList<>();
        for (Module m : ModuleManager.INSTANCE.getModules()) {
            if (m.isEnabled() && m.getCategory() != Category.CLIENT) enabled.add(m);
        }
        if (enabled.isEmpty()) return new int[]{pos[0] - 120, pos[1], 120, 14};
        enabled.sort(Comparator.comparingInt((Module m) -> tr.getWidth(m.getName())).reversed());
        int widest = tr.getWidth(enabled.get(0).getName()) + 14;
        int totalH = enabled.size() * 14 + (enabled.size() - 1) * 4;
        return new int[]{pos[0] - widest, pos[1], widest, totalH};
    }

    public static void renderHud(DrawContext context) {
        if (INSTANCE == null || !INSTANCE.isEnabled()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.options == null) return;
        if (mc.currentScreen instanceof MenuScreen) return;
        if (mc.getDebugHud().shouldShowDebugHud()) return;

        TextRenderer tr = mc.textRenderer;
        int accent = XenonPlus.getAccentARGB();
        Color accentColor = XenonPlus.getAccentColor();
        int W = mc.getWindow().getScaledWidth();

        
        if (INSTANCE.watermark.getValue()) {
            int[] pos = getElementPos(HudElement.WATERMARK);
            int nameW = tr.getWidth("Xenon");
            int plusW = tr.getWidth(" +");
            int bw = nameW + plusW + 16; int bh = 16;
            RenderUtil.drawRoundedRect(context, pos[0], pos[1], bw, bh, 6f, 0xD9141C28, false);
            RenderUtil.drawOutline(context, pos[0], pos[1], bw, bh, 6f, 1f, 0xCC304055, false);
            context.drawText(tr, "Xenon", pos[0] + 8, pos[1] + 4, 0xFFF0F4F8, true);
            context.drawText(tr, " +", pos[0] + 8 + nameW, pos[1] + 4, accent, true);
        }

        
        if (INSTANCE.coordinates.getValue()) {
            int[] pos = getElementPos(HudElement.COORDINATES);
            String coords = String.format("%.1f / %.1f / %.1f",
                    mc.player.getX(), mc.player.getY(), mc.player.getZ());
            int bw = tr.getWidth(coords) + 14; int bh = 14;
            RenderUtil.drawRoundedRect(context, pos[0], pos[1], bw, bh, 5f, 0xD9141C28, false);
            RenderUtil.drawOutline(context, pos[0], pos[1], bw, bh, 5f, 1f, 0xCC304055, false);
            context.drawText(tr, coords, pos[0] + 7, pos[1] + 3, 0xFFD0D8E8, false);
        }

        
        if (INSTANCE.info.getValue()) {
            int[] pos = getElementPos(HudElement.INFO);
            int fps = mc.getCurrentFps();
            int ping = 0;
            try {
                if (mc.getNetworkHandler() != null && mc.player != null) {
                    var entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
                    if (entry != null) ping = entry.getLatency();
                }
            } catch (Exception ignored) {}
            String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            String infoText = fps + " FPS | " + ping + "ms | " + time;
            int bw = tr.getWidth(infoText) + 14; int bh = 14;
            RenderUtil.drawRoundedRect(context, pos[0], pos[1], bw, bh, 5f, 0xD9141C28, false);
            RenderUtil.drawOutline(context, pos[0], pos[1], bw, bh, 5f, 1f, 0xCC304055, false);
            context.drawText(tr, infoText, pos[0] + 7, pos[1] + 3, 0xFFD0D8E8, false);
        }

        
        if (INSTANCE.potionEffects.getValue()) {
            List<StatusEffectInstance> effects = new ArrayList<>(mc.player.getStatusEffects());
            if (!effects.isEmpty()) {
                effects.sort(Comparator.comparingInt(e -> tr.getWidth(getEffectName(e))));
                int[] pos = getElementPos(HudElement.POTION_EFFECTS);
                int by = pos[1];
                for (StatusEffectInstance eff : effects) {
                    String name = getEffectName(eff);
                    int bw = tr.getWidth(name) + 14; int bh = 14;
                    int color = eff.getEffectType().value().getColor();
                    int pillColor = (0xCC << 24) | (color & 0x00FFFFFF);
                    RenderUtil.drawRoundedRect(context, pos[0], by, bw, bh, 5f, 0xD9141C28, false);
                    RenderUtil.drawOutline(context, pos[0], by, bw, bh, 5f, 1f, pillColor, false);
                    RenderUtil.drawRoundedRect(context, pos[0], by, 2f, bh, 2f, pillColor, false);
                    context.drawText(tr, name, pos[0] + 7, by + 3, 0xFFD0D8E8, false);
                    by += bh + 3;
                }
            }
        }


        if (INSTANCE.armor.getValue()) {
            int[] pos = getElementPos(HudElement.ARMOR);
            java.util.List<net.minecraft.item.ItemStack> pieces = new java.util.ArrayList<>();
            net.minecraft.entity.EquipmentSlot[] slots = {
                    net.minecraft.entity.EquipmentSlot.HEAD,
                    net.minecraft.entity.EquipmentSlot.CHEST,
                    net.minecraft.entity.EquipmentSlot.LEGS,
                    net.minecraft.entity.EquipmentSlot.FEET
            };
            for (net.minecraft.entity.EquipmentSlot sl : slots) {
                net.minecraft.item.ItemStack s = mc.player.getEquippedStack(sl);
                if (s != null && !s.isEmpty()) pieces.add(s);
            }
            if (!pieces.isEmpty()) {
                int maxTextW = 0;
                for (net.minecraft.item.ItemStack s : pieces) {
                    int pct = (s.isDamageable() && s.getMaxDamage() > 0)
                            ? (int) Math.round((1.0 - (double) s.getDamage() / s.getMaxDamage()) * 100.0)
                            : 100;
                    int w = tr.getWidth(pct + "%");
                    if (w > maxTextW) maxTextW = w;
                }
                int bw = 22 + maxTextW + 6;
                int bh = 18;
                int ay = pos[1];
                for (net.minecraft.item.ItemStack s : pieces) {
                    int pct = (s.isDamageable() && s.getMaxDamage() > 0)
                            ? (int) Math.round((1.0 - (double) s.getDamage() / s.getMaxDamage()) * 100.0)
                            : 100;
                    int barColor;
                    if (pct >= 66)      barColor = 0xCC4ADE80;
                    else if (pct >= 33) barColor = 0xCCFACC15;
                    else                barColor = 0xCCEF4444;
                    RenderUtil.drawRoundedRect(context, pos[0], ay, bw, bh, 5f, 0xD9141C28, false);
                    RenderUtil.drawOutline(context, pos[0], ay, bw, bh, 5f, 1f, 0xCC304055, false);
                    RenderUtil.drawRoundedRect(context, pos[0], ay, 2f, bh, 2f, barColor, false);
                    context.drawItem(s, pos[0] + 4, ay + 1);
                    String pctStr = pct + "%";
                    context.drawText(tr, pctStr, pos[0] + 22, ay + 5, 0xFFD0D8E8, false);
                    ay += bh + 2;
                }
            }
        }


        if (INSTANCE.keybinds.getValue()) {
            List<Module> bound = collectBoundModules();
            if (!bound.isEmpty()) {
                int[] pos = getElementPos(HudElement.KEYBINDS);
                int by = pos[1];
                for (Module m : bound) {
                    String line = formatBindLine(m);
                    int bw = tr.getWidth(line) + 14;
                    int bh = 14;
                    RenderUtil.drawRoundedRect(context, pos[0], by, bw, bh, 5f, 0xD9141C28, false);
                    RenderUtil.drawOutline(context, pos[0], by, bw, bh, 5f, 1f, 0xCC304055, false);
                    RenderUtil.drawRoundedRect(context, pos[0], by, 2f, bh, 2f, accent, false);
                    context.drawText(tr, line, pos[0] + 7, by + 3, 0xFFD0D8E8, false);
                    by += bh + 3;
                }
            }
        }


        if (INSTANCE.moduleList.getValue()) {
            int[] pos = getElementPos(HudElement.MODULE_LIST);
            List<Module> enabled = new ArrayList<>();
            for (Module m : ModuleManager.INSTANCE.getModules()) {
                if (m.isEnabled() && m.getCategory() != Category.CLIENT) enabled.add(m);
            }
            enabled.sort(Comparator.comparingInt((Module m) -> tr.getWidth(m.getName())).reversed());
            int ey = pos[1];
            for (Module m : enabled) {
                String label = m.getName();
                int bw = tr.getWidth(label) + 14; int bh = 14;
                int bx = pos[0] - bw; 
                RenderUtil.drawRoundedRect(context, bx, ey, bw, bh, 6f, 0xD9141C28, false);
                RenderUtil.drawOutline(context, bx, ey, bw, bh, 6f, 1f, 0xCC304055, false);
                RenderUtil.drawRoundedRect(context, bx, ey, 2f, bh, 2f, accent, false);
                context.drawText(tr, label, bx + 6, ey + 3, 0xFFF2F6FB, false);
                ey += bh + 4;
            }
        }
    }

    public static String getEffectName(StatusEffectInstance eff) {
        String raw = Registries.STATUS_EFFECT.getId(eff.getEffectType().value()).getPath();
        
        String[] parts = raw.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!p.isEmpty()) {
                sb.append(Character.toUpperCase(p.charAt(0)));
                if (p.length() > 1) sb.append(p.substring(1));
                sb.append(' ');
            }
        }
        String name = sb.toString().trim();
        int amp = eff.getAmplifier();
        if (amp > 0) name += " " + toRoman(amp + 1);
        int ticks = eff.getDuration();
        if (ticks < 32767) {
            int secs = ticks / 20;
            name += " " + String.format("%d:%02d", secs / 60, secs % 60);
        }
        return name;
    }

    private static String toRoman(int n) {
        return switch (n) {
            case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV";
            case 5 -> "V"; case 6 -> "VI"; case 7 -> "VII"; case 8 -> "VIII";
            case 9 -> "IX"; case 10 -> "X"; default -> String.valueOf(n);
        };
    }
}

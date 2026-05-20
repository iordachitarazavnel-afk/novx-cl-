package com.xenon.module.modules.misc;

import com.xenon.gui.notification.NotificationManager;
import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.ModeSetting;
import com.xenon.setting.Setting;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class TabDetector extends Module {

    private final ModeSetting detectMode = new ModeSetting("Detect", "List", "Any", "List");
    private final Setting<String> targetPlayers = new Setting<>("Target Players", "");
    private final ModeSetting notificationMode = new ModeSetting("Notification Mode", "Both", "Chat", "Toast", "Both");
    private final Setting<Boolean> logOffline = new Setting<>("Log Offline", true);

    private final Set<String> currentTargetsOnline = new HashSet<>();
    private final Set<String> previousTargetsOnline = new HashSet<>();

    public TabDetector() {
        super("TabDetector", Category.MISC);
        targetPlayers.visibleWhen(() -> detectMode.is("List"));
        addSetting(detectMode);
        addSetting(targetPlayers);
        addSetting(notificationMode);
        addSetting(logOffline);
    }

    @Override
    public void onEnable() {
        currentTargetsOnline.clear();
        previousTargetsOnline.clear();
        snapshotOnlineTargetsInto(previousTargetsOnline);
    }

    @Override
    public void onDisable() {
        currentTargetsOnline.clear();
        previousTargetsOnline.clear();
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) {
            return;
        }

        boolean any = detectMode.is("Any");
        Set<String> wanted = any ? Set.of() : parseTargets(targetPlayers.getValue());
        if (!any && wanted.isEmpty()) {
            currentTargetsOnline.clear();
            previousTargetsOnline.clear();
            return;
        }

        currentTargetsOnline.clear();
        for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
            String name = safeProfileName(entry);
            if (name.isEmpty()) continue;
            if (mc.player != null && name.equalsIgnoreCase(mc.player.getName().getString())) {
                continue;
            }
            if (any || containsIgnoreCase(wanted, name)) {
                currentTargetsOnline.add(name);
            }
        }

        Set<String> joined = new HashSet<>(currentTargetsOnline);
        joined.removeAll(previousTargetsOnline);
        if (!joined.isEmpty()) {
            handleJoin(joined);
        }

        if (logOffline.getValue()) {
            Set<String> left = new HashSet<>(previousTargetsOnline);
            left.removeAll(currentTargetsOnline);
            if (!left.isEmpty()) {
                handleLeave(left);
            }
        }

        previousTargetsOnline.clear();
        previousTargetsOnline.addAll(currentTargetsOnline);
    }

    private void handleJoin(Set<String> players) {
        String list = String.join(", ", players);
        String msg = players.size() == 1 ? "Target player joined: " + list : "Target players joined: " + list;
        notify(msg, players.size() == 1 ? "Target Player Joined!" : "Target Players Joined!", 0xFFE26A6A);
    }

    private void handleLeave(Set<String> players) {
        String list = String.join(", ", players);
        String msg = players.size() == 1 ? "Target player left: " + list : "Target players left: " + list;
        notify(msg, players.size() == 1 ? "Target Player Left!" : "Target Players Left!", 0xFF55D4A2);
    }

    private void notify(String chatMessage, String toastTitle, int accent) {
        String mode = notificationMode.getValue();
        boolean toChat = "Chat".equalsIgnoreCase(mode) || "Both".equalsIgnoreCase(mode);
        boolean toToast = "Toast".equalsIgnoreCase(mode) || "Both".equalsIgnoreCase(mode);

        if (toChat) {
            try {
                mc.inGameHud.getChatHud().addMessage(Text.literal("[TabDetector] " + chatMessage));
            } catch (Throwable ignored) {}
        }
        if (toToast) {
            NotificationManager.INSTANCE.push("TabDetector", toastTitle, ItemStack.EMPTY, accent);
        }
    }

    private void snapshotOnlineTargetsInto(Set<String> out) {
        out.clear();
        if (mc.getNetworkHandler() == null) return;
        boolean any = detectMode.is("Any");
        Set<String> wanted = any ? Set.of() : parseTargets(targetPlayers.getValue());
        if (!any && wanted.isEmpty()) return;

        for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
            String name = safeProfileName(entry);
            if (name.isEmpty()) continue;
            if (mc.player != null && name.equalsIgnoreCase(mc.player.getName().getString())) {
                continue;
            }
            if (any || containsIgnoreCase(wanted, name)) {
                out.add(name);
            }
        }
    }

    private String safeProfileName(PlayerListEntry entry) {
        try {
            if (entry == null || entry.getProfile() == null) return "";
            // In this codebase GameProfile is a record-like API (see PlayerListEntryMixin usage: profile.id()).
            try {
                String n = entry.getProfile().name();
                return n == null ? "" : n;
            } catch (Throwable ignored) {
                return "";
            }
        } catch (Throwable ignored) {
            return "";
        }
    }

    private boolean containsIgnoreCase(Set<String> loweredSet, String value) {
        return loweredSet.contains(value.toLowerCase(Locale.ROOT));
    }

    private Set<String> parseTargets(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        String normalized = raw.replace('\n', ',').replace('\r', ',');
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String part : normalized.split(",")) {
            String t = part == null ? "" : part.trim();
            if (t.isEmpty()) continue;
            out.add(t.toLowerCase(Locale.ROOT));
        }
        return out;
    }
}

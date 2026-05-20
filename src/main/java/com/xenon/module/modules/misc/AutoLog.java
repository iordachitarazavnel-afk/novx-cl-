package com.xenon.module.modules.misc;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.module.modules.client.Friends;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

public final class AutoLog extends Module {

    private static final long COMBAT_COOLDOWN_MS = 20_000L;
    private static final int COMBAT_COOLDOWN_TICKS = (int) (COMBAT_COOLDOWN_MS / 50L);
    private static final double COMBAT_GRACE_RANGE = 8.0;
    private static final long SERVER_COMBAT_GRACE_MS = 1_500L;
    private static final int MAX_TEXT_SCAN_DEPTH = 4;

    private long lastHitTime;
    private long lastAttackTime;
    private long lastServerCombatTagTime;
    private float lastCombinedHealth = -1.0f;
    private int lastObservedAttackedTick = -1;
    private int lastObservedAttackTick = -1;

    public AutoLog() {
        super("AutoLog", Category.MISC);
    }

    @Override
    public void onEnable() {
        lastHitTime = 0L;
        lastAttackTime = 0L;
        lastServerCombatTagTime = 0L;
        lastCombinedHealth = getCurrentCombinedHealth();
        lastObservedAttackedTick = getCurrentAttackedTick();
        lastObservedAttackTick = getCurrentAttackTick();

        if (mc.player != null
                && mc.world != null
                && (isCombatContextPresent() || isRecentPlayerCombat() || isServerCombatTagged())) {
            startCombatCooldown(System.currentTimeMillis());
        }
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) {
            return;
        }

        long now = System.currentTimeMillis();
        float currentCombinedHealth = getCurrentCombinedHealth();
        boolean tookDamage = lastCombinedHealth >= 0.0f && currentCombinedHealth + 0.001f < lastCombinedHealth;
        lastCombinedHealth = currentCombinedHealth;

        if (mc.player.hurtTime > 0 || tookDamage || hasNewPlayerHit()) {
            lastHitTime = now;
        }
        boolean serverCombatTagged = isServerCombatTagged();
        if (serverCombatTagged) {
            lastServerCombatTagTime = now;
        }

        if (mc.options.attackKey.isPressed()
                && mc.crosshairTarget != null
                && mc.crosshairTarget.getType() == HitResult.Type.ENTITY
                && mc.crosshairTarget instanceof EntityHitResult entityHitResult
                && entityHitResult.getEntity() instanceof PlayerEntity target
                && target != mc.player) {
            lastAttackTime = now;
        }
        if (hasNewPlayerAttack()) {
            lastAttackTime = now;
        }

        if (isCombatCooldownActive(now, serverCombatTagged)) {
            return;
        }

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player.isSpectator()) {
                continue;
            }
            if (Friends.isAutoLog() && Friends.isFriend(player.getName().getString())) {
                continue;
            }

            if (mc.getNetworkHandler() != null && mc.getNetworkHandler().getConnection() != null) {
                mc.getNetworkHandler().getConnection()
                        .disconnect(Text.literal("[AutoLog] Player detected: " + player.getName().getString()));
                toggle();
            }
            return;
        }
    }

    private boolean isCombatCooldownActive(long now, boolean serverCombatTagged) {
        if (serverCombatTagged || isRecentPlayerCombat() || now - lastServerCombatTagTime < SERVER_COMBAT_GRACE_MS) {
            return true;
        }
        if (lastHitTime <= 0L && lastAttackTime <= 0L) {
            return false;
        }

        long lastCombatTime = Math.max(lastHitTime, lastAttackTime);
        return now - lastCombatTime < COMBAT_COOLDOWN_MS;
    }

    private boolean isCombatContextPresent() {
        if (mc.player == null || mc.world == null) {
            return false;
        }
        if (mc.player.hurtTime > 0 || isRecentPlayerCombat()) {
            return true;
        }
        if (mc.crosshairTarget != null
                && mc.crosshairTarget.getType() == HitResult.Type.ENTITY
                && mc.crosshairTarget instanceof EntityHitResult entityHitResult
                && entityHitResult.getEntity() instanceof PlayerEntity target
                && target != mc.player
                && !target.isSpectator()) {
            return true;
        }

        double maxDistanceSq = COMBAT_GRACE_RANGE * COMBAT_GRACE_RANGE;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player.isSpectator()) {
                continue;
            }
            if (mc.player.squaredDistanceTo(player) <= maxDistanceSq) {
                return true;
            }
        }
        return false;
    }

    private void startCombatCooldown(long now) {
        lastHitTime = now;
        lastAttackTime = now;
        lastServerCombatTagTime = now;
    }

    private boolean isRecentPlayerCombat() {
        return isRecentPlayerHit() || isRecentPlayerAttack();
    }

    private boolean hasNewPlayerHit() {
        int currentAttackedTick = getCurrentAttackedTick();
        if (currentAttackedTick <= 0 || currentAttackedTick == lastObservedAttackedTick) {
            return false;
        }

        lastObservedAttackedTick = currentAttackedTick;
        return isRecentPlayerHit();
    }

    private boolean hasNewPlayerAttack() {
        int currentAttackTick = getCurrentAttackTick();
        if (currentAttackTick <= 0 || currentAttackTick == lastObservedAttackTick) {
            return false;
        }

        lastObservedAttackTick = currentAttackTick;
        return isRecentPlayerAttack();
    }

    private boolean isRecentPlayerHit() {
        if (mc.player == null) {
            return false;
        }
        if (!(mc.player.getLastAttacker() instanceof PlayerEntity attacker)
                || attacker == mc.player
                || attacker.isSpectator()) {
            return false;
        }
        return isRecentCombatTick(mc.player.getLastAttackedTime());
    }

    private boolean isRecentPlayerAttack() {
        if (mc.player == null) {
            return false;
        }
        if (!(mc.player.getAttacking() instanceof PlayerEntity target)
                || target == mc.player
                || target.isSpectator()) {
            return false;
        }
        return isRecentCombatTick(mc.player.getLastAttackTime());
    }

    private boolean isRecentCombatTick(int tickTimestamp) {
        if (mc.player == null || tickTimestamp <= 0) {
            return false;
        }

        int elapsedTicks = mc.player.age - tickTimestamp;
        return elapsedTicks >= 0 && elapsedTicks < COMBAT_COOLDOWN_TICKS;
    }

    private int getCurrentAttackedTick() {
        return mc.player != null ? mc.player.getLastAttackedTime() : -1;
    }

    private int getCurrentAttackTick() {
        return mc.player != null ? mc.player.getLastAttackTime() : -1;
    }

    private boolean isServerCombatTagged() {
        if (mc.world != null && containsCombatScoreboardText(mc.world.getScoreboard())) {
            return true;
        }
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        return mc.inGameHud != null && containsCombatText(mc.inGameHud, 0, visited);
    }

    private boolean containsCombatScoreboardText(Scoreboard scoreboard) {
        if (scoreboard == null) {
            return false;
        }

        for (ScoreboardObjective objective : scoreboard.getObjectives()) {
            if (containsCombatKeyword(objective.getName()) || containsCombatText(objective.getDisplayName())) {
                return true;
            }
        }

        for (ScoreboardDisplaySlot slot : ScoreboardDisplaySlot.values()) {
            ScoreboardObjective objective = scoreboard.getObjectiveForSlot(slot);
            if (objective == null) {
                continue;
            }
            if (containsCombatKeyword(objective.getName()) || containsCombatText(objective.getDisplayName())) {
                return true;
            }

            for (ScoreboardEntry entry : scoreboard.getScoreboardEntries(objective)) {
                if (containsCombatKeyword(entry.owner())
                        || containsCombatText(entry.name())
                        || containsCombatText(entry.display())) {
                    return true;
                }

                Team team = scoreboard.getScoreHolderTeam(entry.owner());
                if (containsCombatTeamText(team)) {
                    return true;
                }
            }
        }

        for (Team team : scoreboard.getTeams()) {
            if (containsCombatTeamText(team)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsCombatTeamText(Team team) {
        return team != null
                && (containsCombatKeyword(team.getName())
                || containsCombatText(team.getDisplayName())
                || containsCombatText(team.getPrefix())
                || containsCombatText(team.getSuffix()));
    }

    private boolean containsCombatText(Object value, int depth, Set<Object> visited) {
        if (value == null || depth > MAX_TEXT_SCAN_DEPTH) {
            return false;
        }

        if (value instanceof Text text) {
            return containsCombatKeyword(text.getString());
        }
        if (value instanceof String string) {
            return containsCombatKeyword(string);
        }
        if (!visited.add(value)) {
            return false;
        }

        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (containsCombatText(entry.getKey(), depth + 1, visited)
                        || containsCombatText(entry.getValue(), depth + 1, visited)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Collection<?> collection) {
            for (Object element : collection) {
                if (containsCombatText(element, depth + 1, visited)) {
                    return true;
                }
            }
            return false;
        }

        Class<?> clazz = value.getClass();
        if (!isInspectableClass(clazz)) {
            return false;
        }

        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }

                field.setAccessible(true);
                try {
                    Object fieldValue = field.get(value);
                    if (containsCombatText(fieldValue, depth + 1, visited)) {
                        return true;
                    }
                } catch (IllegalAccessException ignored) {
                }
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private boolean isInspectableClass(Class<?> clazz) {
        String name = clazz.getName();
        return name.startsWith("net.minecraft.scoreboard.")
                || name.startsWith("net.minecraft.text.")
                || name.startsWith("net.minecraft.client.gui.hud.")
                || name.startsWith("net.minecraft.client.network.")
                || name.startsWith("java.util.");
    }

    private boolean containsCombatKeyword(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase();
        return lower.contains("combat");
    }

    private boolean containsCombatText(Text text) {
        return text != null && containsCombatKeyword(text.getString());
    }

    private float getCurrentCombinedHealth() {
        if (mc.player == null) {
            return -1.0f;
        }
        return mc.player.getHealth() + mc.player.getAbsorptionAmount();
    }
}

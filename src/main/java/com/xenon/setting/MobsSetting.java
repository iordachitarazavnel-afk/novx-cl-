package com.xenon.setting;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MobsSetting extends Setting<Set<EntityType<?>>> {

    private final List<EntityType<?>> availableMobs;
    private long version;

    public MobsSetting(String name, EntityType<?>... defaults) {
        super(name, createDefaultSet(defaults));
        this.availableMobs = Registries.ENTITY_TYPE.stream()
                .filter(MobsSetting::isLivingMob)
                .sorted(Comparator.comparing(this::getDisplayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static boolean isLivingMob(EntityType<?> type) {
        SpawnGroup g = type.getSpawnGroup();
        return g == SpawnGroup.MONSTER
                || g == SpawnGroup.CREATURE
                || g == SpawnGroup.AMBIENT
                || g == SpawnGroup.AXOLOTLS
                || g == SpawnGroup.UNDERGROUND_WATER_CREATURE
                || g == SpawnGroup.WATER_CREATURE
                || g == SpawnGroup.WATER_AMBIENT;
    }

    @Override
    public void setValue(Set<EntityType<?>> value) {
        LinkedHashSet<EntityType<?>> next = new LinkedHashSet<>();
        if (value != null) {
            for (EntityType<?> t : value) {
                if (t != null) next.add(t);
            }
        }
        super.setValue(next);
        version++;
    }

    public boolean contains(EntityType<?> type) {
        return type != null && getValue().contains(type);
    }

    public void toggle(EntityType<?> type) {
        if (type == null) return;
        LinkedHashSet<EntityType<?>> next = new LinkedHashSet<>(getValue());
        if (!next.add(type)) next.remove(type);
        setValue(next);
    }

    public void clear() {
        if (getValue().isEmpty()) return;
        setValue(Collections.emptySet());
    }

    public int size() { return getValue().size(); }
    public long getVersion() { return version; }

    public Set<EntityType<?>> getSelectedMobs() {
        return Collections.unmodifiableSet(getValue());
    }

    public List<EntityType<?>> getAvailableMobs() { return availableMobs; }

    public List<EntityType<?>> filter(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return availableMobs;
        List<EntityType<?>> filtered = new ArrayList<>();
        for (EntityType<?> t : availableMobs) {
            String displayName = getDisplayName(t).toLowerCase(Locale.ROOT);
            Identifier id = Registries.ENTITY_TYPE.getId(t);
            String idString = id == null ? "" : id.toString().toLowerCase(Locale.ROOT);
            if (displayName.contains(normalized) || idString.contains(normalized)) {
                filtered.add(t);
            }
        }
        return filtered;
    }

    public String getDisplayName(EntityType<?> type) {
        try {
            return type.getName().getString();
        } catch (Exception ignored) {
            Identifier id = Registries.ENTITY_TYPE.getId(type);
            return id == null ? "Mob" : id.getPath();
        }
    }

    public String getSummary() {
        if (getValue().isEmpty()) return "None";
        EntityType<?> first = getValue().iterator().next();
        String firstName = getDisplayName(first);
        int extra = getValue().size() - 1;
        return extra > 0 ? firstName + " +" + extra : firstName;
    }

    private static Set<EntityType<?>> createDefaultSet(EntityType<?>... defaults) {
        LinkedHashSet<EntityType<?>> selected = new LinkedHashSet<>();
        if (defaults != null) {
            Collections.addAll(selected, defaults);
            selected.remove(null);
        }
        return selected;
    }
}

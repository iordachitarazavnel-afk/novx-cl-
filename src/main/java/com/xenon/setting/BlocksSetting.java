package com.xenon.setting;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class BlocksSetting extends Setting<Set<Block>> {

    private final List<Block> availableBlocks;
    private long version;

    public BlocksSetting(String name, Block... defaults) {
        super(name, createDefaultSet(defaults));
        this.availableBlocks = Registries.BLOCK.stream()
                .filter(block -> block != Blocks.AIR)
                .sorted(Comparator.comparing(this::getDisplayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    public void setValue(Set<Block> value) {
        LinkedHashSet<Block> next = new LinkedHashSet<>();
        if (value != null) {
            for (Block block : value) {
                if (block != null && block != Blocks.AIR) {
                    next.add(block);
                }
            }
        }

        super.setValue(next);
        version++;
    }

    public boolean contains(Block block) {
        return block != null && getValue().contains(block);
    }

    public void toggle(Block block) {
        if (block == null || block == Blocks.AIR) {
            return;
        }

        LinkedHashSet<Block> next = new LinkedHashSet<>(getValue());
        if (!next.add(block)) {
            next.remove(block);
        }
        setValue(next);
    }

    public void clear() {
        if (getValue().isEmpty()) {
            return;
        }
        setValue(Collections.emptySet());
    }

    public int size() {
        return getValue().size();
    }

    public long getVersion() {
        return version;
    }

    public Set<Block> getSelectedBlocks() {
        return Collections.unmodifiableSet(getValue());
    }

    public List<Block> getAvailableBlocks() {
        return availableBlocks;
    }

    public List<Block> filter(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return availableBlocks;
        }

        List<Block> filtered = new ArrayList<>();
        for (Block block : availableBlocks) {
            String displayName = getDisplayName(block).toLowerCase(Locale.ROOT);
            Identifier id = Registries.BLOCK.getId(block);
            String idString = id == null ? "" : id.toString().toLowerCase(Locale.ROOT);
            if (displayName.contains(normalized) || idString.contains(normalized)) {
                filtered.add(block);
            }
        }
        return filtered;
    }

    public String getDisplayName(Block block) {
        try {
            return block.getName().getString();
        } catch (Exception ignored) {
            Identifier id = Registries.BLOCK.getId(block);
            return id == null ? "Block" : id.getPath();
        }
    }

    public String getSummary() {
        if (getValue().isEmpty()) {
            return "None";
        }

        Block first = getValue().iterator().next();
        String firstName = getDisplayName(first);
        int extra = getValue().size() - 1;
        return extra > 0 ? firstName + " +" + extra : firstName;
    }

    private static Set<Block> createDefaultSet(Block... defaults) {
        LinkedHashSet<Block> selected = new LinkedHashSet<>();
        if (defaults != null) {
            Collections.addAll(selected, defaults);
            selected.remove(null);
            selected.remove(Blocks.AIR);
        }
        return selected;
    }
}

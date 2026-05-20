package com.xenon.utils;

import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

public final class NametagRenderState {

    private static final Set<EntityRenderState> OVERRIDDEN_LABELS = Collections.newSetFromMap(new WeakHashMap<>());

    private NametagRenderState() {
    }

    public static void clear(EntityRenderState state) {
        OVERRIDDEN_LABELS.remove(state);
    }

    public static void mark(EntityRenderState state) {
        OVERRIDDEN_LABELS.add(state);
    }

    public static boolean hasEntry(EntityRenderState state) {
        return OVERRIDDEN_LABELS.contains(state);
    }

    public static boolean isOutlinedLabel(Text text) {
        return false;
    }

    public record ItemEntry(ItemStack stack) {
    }

    public record Entry(
            Object entity,
            Vec3d labelPos,
            Text nameLabel,
            Text healthLabel,
            List<ItemEntry> items
    ) {
    }
}

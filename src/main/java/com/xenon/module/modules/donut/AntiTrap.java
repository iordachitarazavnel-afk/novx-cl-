package com.xenon.module.modules.donut;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.Setting;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;

import java.util.ArrayList;
import java.util.List;

public final class AntiTrap extends Module {

    private final Setting<Boolean> armorStands = new Setting<>("Armor Stands", true);
    private final Setting<Boolean> minecarts = new Setting<>("Minecarts", true);
    private final Setting<Boolean> chestMinecarts = new Setting<>("Chest Minecarts", true);
    private final Setting<Boolean> hopperMinecarts = new Setting<>("Hopper Minecarts", true);

    public AntiTrap() {
        super("AntiTrap", Category.DONUT);
        addSetting(armorStands);
        addSetting(minecarts);
        addSetting(chestMinecarts);
        addSetting(hopperMinecarts);
    }

    @Override
    public void onEnable() {
        removeTrapEntities();
    }

    @Override
    public void onTick() {
        removeTrapEntities();
    }

    private void removeTrapEntities() {
        if (mc.world == null) return;
        List<Entity> trapEntities = new ArrayList<>();
        mc.world.getEntities().forEach(entity -> {
            if (entity != null && isTrapEntity(entity.getType())) {
                trapEntities.add(entity);
            }
        });
        trapEntities.forEach(e -> {
            if (!e.isRemoved()) e.remove(Entity.RemovalReason.DISCARDED);
        });
    }

    private boolean isTrapEntity(EntityType<?> type) {
        if (type == null) return false;
        if (armorStands.getValue() && type.equals(EntityType.ARMOR_STAND)) return true;
        if (minecarts.getValue() && type.equals(EntityType.MINECART)) return true;
        if (chestMinecarts.getValue() && type.equals(EntityType.CHEST_MINECART)) return true;
        if (hopperMinecarts.getValue() && type.equals(EntityType.HOPPER_MINECART)) return true;
        return false;
    }
}

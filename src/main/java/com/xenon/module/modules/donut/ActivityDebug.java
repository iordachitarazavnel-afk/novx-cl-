package com.xenon.module.modules.donut;

import com.xenon.gui.notification.NotificationManager;
import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.Setting;
import com.xenon.utils.RenderUtils;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ActivityDebug extends Module {

    private static final long NOTIFY_COOLDOWN_MS = 1250L;
    private static final double MARKER_SURFACE_Y = 57.0D;
    private static final double MARKER_SURFACE_THICKNESS = 0.05D;

    private final Setting<Float> yLevel = new Setting<>("y-level", 16.0f, -64.0f, 320.0f);
    private final Setting<Boolean> notification = new Setting<>("Notification", false);

    private final Set<ChunkPos> susChunks = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<Long, Long> lastNotifiedAt = new ConcurrentHashMap<>();
    private static final Color YELLOW = new Color(255, 220, 0, 180);
    private static final Color YELLOW_OUTLINE = new Color(255, 220, 0, 255);
    private final Map<Class<?>, List<Field>> doubleFields = new ConcurrentHashMap<>();
    private final Map<Class<?>, List<Field>> nestedFields = new ConcurrentHashMap<>();
    private final Map<Class<?>, List<Field>> blockPosFields = new ConcurrentHashMap<>();
    private final Map<Class<?>, List<Field>> vec3Fields = new ConcurrentHashMap<>();
    private final ThreadLocal<Set<Integer>> exploredObjects = ThreadLocal.withInitial(() ->
            Collections.newSetFromMap(new ConcurrentHashMap<>())
    );

    public ActivityDebug() {
        super("ActivityDebug", Category.DONUT);
        addSetting(yLevel);
        addSetting(notification);
    }

    @Override
    public void onDisable() {
        susChunks.clear();
        lastNotifiedAt.clear();
        doubleFields.clear();
        nestedFields.clear();
        blockPosFields.clear();
        vec3Fields.clear();
    }

    @Override
    public void onPacketReceive(Packet<?> packet) {
        if (packet instanceof ChunkDeltaUpdateS2CPacket sectionUpdate) {
            sectionUpdate.visitUpdates((pos, state) -> checkAndAdd(pos.getX(), pos.getY(), pos.getZ()));
            return;
        }

        if (packet instanceof BlockUpdateS2CPacket blockUpdate) {
            BlockPos pos = blockUpdate.getPos();
            checkAndAdd(pos.getX(), pos.getY(), pos.getZ());
            return;
        }

        exploredObjects.get().clear();
        analyzeObject(packet, 0);
    }

    private void analyzeObject(Object obj, int depth) {
        if (obj == null || depth > 3) {
            return;
        }

        int hash = System.identityHashCode(obj);
        if (!exploredObjects.get().add(hash)) {
            return;
        }

        Class<?> clazz = obj.getClass();

        blockPosFields.computeIfAbsent(clazz, c -> getFieldsOfType(c, BlockPos.class));
        vec3Fields.computeIfAbsent(clazz, c -> getFieldsOfType(c, Vec3d.class));
        doubleFields.computeIfAbsent(clazz, c -> getFieldsOfType(c, double.class));
        nestedFields.computeIfAbsent(clazz, c -> {
            List<Field> list = new ArrayList<>();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())
                            && !f.getType().isPrimitive()
                            && !f.getType().getName().startsWith("java.")
                            && !f.getType().isEnum()) {
                        f.setAccessible(true);
                        list.add(f);
                    }
                }
                c = c.getSuperclass();
            }
            return list;
        });

        for (Field f : blockPosFields.get(clazz)) {
            try {
                BlockPos bp = (BlockPos) f.get(obj);
                if (bp != null) {
                    checkAndAdd(bp.getX(), bp.getY(), bp.getZ());
                }
            } catch (Exception ignored) {
            }
        }

        for (Field f : vec3Fields.get(clazz)) {
            try {
                Vec3d v = (Vec3d) f.get(obj);
                if (v != null) {
                    checkAndAdd(v.x, v.y, v.z);
                }
            } catch (Exception ignored) {
            }
        }

        List<Field> dFields = doubleFields.get(clazz);
        if (dFields.size() >= 3) {
            try {
                double x = dFields.get(0).getDouble(obj);
                double y = dFields.get(1).getDouble(obj);
                double z = dFields.get(2).getDouble(obj);
                if (Math.abs(x) < 3.0E7 && Math.abs(z) < 3.0E7 && y > -2048 && y < 2048) {
                    checkAndAdd(x, y, z);
                }
            } catch (Exception ignored) {
            }
        }

        for (Field f : nestedFields.get(clazz)) {
            try {
                Object nestedObj = f.get(obj);
                if (nestedObj != null) {
                    analyzeObject(nestedObj, depth + 1);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private List<Field> getFieldsOfType(Class<?> clazz, Class<?> type) {
        List<Field> list = new ArrayList<>();
        while (clazz != null && clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && f.getType() == type) {
                    f.setAccessible(true);
                    list.add(f);
                }
            }
            clazz = clazz.getSuperclass();
        }
        return list;
    }

    private void checkAndAdd(double x, double y, double z) {
        if (mc.player != null && mc.player.getY() < 0.0D) {
            return;
        }
        if (y <= yLevel.getValue()) {
            ChunkPos chunkPos = new ChunkPos((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
            if (susChunks.add(chunkPos)) {
                maybeNotify(chunkPos, y);
            }
        }
    }

    private void maybeNotify(ChunkPos chunkPos, double y) {
        if (!notification.getValue() || mc.player == null || mc.world == null) {
            return;
        }

        long chunkKey = chunkPos.toLong();
        long now = System.currentTimeMillis();
        long last = lastNotifiedAt.getOrDefault(chunkKey, 0L);
        if (now - last < NOTIFY_COOLDOWN_MS) {
            return;
        }

        lastNotifiedAt.put(chunkKey, now);
        NotificationManager.INSTANCE.push(
                "Activity detected",
                "Chunk " + chunkPos.x + ", " + chunkPos.z + "  Y " + (int) Math.floor(y),
                new ItemStack(Items.COMPASS),
                YELLOW_OUTLINE.getRGB()
        );
        mc.world.playSound(
                mc.player,
                mc.player.getX(),
                mc.player.getY(),
                mc.player.getZ(),
                SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                SoundCategory.MASTER,
                0.6f,
                1.05f
        );
    }

    @Override
    public void onRender(MatrixStack matrices, float tickDelta) {
        if (mc.world == null) {
            return;
        }

        Camera camera = RenderUtils.getCamera();
        if (camera == null) {
            return;
        }

        Vec3d camPos = RenderUtils.getCameraPos(camera);
        double markerBaseY = Math.max(mc.world.getBottomY(), Math.min(MARKER_SURFACE_Y, mc.world.getTopYInclusive()));
        RenderUtils.WorldBatch batch = RenderUtils.beginWorldBatch(matrices);
        for (ChunkPos cp : susChunks) {
            if (!RenderUtils.isWorldBoxVisible(cp.getStartX(), MARKER_SURFACE_Y, cp.getStartZ(), cp.getEndX(), MARKER_SURFACE_Y + MARKER_SURFACE_THICKNESS, cp.getEndZ())) {
                continue;
            }
            double x1 = cp.getStartX() - camPos.x;
            double z1 = cp.getStartZ() - camPos.z;
            double y1 = markerBaseY - camPos.y;
            double x2 = x1 + 16.0D;
            double y2 = y1 + MARKER_SURFACE_THICKNESS;
            double z2 = z1 + 16.0D;

            batch.renderFilledBox(x1, y1, z1, x2, y2, z2, YELLOW);
        }
        batch.flush();
    }
}

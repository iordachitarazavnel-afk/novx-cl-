package com.xenon.module.modules.misc;

import com.xenon.XenonClient;
import com.xenon.gui.MenuScreen;
import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.module.modules.render.Freecam;
import com.xenon.setting.Setting;
import com.xenon.utils.NametagRenderState;
import com.xenon.utils.RenderUtils;
import com.xenon.utils.renderer.ProjectionUtil;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.EntityAttachmentType;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public final class NameTags extends Module {

    private static final float MAX_RENDER_DISTANCE = 64.0f;
    private static final boolean SHOW_ABSORPTION = true;
    private static final int HUD_TEXT_COLOR = 0xFFFFFFFF;
    private static final int HUD_OUTLINE_COLOR = 0xFF000000;
    private static final float HUD_WORLD_SCALE = 0.025f;
    private static final int HUD_OUTLINE_RADIUS = 1;
    private static final int HUD_NAME_OFFSET = 0;
    private static final int HUD_HEALTH_OFFSET_WITH_NAME = 10;
    private static final int HUD_HEALTH_OFFSET_NO_NAME = 0;
    private static final int HUD_ITEM_SIZE = 16;
    private static final int HUD_ITEM_GAP = 2;
    private static final int HUD_ITEM_ROW_OFFSET_WITH_HEALTH = 34;
    private static final int HUD_ITEM_ROW_OFFSET_WITH_NAME = 16;
    private static final int HUD_ITEM_ROW_OFFSET_NO_TEXT = 0;
    private static final double HUD_ANCHOR_Y_ADJUST = 0.62;
    private static final double HUD_FRUSTUM_Y_PADDING = 1.25D;
    private static final long HUD_CACHE_DURATION_MS = 125L;
    private static final int HEART_ICON_SIZE = 9;
    private static final int HEART_ICON_SPACING = 8;
    private static final Identifier HEART_CONTAINER_TEXTURE = Identifier.ofVanilla("hud/heart/container");
    private static final Identifier HEART_FULL_TEXTURE = Identifier.ofVanilla("hud/heart/full");
    private static final Identifier HEART_HALF_TEXTURE = Identifier.ofVanilla("hud/heart/half");
    private static final Identifier HEART_ABS_FULL_TEXTURE = Identifier.ofVanilla("hud/heart/absorbing_full");
    private static final Identifier HEART_ABS_HALF_TEXTURE = Identifier.ofVanilla("hud/heart/absorbing_half");
    private static final Pattern MINECRAFT_COLOR_CODE_PATTERN = Pattern.compile("\u00A7.");

    public static NameTags instance;

    private final Setting<Boolean> self = new Setting<>("Self", true);
    private final Setting<Boolean> name = new Setting<>("Name", true);
    private final Setting<Boolean> ping = new Setting<>("Ping", true);
    private final Setting<Boolean> health = new Setting<>("Health", true);
    private final Setting<Boolean> mainHand = new Setting<>("MainHand", true);
    private final Setting<Boolean> offHand = new Setting<>("OffHand", true);
    private final Setting<Boolean> armor = new Setting<>("Armor", true);
    private final Map<UUID, CachedHudData> hudCache = new HashMap<>();
    private final ProjectionUtil.ScreenProjection screenProjection = new ProjectionUtil.ScreenProjection();
    private int hudConfigSignature = Integer.MIN_VALUE;

    public NameTags() {
        super("NameTags", Category.MISC);
        instance = this;
        addSetting(self);
        addSetting(name);
        addSetting(ping);
        addSetting(health);
        addSetting(mainHand);
        addSetting(offHand);
        addSetting(armor);
    }

    public static boolean isActive() {
        return instance != null && instance.isEnabled() && mc != null && mc.player != null;
    }

    public static void renderHud(DrawContext context, float tickDelta) {
        if (!isActive() || mc.world == null || mc.options.hudHidden || isMenuOpen()) {
            return;
        }

        NameTags module = instance;
        if (module == null) {
            return;
        }

        module.ensureHudCacheConfig();
        module.pruneHudCacheIfNeeded();

        long now = System.currentTimeMillis();
        Camera camera = RenderUtils.getCamera();
        if (camera == null) {
            return;
        }

        Vec3d cameraPos = RenderUtils.getCameraPos(camera);
        double cameraX = cameraPos.x;
        double cameraY = cameraPos.y;
        double cameraZ = cameraPos.z;
        double maxDistanceSq = MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE;
        double scaleBaseX = mc.getWindow().getScaledWidth() * 0.5D * Math.abs(ProjectionUtil.projectionMatrix.m00()) * HUD_WORLD_SCALE;
        double scaleBaseY = mc.getWindow().getScaledHeight() * 0.5D * Math.abs(ProjectionUtil.projectionMatrix.m11()) * HUD_WORLD_SCALE;
        Matrix3x2fStack matrices = context.getMatrices();

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!module.shouldRenderFor(player)) {
                continue;
            }

            double worldX = MathHelper.lerp(tickDelta, player.lastRenderX, player.getX());
            double worldY = MathHelper.lerp(tickDelta, player.lastRenderY, player.getY());
            double worldZ = MathHelper.lerp(tickDelta, player.lastRenderZ, player.getZ());
            double deltaX = worldX - cameraX;
            double deltaY = worldY - cameraY;
            double deltaZ = worldZ - cameraZ;
            double squaredDistance = (deltaX * deltaX) + (deltaY * deltaY) + (deltaZ * deltaZ);
            if (squaredDistance > maxDistanceSq) {
                continue;
            }

            CachedHudData hudData = module.getCachedHudData(player, now);
            if (hudData.isEmpty()) {
                continue;
            }

            double halfWidth = Math.max(0.35D, player.getWidth() * 0.5D);
            if (!RenderUtils.isWorldBoxVisible(
                    worldX - halfWidth,
                    worldY,
                    worldZ - halfWidth,
                    worldX + halfWidth,
                    worldY + player.getHeight() + HUD_FRUSTUM_Y_PADDING,
                    worldZ + halfWidth
            )) {
                continue;
            }

            float hudScale = module.projectHudAnchor(player, tickDelta, worldX, worldY, worldZ, scaleBaseX, scaleBaseY);
            if (hudScale <= 0.0f) {
                continue;
            }

            matrices.pushMatrix();
            applyHudTransform(matrices, (float) module.screenProjection.x, (float) module.screenProjection.y, hudScale);
            renderHudLabel(context, hudData.nameLabel(), hudData.nameWidth(), HUD_NAME_OFFSET, false);
            renderHudHealth(
                    context,
                    hudData.healthData(),
                    hudData.nameLabel() != null ? HUD_HEALTH_OFFSET_WITH_NAME : HUD_HEALTH_OFFSET_NO_NAME
            );
            renderHudItems(context, hudData.items(), hudData.itemRowWidth(), hudData.nameLabel() != null, hudData.healthData() != null);
            matrices.popMatrix();
        }
    }

    public boolean shouldRenderFor(LivingEntity entity) {
        if (!entity.isAlive() || entity instanceof ArmorStandEntity || !(entity instanceof PlayerEntity)) {
            return false;
        }

        if (entity != mc.player && entity.isInvisibleTo(mc.player)) {
            return false;
        }

        if (entity == mc.player) {
            return self.getValue() && (!mc.options.getPerspective().isFirstPerson()
                    || (Freecam.instance != null && Freecam.instance.isEnabled()));
        }

        return true;
    }

    public boolean shouldRenderForState(LivingEntity entity, double squaredDistanceToCamera) {
        if (!shouldRenderFor(entity) || isMenuOpen()) {
            return false;
        }

        return squaredDistanceToCamera <= (double) (MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE);
    }

    @Override
    public void onEnable() {
        hudCache.clear();
        hudConfigSignature = Integer.MIN_VALUE;
    }

    @Override
    public void onDisable() {
        hudCache.clear();
    }

    @Override
    public void onTick() {
    }

    public Text buildNameLabel(LivingEntity entity) {
        MutableText text = Text.empty();
        boolean hasContent = false;

        if (name.getValue()) {
            text.append(Text.literal("| ").formatted(Formatting.DARK_AQUA));
            text.append(entity.getDisplayName().copy().formatted(Formatting.WHITE));
            hasContent = true;
        }

        if (ping.getValue() && entity instanceof PlayerEntity player) {
            int latency = getPing(player);
            if (latency >= 0) {
                if (hasContent) {
                    text.append(Text.literal(" ").formatted(Formatting.GRAY));
                }

                text.append(Text.literal("[").formatted(Formatting.DARK_GRAY));
                text.append(Text.literal(latency + " ms").formatted(getPingFormatting(latency)));
                text.append(Text.literal("]").formatted(Formatting.DARK_GRAY));
                hasContent = true;
            }
        }

        if (health.getValue()) {
            float absorption = SHOW_ABSORPTION ? Math.max(0.0f, entity.getAbsorptionAmount()) : 0.0f;
            if (absorption > 0.0f) {
                int abs = Math.max(1, MathHelper.ceil(absorption));
                if (hasContent) {
                    text.append(Text.literal(" ").formatted(Formatting.GRAY));
                }
                text.append(Text.literal("+" + abs).formatted(Formatting.GOLD));
                hasContent = true;
            }
        }

        return hasContent ? text : null;
    }

    private HealthRenderData getHealthRenderData(LivingEntity entity) {
        if (!health.getValue()) {
            return null;
        }

        float maxHealth = Math.max(1.0f, entity.getMaxHealth());
        float currentHealth = MathHelper.clamp(entity.getHealth(), 0.0f, maxHealth);
        float absorption = SHOW_ABSORPTION ? Math.max(0.0f, entity.getAbsorptionAmount()) : 0.0f;

        int maxHearts = Math.max(1, MathHelper.ceil(maxHealth / 2.0f));
        if (maxHearts > 10) {
            float scale = 10.0f / (float) maxHearts;
            currentHealth *= scale;
            absorption *= scale;
            maxHearts = 10;
        }

        int filledHalfHearts = MathHelper.clamp(Math.round(currentHealth), 0, maxHearts * 2);
        int fullHearts = filledHalfHearts / 2;
        boolean halfHeart = (filledHalfHearts & 1) != 0;
        int emptyHearts = Math.max(0, maxHearts - fullHearts - (halfHeart ? 1 : 0));

        int absorptionHalfHearts = Math.max(0, Math.round(absorption));
        int absorptionFullHearts = absorptionHalfHearts / 2;
        boolean absorptionHalfHeart = (absorptionHalfHearts & 1) != 0;

        int iconCount = maxHearts + absorptionFullHearts + (absorptionHalfHeart ? 1 : 0);
        if (fullHearts <= 0 && !halfHeart && absorptionFullHearts <= 0 && !absorptionHalfHeart && emptyHearts <= 0) {
            return null;
        }

        int totalWidth = ((iconCount - 1) * HEART_ICON_SPACING) + HEART_ICON_SIZE;
        return new HealthRenderData(maxHearts, fullHearts, halfHeart, emptyHearts, absorptionFullHearts, absorptionHalfHeart, totalWidth);
    }

    public List<NametagRenderState.ItemEntry> buildItemEntries(LivingEntity entity) {
        List<NametagRenderState.ItemEntry> items = new ArrayList<>(6);

        if (offHand.getValue()) {
            addItem(items, entity.getOffHandStack());
        }

        if (armor.getValue()) {
            addItem(items, entity.getEquippedStack(EquipmentSlot.FEET));
            addItem(items, entity.getEquippedStack(EquipmentSlot.LEGS));
            addItem(items, entity.getEquippedStack(EquipmentSlot.CHEST));
            addItem(items, entity.getEquippedStack(EquipmentSlot.HEAD));
        }

        if (mainHand.getValue()) {
            addItem(items, entity.getMainHandStack());
        }

        return items;
    }

    private void addItem(List<NametagRenderState.ItemEntry> items, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

        items.add(new NametagRenderState.ItemEntry(stack.copy()));
    }

    private static void renderHudItems(
            DrawContext context,
            List<NametagRenderState.ItemEntry> items,
            int rowWidth,
            boolean hasNameLabel,
            boolean hasHealthLabel
    ) {
        if (items.isEmpty()) {
            return;
        }

        int startX = -(rowWidth / 2);
        int offset = hasHealthLabel
                ? HUD_ITEM_ROW_OFFSET_WITH_HEALTH
                : hasNameLabel ? HUD_ITEM_ROW_OFFSET_WITH_NAME : HUD_ITEM_ROW_OFFSET_NO_TEXT;
        int y = -offset;

        for (int index = 0; index < items.size(); index++) {
            ItemStack stack = items.get(index).stack();
            int x = startX + (index * (HUD_ITEM_SIZE + HUD_ITEM_GAP));
            context.drawItem(stack, x, y);
            context.drawStackOverlay(mc.textRenderer, stack, x, y, null);
        }
    }

    private static void renderHudLabel(
            DrawContext context,
            Text text,
            int textWidth,
            int yOffset,
            boolean outlined
    ) {
        if (text == null) {
            return;
        }

        int x = -(textWidth / 2);
        int y = -yOffset;

        if (outlined) {
            String plain = text.getString();
            for (int offsetX = -HUD_OUTLINE_RADIUS; offsetX <= HUD_OUTLINE_RADIUS; offsetX++) {
                for (int offsetY = -HUD_OUTLINE_RADIUS; offsetY <= HUD_OUTLINE_RADIUS; offsetY++) {
                    if (offsetX == 0 && offsetY == 0) {
                        continue;
                    }

                    context.drawText(mc.textRenderer, plain, x + offsetX, y + offsetY, HUD_OUTLINE_COLOR, false);
                }
            }
        }
        context.drawText(mc.textRenderer, text, x, y, HUD_TEXT_COLOR, false);
    }

    private static void renderHudHealth(
            DrawContext context,
            HealthRenderData healthData,
            int yOffset
    ) {
        if (healthData == null) {
            return;
        }

        int x = -(healthData.totalWidth() / 2);
        int y = -yOffset;

        for (int index = 0; index < healthData.baseHeartCount(); index++) {
            int heartX = x + (index * HEART_ICON_SPACING);
            drawHeart(context, HEART_CONTAINER_TEXTURE, heartX, y);
        }

        for (int index = 0; index < healthData.fullHearts(); index++) {
            int heartX = x + (index * HEART_ICON_SPACING);
            drawHeart(context, HEART_FULL_TEXTURE, heartX, y);
        }

        if (healthData.halfHeart()) {
            int heartX = x + (healthData.fullHearts() * HEART_ICON_SPACING);
            drawHeart(context, HEART_HALF_TEXTURE, heartX, y);
        }

        int absorptionStart = x + (healthData.baseHeartCount() * HEART_ICON_SPACING);
        int absorptionIcons = healthData.absorptionFullHearts() + (healthData.absorptionHalfHeart() ? 1 : 0);
        for (int index = 0; index < absorptionIcons; index++) {
            int heartX = absorptionStart + (index * HEART_ICON_SPACING);
            drawHeart(context, HEART_CONTAINER_TEXTURE, heartX, y);
        }
        for (int index = 0; index < healthData.absorptionFullHearts(); index++) {
            int heartX = absorptionStart + (index * HEART_ICON_SPACING);
            drawHeart(context, HEART_ABS_FULL_TEXTURE, heartX, y);
        }

        if (healthData.absorptionHalfHeart()) {
            int heartX = absorptionStart + (healthData.absorptionFullHearts() * HEART_ICON_SPACING);
            drawHeart(context, HEART_ABS_HALF_TEXTURE, heartX, y);
        }
    }

    private static void drawHeart(DrawContext context, Identifier texture, int x, int y) {
        context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, texture, x, y, HEART_ICON_SIZE, HEART_ICON_SIZE);
    }

    private void ensureHudCacheConfig() {
        int currentSignature = getHudConfigSignature();
        if (currentSignature != hudConfigSignature) {
            hudConfigSignature = currentSignature;
            hudCache.clear();
        }
    }

    private void pruneHudCacheIfNeeded() {
        if (mc.world == null || hudCache.size() <= mc.world.getPlayers().size() + 8) {
            return;
        }

        hudCache.keySet().removeIf(uuid -> mc.world.getPlayerByUuid(uuid) == null);
    }

    private int getHudConfigSignature() {
        int signature = 0;
        if (self.getValue()) signature |= 1;
        if (name.getValue()) signature |= 1 << 1;
        if (ping.getValue()) signature |= 1 << 2;
        if (health.getValue()) signature |= 1 << 3;
        if (mainHand.getValue()) signature |= 1 << 4;
        if (offHand.getValue()) signature |= 1 << 5;
        if (armor.getValue()) signature |= 1 << 6;
        return signature;
    }

    private CachedHudData getCachedHudData(PlayerEntity player, long now) {
        CachedHudData cached = hudCache.get(player.getUuid());
        if (cached != null && cached.expiresAtMs() > now) {
            return cached;
        }

        Text nameLabel = buildNameLabel(player);
        List<NametagRenderState.ItemEntry> items = buildItemEntries(player);
        CachedHudData rebuilt = new CachedHudData(
                now + HUD_CACHE_DURATION_MS,
                nameLabel,
                nameLabel != null ? mc.textRenderer.getWidth(nameLabel) : 0,
                getHealthRenderData(player),
                items,
                items.isEmpty() ? 0 : (items.size() * HUD_ITEM_SIZE) + ((items.size() - 1) * HUD_ITEM_GAP)
        );
        hudCache.put(player.getUuid(), rebuilt);
        return rebuilt;
    }

    private static void applyHudTransform(Matrix3x2fStack matrices, float screenX, float screenY, float scale) {
        matrices.translate(screenX, screenY);
        matrices.scale(scale, scale);
    }

    private float projectHudAnchor(PlayerEntity player, float tickDelta, double worldX, double worldY, double worldZ, double scaleBaseX, double scaleBaseY) {
        Vec3d localAnchor = player.getAttachments()
                .getPointNullable(EntityAttachmentType.NAME_TAG, 0, player.getLerpedYaw(tickDelta));

        double anchorX;
        double anchorY;
        double anchorZ;
        if (localAnchor == null) {
            anchorX = worldX;
            anchorY = worldY + player.getHeight() + 0.5 + HUD_ANCHOR_Y_ADJUST;
            anchorZ = worldZ;
        } else {
            anchorX = worldX + localAnchor.x;
            anchorY = worldY + localAnchor.y + HUD_ANCHOR_Y_ADJUST;
            anchorZ = worldZ + localAnchor.z;
        }

        if (!ProjectionUtil.projectToScreen(
                ProjectionUtil.modelViewMatrix,
                ProjectionUtil.projectionMatrix,
                anchorX,
                anchorY,
                anchorZ,
                screenProjection
        )) {
            return 0.0f;
        }

        if (!screenProjection.visible || screenProjection.z < 0.0 || screenProjection.z > 1.0 || screenProjection.w <= 0.0) {
            return 0.0f;
        }

        double scaleX = scaleBaseX / screenProjection.w;
        double scaleY = scaleBaseY / screenProjection.w;
        float screenScale = (float) ((scaleX + scaleY) * 0.5D);
        return Float.isFinite(screenScale) && screenScale > 0.0f ? screenScale : 0.0f;
    }

    private static boolean isMenuOpen() {
        return mc.currentScreen instanceof MenuScreen;
    }

    private int getPing(PlayerEntity player) {
        if (mc.getNetworkHandler() == null) {
            return -1;
        }

        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(player.getUuid());
        return entry != null ? entry.getLatency() : -1;
    }

    private Formatting getPingFormatting(int latency) {
        if (latency < 75) {
            return Formatting.GREEN;
        }
        if (latency < 150) {
            return Formatting.YELLOW;
        }
        return Formatting.RED;
    }

    private String stripMinecraftFormatting(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return MINECRAFT_COLOR_CODE_PATTERN.matcher(text).replaceAll("").trim();
    }

    private boolean containsLetters(String text) {
        for (int index = 0; index < text.length(); index++) {
            if (Character.isLetter(text.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private String sanitizeLogText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\n', ' ').replace('\r', ' ');
    }

    private String normalizeLookupName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record CachedHudData(
            long expiresAtMs,
            Text nameLabel,
            int nameWidth,
            HealthRenderData healthData,
            List<NametagRenderState.ItemEntry> items,
            int itemRowWidth
    ) {
        private boolean isEmpty() {
            return nameLabel == null && healthData == null && items.isEmpty();
        }
    }

    private record HealthRenderData(
            int baseHeartCount,
            int fullHearts,
            boolean halfHeart,
            int emptyHearts,
            int absorptionFullHearts,
            boolean absorptionHalfHeart,
            int totalWidth
    ) {
    }

}

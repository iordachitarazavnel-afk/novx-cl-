package com.xenon.module.modules.render;

import com.xenon.gui.notification.NotificationManager;
import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.BlocksSetting;
import com.xenon.setting.Setting;
import com.xenon.utils.RenderUtils;
import com.xenon.module.modules.client.XenonPlus;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class BlockESP extends Module {

    private static final int RESCAN_INTERVAL_TICKS = 40;
    private static final int CHUNKS_PER_TICK = 6;
    private static final long NOTIFY_COOLDOWN_MS = 750L;
    private static final int DEFAULT_ALPHA = 110;
    private static final double BOX_INSET = 0.0625;
    private static final double TRACER_START_DISTANCE = 150.0D;
    private static final double TRACER_END_DISTANCE = 24.0D;
    private static final double TRACER_BEHIND_MIN_SPREAD = 2.75D;

    private final BlocksSetting blocks = new BlocksSetting("Blocks", Blocks.SPAWNER);
    private final Setting<Boolean> notify = new Setting<>("Notification", true);
    private final Setting<Boolean> esp = new Setting<>("ESP", true);
    private final Setting<Boolean> tracers = new Setting<>("Tracers", false);

    private final Map<Long, Set<BlockPos>> cachedBlocks = new ConcurrentHashMap<>();
    private final Map<BlockPos, Block> posTypeMap = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastNotifiedAt = new ConcurrentHashMap<>();
    private final ArrayDeque<Long> scanQueue = new ArrayDeque<>();
    private final Set<Long> queuedChunks = new HashSet<>();
    private final Object queueLock = new Object();

    private volatile Set<Block> targets = Collections.emptySet();
    private long lastBlocksVersion = -1L;
    private int tickCounter = 0;
    private boolean fullRescanRequested = true;
    private ChunkPos lastCenterChunk;
    private int lastChunkRadius = -1;

    public BlockESP() {
        super("Block ESP", Category.RENDER);
        addSetting(blocks);
        addSetting(notify);
        addSetting(esp);
        addSetting(tracers);
    }

    @Override
    public void onEnable() {
        clearCaches();
        lastBlocksVersion = -1L;
        fullRescanRequested = true;
        tickCounter = 0;
        lastCenterChunk = null;
        lastChunkRadius = -1;
    }

    @Override
    public void onDisable() {
        clearCaches();
        lastCenterChunk = null;
        lastChunkRadius = -1;
    }

    @Override
    public void onTick() {
        if (mc.world == null || mc.player == null) {
            return;
        }

        updateTargets();
        if (targets.isEmpty()) {
            clearCaches();
            return;
        }

        tickCounter++;
        ChunkPos currentChunk = mc.player.getChunkPos();
        int currentChunkRadius = getChunkRadius();
        boolean forceRescan = fullRescanRequested || tickCounter % RESCAN_INTERVAL_TICKS == 0;
        if (forceRescan || lastCenterChunk == null || !lastCenterChunk.equals(currentChunk) || lastChunkRadius != currentChunkRadius) {
            rebuildLoadedChunkQueue(forceRescan);
            fullRescanRequested = false;
            lastCenterChunk = currentChunk;
            lastChunkRadius = currentChunkRadius;
        }

        for (int i = 0; i < CHUNKS_PER_TICK; i++) {
            Long chunkKey;
            synchronized (queueLock) {
                chunkKey = scanQueue.poll();
                if (chunkKey != null) {
                    queuedChunks.remove(chunkKey);
                }
            }
            if (chunkKey == null) {
                break;
            }
            int chunkX = ChunkPos.getPackedX(chunkKey);
            int chunkZ = ChunkPos.getPackedZ(chunkKey);
            WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(chunkX, chunkZ, false);
            if (chunk != null) {
                scanChunk(chunk);
            }
        }
    }

    @Override
    public void onPacketReceive(Packet<?> packet) {
        if (mc.world == null) {
            return;
        }

        if (packet instanceof ChunkDataS2CPacket chunkData) {
            queueChunk(ChunkPos.toLong(chunkData.getChunkX(), chunkData.getChunkZ()), true);
            return;
        }

        if (packet instanceof ChunkDeltaUpdateS2CPacket deltaUpdate) {
            deltaUpdate.visitUpdates((pos, state) -> queueChunk(new ChunkPos(pos).toLong(), true));
            return;
        }

        if (packet instanceof BlockUpdateS2CPacket blockUpdate) {
            queueChunk(new ChunkPos(blockUpdate.getPos()).toLong(), true);
        }
    }

    @Override
    public void onRender(MatrixStack matrices, float tickDelta) {
        if (mc.world == null || mc.player == null || cachedBlocks.isEmpty()) {
            return;
        }
        if (!esp.getValue() && !tracers.getValue()) {
            return;
        }
        Set<Block> localTargets = this.targets;
        if (localTargets.isEmpty()) {
            return;
        }

        Camera cam = RenderUtils.getCamera();
        if (cam == null) {
            return;
        }

        Vec3d camPos = RenderUtils.getCameraPos(cam);
        Vec3d cameraForward = RenderUtils.getCameraForward(cam);
        Vec3d cameraRight = RenderUtils.getCameraRight(cam);
        Vec3d cameraUp = RenderUtils.getCameraUp(cameraForward, cameraRight);
        Vec3d tracerStart = cameraForward.multiply(TRACER_START_DISTANCE);
        double maxDistanceSq = getMaxRenderDistanceSq();
        RenderUtils.WorldBatch batch = RenderUtils.beginWorldBatch(matrices);
        boolean rendered = false;

        // Must be on render thread and placed after the batch setup but before we emit any vertices.
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        try {
            for (Set<BlockPos> positions : cachedBlocks.values()) {
                for (BlockPos pos : positions) {
                    if (pos.getSquaredDistance(mc.player.getX(), mc.player.getY(), mc.player.getZ()) > maxDistanceSq) {
                        continue;
                    }

                    Block block = posTypeMap.get(pos);
                    if (block == null) {
                        BlockState state = mc.world.getBlockState(pos);
                        block = state.getBlock();
                    }
                    if (!localTargets.contains(block)) {
                        continue;
                    }

                    Color color = getBlockColor(block, DEFAULT_ALPHA);
                    double x = pos.getX() - camPos.x;
                    double y = pos.getY() - camPos.y;
                    double z = pos.getZ() - camPos.z;
                    Color outline = new Color(color.getRed(), color.getGreen(), color.getBlue(), 255);

                    if (esp.getValue()) {
                        batch.renderOutlineBox(
                                x + BOX_INSET,
                                y + BOX_INSET,
                                z + BOX_INSET,
                                x + 1.0 - BOX_INSET,
                                y + 1.0 - BOX_INSET,
                                z + 1.0 - BOX_INSET,
                                outline
                        );
                        batch.renderFilledBox(
                                x + BOX_INSET,
                                y + BOX_INSET,
                                z + BOX_INSET,
                                x + 1.0 - BOX_INSET,
                                y + 1.0 - BOX_INSET,
                                z + 1.0 - BOX_INSET,
                                color
                        );
                    }
                    if (tracers.getValue()) {
                        Vec3d relativeTarget = new Vec3d(x + 0.5D, y + 0.5D, z + 0.5D);
                    Vec3d tracerEnd = RenderUtils.getSpreadTracerEnd(
                            relativeTarget,
                            cameraForward,
                            cameraRight,
                            cameraUp,
                            TRACER_END_DISTANCE,
                            TRACER_BEHIND_MIN_SPREAD
                    );
                    batch.renderLine(outline, tracerStart, tracerEnd, XenonPlus.tracerLineWidth());
                }
                rendered = true;
            }
        }

            if (rendered) {
                batch.flush();
            }
        } finally {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
        }
    }

    private void updateTargets() {
        long version = blocks.getVersion();
        if (version == lastBlocksVersion) {
            return;
        }

        lastBlocksVersion = version;
        // Snapshot: only blocks explicitly selected by the user.
        targets = Set.copyOf(blocks.getSelectedBlocks());
        clearCaches();
        fullRescanRequested = true;
    }

    public boolean isSelected(Block block) {
        updateTargets();
        return blocks.contains(block);
    }

    public int getSelectedCount() {
        updateTargets();
        return blocks.size();
    }

    private void rebuildLoadedChunkQueue(boolean forceRescan) {
        if (mc.world == null || mc.player == null) {
            return;
        }

        int viewDist = getChunkRadius();
        ChunkPos center = mc.player.getChunkPos();
        List<WorldChunk> loadedChunks = new ArrayList<>();
        Set<Long> loadedChunkKeys = new HashSet<>();
        for (int x = -viewDist; x <= viewDist; x++) {
            for (int z = -viewDist; z <= viewDist; z++) {
                WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(center.x + x, center.z + z, false);
                if (chunk != null) {
                    loadedChunks.add(chunk);
                    loadedChunkKeys.add(chunk.getPos().toLong());
                }
            }
        }

        loadedChunks.sort(Comparator.comparingInt(chunk -> getChunkDistanceSq(center, chunk.getPos())));

        synchronized (queueLock) {
            scanQueue.removeIf(chunkKey -> !loadedChunkKeys.contains(chunkKey));
            queuedChunks.retainAll(loadedChunkKeys);
            for (WorldChunk chunk : loadedChunks) {
                long chunkKey = chunk.getPos().toLong();
                boolean shouldQueue = forceRescan || !cachedBlocks.containsKey(chunkKey);
                if (shouldQueue && queuedChunks.add(chunkKey)) {
                    scanQueue.addLast(chunkKey);
                }
            }
        }

        pruneOutOfRange(center, viewDist);
    }

    private void queueChunk(long chunkKey, boolean prioritized) {
        synchronized (queueLock) {
            if (prioritized && queuedChunks.contains(chunkKey)) {
                scanQueue.remove(chunkKey);
                scanQueue.addFirst(chunkKey);
                return;
            }

            if (!queuedChunks.add(chunkKey)) {
                return;
            }

            if (prioritized) {
                scanQueue.addFirst(chunkKey);
            } else {
                scanQueue.add(chunkKey);
            }
        }
    }

    private void scanChunk(WorldChunk chunk) {
        Set<Block> localTargets = this.targets;
        if (localTargets.isEmpty()) {
            return;
        }
        int worldBottom = mc.world.getBottomY();
        int worldTopExclusive = mc.world.getBottomY() + mc.world.getHeight();
        int minSection = mc.world.getBottomSectionCoord();

        ChunkPos chunkPos = chunk.getPos();
        long chunkKey = chunkPos.toLong();
        Set<BlockPos> oldSet = cachedBlocks.get(chunkKey);
        Set<BlockPos> newSet = new HashSet<>();
        Block firstNewBlock = null;
        BlockPos firstNewPos = null;

        ChunkSection[] sections = chunk.getSectionArray();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            ChunkSection section = sections[sectionIndex];
            if (section == null || section.isEmpty()) {
                continue;
            }

            int sectionYBase = (minSection + sectionIndex) * 16;
            if (sectionYBase + 16 <= worldBottom || sectionYBase >= worldTopExclusive) {
                continue;
            }

            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int localY = 0; localY < 16; localY++) {
                        BlockState state = section.getBlockState(localX, localY, localZ);
                        Block block = state.getBlock();
                        if (!localTargets.contains(block)) {
                            continue;
                        }

                        BlockPos pos = new BlockPos(chunkPos.getStartX() + localX, sectionYBase + localY, chunkPos.getStartZ() + localZ);
                        newSet.add(pos);
                        posTypeMap.put(pos, block);
                        if (firstNewBlock == null && (oldSet == null || !oldSet.contains(pos))) {
                            firstNewBlock = block;
                            firstNewPos = pos;
                        }
                    }
                }
            }
        }

        if (oldSet != null) {
            for (BlockPos pos : oldSet) {
                if (!newSet.contains(pos)) {
                    posTypeMap.remove(pos);
                }
            }
        }

        if (newSet.isEmpty()) {
            removeChunkCache(chunkKey);
            lastNotifiedAt.remove(chunkKey);
            return;
        }

        cachedBlocks.put(chunkKey, newSet);
        if (firstNewBlock != null && firstNewPos != null) {
            maybeNotify(chunkKey, firstNewBlock, firstNewPos, chunkPos);
        }
    }

    private void maybeNotify(long chunkKey, Block block, BlockPos pos, ChunkPos chunkPos) {
        if (!notify.getValue() || mc.player == null) {
            return;
        }

        long now = System.currentTimeMillis();
        long last = lastNotifiedAt.getOrDefault(chunkKey, 0L);
        if (now - last < NOTIFY_COOLDOWN_MS) {
            return;
        }

        lastNotifiedAt.put(chunkKey, now);
        NotificationManager.INSTANCE.push(
                safeBlockName(block) + " found",
                "X " + pos.getX() + "  Y " + pos.getY() + "  Z " + pos.getZ(),
                createNotificationStack(block),
                getBlockColor(block, 255).getRGB()
        );
        mc.world.playSound(
                mc.player,
                mc.player.getX(),
                mc.player.getY(),
                mc.player.getZ(),
                SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                SoundCategory.MASTER,
                0.6f,
                0.95f
        );
    }

    private ItemStack createNotificationStack(Block block) {
        ItemStack stack = new ItemStack(block.asItem());
        return stack.isEmpty() ? ItemStack.EMPTY : stack;
    }

    private int getChunkDistanceSq(ChunkPos origin, ChunkPos target) {
        int dx = target.x - origin.x;
        int dz = target.z - origin.z;
        return dx * dx + dz * dz;
    }

    private int getChunkRadius() {
        return mc.options.getClampedViewDistance();
    }

    private double getMaxRenderDistanceSq() {
        double maxDistance = (getChunkRadius() * 16.0D) + 16.0D;
        return maxDistance * maxDistance;
    }

    private void pruneOutOfRange(ChunkPos center, int chunkRadius) {
        List<Long> toRemove = new ArrayList<>();
        for (Long chunkKey : cachedBlocks.keySet()) {
            ChunkPos chunkPos = new ChunkPos(ChunkPos.getPackedX(chunkKey), ChunkPos.getPackedZ(chunkKey));
            if (Math.abs(chunkPos.x - center.x) > chunkRadius || Math.abs(chunkPos.z - center.z) > chunkRadius) {
                toRemove.add(chunkKey);
            }
        }

        for (Long chunkKey : toRemove) {
            removeChunkCache(chunkKey);
            lastNotifiedAt.remove(chunkKey);
        }
    }

    private void removeChunkCache(long chunkKey) {
        Set<BlockPos> removed = cachedBlocks.remove(chunkKey);
        if (removed == null) {
            return;
        }

        for (BlockPos pos : removed) {
            posTypeMap.remove(pos);
        }
    }

    private Color getBlockColor(Block block, int alphaValue) {
        Identifier id = Registries.BLOCK.getId(block);
        String path = id == null ? "" : id.getPath();

        if (block == Blocks.SPAWNER) {
            return new Color(138, 126, 166, alphaValue);
        }
        if (path.contains("diamond")) {
            return new Color(0, 255, 255, alphaValue);
        }
        if (path.contains("ancient_debris")) {
            return new Color(196, 120, 72, alphaValue);
        }
        if (path.contains("emerald")) {
            return new Color(0, 255, 127, alphaValue);
        }
        if (path.contains("gold")) {
            return new Color(255, 215, 0, alphaValue);
        }
        if (path.contains("iron")) {
            return new Color(213, 213, 213, alphaValue);
        }
        if (path.contains("redstone")) {
            return new Color(255, 70, 70, alphaValue);
        }
        if (path.contains("lapis")) {
            return new Color(70, 110, 255, alphaValue);
        }
        return new Color(255, 255, 0, alphaValue);
    }

    private String safeBlockName(Block block) {
        try {
            return block.getName().getString();
        } catch (Exception ignored) {
            Identifier id = Registries.BLOCK.getId(block);
            return id == null ? "Block" : id.toString();
        }
    }

    private void clearCaches() {
        cachedBlocks.clear();
        posTypeMap.clear();
        lastNotifiedAt.clear();
        synchronized (queueLock) {
            scanQueue.clear();
            queuedChunks.clear();
        }
    }
}

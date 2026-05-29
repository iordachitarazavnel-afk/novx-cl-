package com.xenon.module.modules.donut;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.BooleanSetting;
import com.xenon.setting.NumberSetting;
import com.xenon.utils.RenderUtils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.ChunkSection;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ConnectedDebug extends Module {

    private final NumberSetting  scanRadius      = new NumberSetting("Scan Radius", 1.0, 16.0, 6.0, 1.0);
    private final NumberSetting  maxY            = new NumberSetting("Max Y", -64.0, 64.0, 0.0, 1.0);
    private final NumberSetting  minConnected    = new NumberSetting("Min Connected", 1.0, 64.0, 2.0, 1.0);
    private final NumberSetting  chunksPerTick   = new NumberSetting("Chunks Per Tick", 1.0, 12.0, 3.0, 1.0);
    private final BooleanSetting detectRails     = new BooleanSetting("Rails", true);
    private final BooleanSetting detectRedstone  = new BooleanSetting("Redstone", true);
    private final BooleanSetting detectHoppers   = new BooleanSetting("Hoppers", true);
    private final BooleanSetting deepslateOnly   = new BooleanSetting("Deepslate Only", true);
    private final BooleanSetting bypassRenderLimit = new BooleanSetting("Bypass Render Limit", true);
    private final NumberSetting  minDeepslate    = new NumberSetting("Min Deepslate", 1.0, 10.0, 2.0, 1.0);
    private final BooleanSetting chatPing        = new BooleanSetting("Chat Ping", true);
    private final BooleanSetting chunkMark       = new BooleanSetting("Chunk Mark", true);
    private final NumberSetting  chunkMarkY      = new NumberSetting("Chunk Y Level", -64.0, 320.0, 55.0, 1.0);
    private final BooleanSetting esp             = new BooleanSetting("ESP", true);
    private final BooleanSetting tracers         = new BooleanSetting("Tracers", false);

    private static final Color CHUNK_COLOR  = new Color(255, 140, 40,  200);
    private static final Color ESP_COLOR    = new Color(255, 160, 60,  170);
    private static final Color TRACER_COLOR = new Color(255, 180, 80,  220);

    private static final Set<Block> RAIL_BLOCKS = Set.of(
            Blocks.RAIL, Blocks.POWERED_RAIL, Blocks.DETECTOR_RAIL, Blocks.ACTIVATOR_RAIL);

    private static final Set<Block> REDSTONE_BLOCKS = Set.of(
            Blocks.REDSTONE_WIRE, Blocks.REDSTONE_TORCH, Blocks.REDSTONE_WALL_TORCH,
            Blocks.REPEATER, Blocks.COMPARATOR, Blocks.PISTON, Blocks.STICKY_PISTON,
            Blocks.OBSERVER, Blocks.DISPENSER, Blocks.DROPPER, Blocks.HOPPER,
            Blocks.TNT, Blocks.LEVER, Blocks.STONE_BUTTON, Blocks.OAK_BUTTON,
            Blocks.SPRUCE_BUTTON, Blocks.BIRCH_BUTTON, Blocks.JUNGLE_BUTTON,
            Blocks.ACACIA_BUTTON, Blocks.DARK_OAK_BUTTON, Blocks.TRIPWIRE_HOOK,
            Blocks.TRAPPED_CHEST, Blocks.DAYLIGHT_DETECTOR);

    private static final Set<Block> HOPPER_BLOCKS = Set.of(Blocks.HOPPER);

    private static final int[][] CONNECTED_DIRS = {
            {1,0,0}, {-1,0,0}, {0,1,0}, {0,-1,0}, {0,0,1}, {0,0,-1}
    };

    private final Map<ChunkPos, Set<BlockPos>> connectedByChunk = new ConcurrentHashMap<>();
    private final Set<ChunkPos> flaggedChunks  = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> notifiedChunks = ConcurrentHashMap.newKeySet();
    private final Queue<ChunkPos> scanQueue    = new ArrayDeque<>();
    private int tickCounter = 0;

    public ConnectedDebug() {
        super("ConnectedDebug", Category.DONUT);
        addSetting(scanRadius);
        addSetting(maxY);
        addSetting(minConnected);
        addSetting(chunksPerTick);
        addSetting(detectRails);
        addSetting(detectRedstone);
        addSetting(detectHoppers);
        addSetting(deepslateOnly);
        addSetting(bypassRenderLimit);
        addSetting(minDeepslate);
        addSetting(chatPing);
        addSetting(chunkMark);
        addSetting(chunkMarkY);
        addSetting(esp);
        addSetting(tracers);
    }

    @Override
    public void onEnable() {
        connectedByChunk.clear();
        flaggedChunks.clear();
        notifiedChunks.clear();
        scanQueue.clear();
        enqueueNearbyChunks();
        burstScan();
        tickCounter = 0;
    }

    @Override
    public void onDisable() {
        connectedByChunk.clear();
        flaggedChunks.clear();
        notifiedChunks.clear();
        scanQueue.clear();
    }

    @Override
    public void onTick() {
        if (mc.world == null || mc.player == null) return;

        tickCounter++;
        int perTick = chunksPerTick.getIntValue();
        for (int i = 0; i < perTick && !scanQueue.isEmpty(); i++) {
            ChunkPos cp = scanQueue.poll();
            WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(cp.x, cp.z, false);
            if (chunk == null) continue;
            scanChunk(chunk);
        }

        if (scanQueue.isEmpty() && tickCounter % 200 == 0) {
            enqueueNearbyChunks();
        }
    }

    @Override
    public void onPacketReceive(net.minecraft.network.packet.Packet<?> packet) {
        if (packet instanceof net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket p) {
            ChunkPos cp = new ChunkPos(p.getChunkX(), p.getChunkZ());
            if (isWithinRadius(cp)) scanQueue.add(cp);
        }
    }

    @Override
    public void onRender(MatrixStack matrices, float tickDelta) {
        if (mc.world == null || mc.player == null || flaggedChunks.isEmpty()) return;

        Camera cam = RenderUtils.getCamera();
        if (cam == null) return;

        Vec3d camPos      = RenderUtils.getCameraPos(cam);
        Vec3d camForward  = RenderUtils.getCameraForward(cam);
        Vec3d camRight    = RenderUtils.getCameraRight(cam);
        Vec3d camUp       = RenderUtils.getCameraUp(camForward, camRight);

        matrices.push();
        RenderUtils.WorldBatch batch = RenderUtils.beginWorldBatch(matrices);

        if (chunkMark.getValue()) {
            double yLevel = chunkMarkY.getValue();
            for (ChunkPos cp : flaggedChunks) {
                double x1 = cp.getStartX();
                double z1 = cp.getStartZ();
                batch.renderOutlineBox(x1, yLevel, z1, x1 + 16.0, yLevel + 0.05, z1 + 16.0, CHUNK_COLOR);
                batch.renderFilledBox(x1, yLevel, z1, x1 + 16.0, yLevel + 0.05, z1 + 16.0, CHUNK_COLOR);
            }
        }

        if (esp.getValue()) {
            for (Set<BlockPos> positions : connectedByChunk.values()) {
                for (BlockPos pos : positions) {
                    double x = pos.getX() - camPos.x;
                    double y = pos.getY() - camPos.y;
                    double z = pos.getZ() - camPos.z;
                    batch.renderOutlineBox(x, y, z, x + 1.0, y + 1.0, z + 1.0, ESP_COLOR);
                    batch.renderFilledBox(x, y, z, x + 1.0, y + 1.0, z + 1.0, ESP_COLOR);
                }
            }
        }

        if (tracers.getValue()) {
            BlockPos nearest  = null;
            double   nearDist = Double.MAX_VALUE;
            for (Set<BlockPos> positions : connectedByChunk.values()) {
                for (BlockPos pos : positions) {
                    double d = mc.player.squaredDistanceTo(pos.getX(), pos.getY(), pos.getZ());
                    if (d < nearDist) { nearDist = d; nearest = pos; }
                }
            }
            if (nearest != null) {
                Vec3d tracerStart = camForward.multiply(150.0);
                Vec3d target = new Vec3d(
                        nearest.getX() + 0.5 - camPos.x,
                        nearest.getY() + 0.5 - camPos.y,
                        nearest.getZ() + 0.5 - camPos.z);
                Vec3d tracerEnd = RenderUtils.getSpreadTracerEnd(target, camForward, camRight, camUp, 24.0, 2.75);
                batch.renderLine(TRACER_COLOR, tracerStart, tracerEnd, 2.0f);
            }
        }

        batch.flush();
        matrices.pop();
    }

    private void burstScan() {
        for (int i = 0; i < 64 && !scanQueue.isEmpty(); i++) {
            ChunkPos cp = scanQueue.poll();
            if (mc.world == null) break;
            WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(cp.x, cp.z, false);
            if (chunk != null) scanChunk(chunk);
        }
    }

    private void enqueueNearbyChunks() {
        if (mc.world == null || mc.player == null) return;
        int radius = scanRadius.getIntValue();
        ChunkPos center = mc.player.getChunkPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos cp = new ChunkPos(center.x + dx, center.z + dz);
                if (!flaggedChunks.contains(cp) && !scanQueue.contains(cp))
                    scanQueue.add(cp);
            }
        }
    }

    private boolean isWithinRadius(ChunkPos cp) {
        if (mc.player == null) return false;
        ChunkPos center = mc.player.getChunkPos();
        int radius = scanRadius.getIntValue();
        return Math.abs(cp.x - center.x) <= radius && Math.abs(cp.z - center.z) <= radius;
    }

    private void scanChunk(WorldChunk chunk) {
        if (mc.world == null) return;
        ChunkPos chunkPos = chunk.getPos();
        int yLim           = maxY.getIntValue();
        boolean checkDeep  = deepslateOnly.getValue();
        int coverReq       = minDeepslate.getIntValue();
        boolean bypass     = bypassRenderLimit.getValue();

        // construieste setul de blocuri target
        HashSet<Block> targets = new HashSet<>();
        if (detectRails.getValue())    targets.addAll(RAIL_BLOCKS);
        if (detectRedstone.getValue()) targets.addAll(REDSTONE_BLOCKS);
        if (detectHoppers.getValue())  targets.addAll(HOPPER_BLOCKS);
        if (targets.isEmpty()) return;

        HashSet<BlockPos> allMatches = new HashSet<>();
        int minSection = mc.world.getBottomSectionCoord();
        ChunkSection[] sections = chunk.getSectionArray();

        for (int sectionIdx = 0; sectionIdx < sections.length; sectionIdx++) {
            ChunkSection section = sections[sectionIdx];
            int sectionYBase = (minSection + sectionIdx) * 16;
            if (section == null || section.isEmpty() || sectionYBase > yLim) continue;

            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int ly = 0; ly < 16; ly++) {
                        int wy = sectionYBase + ly;
                        if (wy > yLim) break;

                        BlockState state = section.getBlockState(lx, ly, lz);
                        Block block = state.getBlock();
                        if (!targets.contains(block)) continue;

                        BlockPos pos = new BlockPos(chunkPos.getStartX() + lx, wy, chunkPos.getStartZ() + lz);
                        if (checkDeep && !bypass && !hasDeepslateCoverAbove(pos, coverReq)) continue;
                        allMatches.add(pos);
                    }
                }
            }
        }

        if (allMatches.size() < minConnected.getIntValue()) {
            connectedByChunk.remove(chunkPos);
            flaggedChunks.remove(chunkPos);
            notifiedChunks.remove(chunkPos);
            return;
        }

        int connectedCount = countConnected(allMatches);
        if (connectedCount >= minConnected.getIntValue()) {
            connectedByChunk.put(chunkPos, allMatches);
            if (flaggedChunks.add(chunkPos) && notifiedChunks.add(chunkPos)) {
                if (chatPing.getValue() && mc.player != null) {
                    mc.player.sendMessage(
                        net.minecraft.text.Text.literal(
                            "§6[ConnectedDebug] §7Chunk §f" + chunkPos.x + ", " + chunkPos.z +
                            " §7(" + connectedCount + " connected)"),
                        false);
                }
                com.xenon.gui.notification.NotificationManager.INSTANCE.push(
                        "ConnectedDebug",
                        "Chunk " + chunkPos.x + ", " + chunkPos.z + " (" + connectedCount + " connected)",
                        new ItemStack(Items.COMPARATOR),
                        0xFFFF8C28
                );
            }
        } else {
            connectedByChunk.remove(chunkPos);
            flaggedChunks.remove(chunkPos);
            notifiedChunks.remove(chunkPos);
        }
    }

    private int countConnected(Set<BlockPos> positions) {
        if (positions.isEmpty()) return 0;
        HashSet<BlockPos> visited = new HashSet<>();
        int totalConnected = 0;

        for (BlockPos start : positions) {
            if (visited.contains(start)) continue;
            int groupSize = 0;
            ArrayDeque<BlockPos> stack = new ArrayDeque<>();
            stack.push(start);
            visited.add(start);
            while (!stack.isEmpty()) {
                BlockPos cur = stack.pop();
                groupSize++;
                for (int[] dir : CONNECTED_DIRS) {
                    BlockPos neighbor = cur.add(dir[0], dir[1], dir[2]);
                    if (positions.contains(neighbor) && visited.add(neighbor))
                        stack.push(neighbor);
                }
            }
            if (groupSize >= 2) totalConnected += groupSize;
        }
        return totalConnected;
    }

    private boolean hasDeepslateCoverAbove(BlockPos pos, int required) {
        if (mc.world == null) return false;
        int count    = 0;
        int worldTop = mc.world.getBottomY() + mc.world.getHeight() - 1;
        for (int dy = 1; dy <= 8; dy++) {
            BlockPos above = pos.up(dy);
            if (above.getY() > worldTop) break;
            BlockState state = mc.world.getBlockState(above);
            if (state.isOf(Blocks.DEEPSLATE) || state.isOf(Blocks.DEEPSLATE_BRICKS)) {
                if (++count >= required) return true;
            }
        }
        return false;
    }
}

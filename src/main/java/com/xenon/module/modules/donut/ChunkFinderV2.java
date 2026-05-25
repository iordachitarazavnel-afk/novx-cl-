package com.xenon.module.modules.donut;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.BooleanSetting;
import com.xenon.setting.NumberSetting;
import com.xenon.utils.RenderUtils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PillarBlock;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockPos.Mutable;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Direction.Axis;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.WorldChunk;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class ChunkFinderV2 extends Module {

    private final BooleanSetting fill              = new BooleanSetting("Fill", true);
    private final BooleanSetting outline           = new BooleanSetting("Outline", true);
    private final BooleanSetting tracers           = new BooleanSetting("Tracers", true);
    private final BooleanSetting highlightBlocks   = new BooleanSetting("Highlight Blocks", true);
    private final BooleanSetting chatFeedback      = new BooleanSetting("Chat Feedback", true);
    private final NumberSetting  scanRadius        = new NumberSetting("Scan Radius", 2.0, 12.0, 6.0, 1.0);
    private final NumberSetting  minScanY          = new NumberSetting("Min Y", -64.0, 200.0, -64.0, 1.0);
    private final NumberSetting  maxScanY          = new NumberSetting("Max Y", -64.0, 320.0, 96.0, 1.0);
    private final NumberSetting  maxRenderedChunks = new NumberSetting("Max Chunks", 1.0, 128.0, 32.0, 1.0);
    private final NumberSetting  maxRenderedBlocks = new NumberSetting("Max Blocks", 0.0, 256.0, 96.0, 1.0);

    private final Set<ChunkPos>                          flaggedChunks     = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos>                          scannedChunks     = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos>                          notifiedChunks    = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<ChunkPos, Set<BlockPos>> flaggedBlocks     = new ConcurrentHashMap<>();
    private final ConcurrentMap<ChunkPos, ChunkAnalysis> chunkAnalysisData = new ConcurrentHashMap<>();
    private final Set<ChunkPos>                          cherryGroveChunks = ConcurrentHashMap.newKeySet();

    private ExecutorService  scannerThread;
    private Future<?>        currentScanTask;
    private volatile boolean shouldStop;
    private long    lastScanTime;
    private long    lastCleanupTime;
    private boolean isPausedDueToLag;
    private long    lagPauseStartTime;
    private boolean isDonutSmp;
    private boolean hasCheckedServer;
    private ChunkPos lastCherryFlaggedChunk;
    private long     cherryGroveEnterTime;
    private boolean  inCherryGrove;
    private boolean  cherryFlagPending;

    public ChunkFinderV2() {
        super("ChunkFinderV2", Category.DONUT);
        addSetting(fill);
        addSetting(outline);
        addSetting(tracers);
        addSetting(highlightBlocks);
        addSetting(chatFeedback);
        addSetting(scanRadius);
        addSetting(minScanY);
        addSetting(maxScanY);
        addSetting(maxRenderedChunks);
        addSetting(maxRenderedBlocks);
    }

    @Override
    public void onEnable() {
        clearAllData();
        shouldStop             = false;
        hasCheckedServer       = false;
        isDonutSmp             = false;
        inCherryGrove          = false;
        cherryFlagPending      = false;
        lastCherryFlaggedChunk = null;
        scannerThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Xenon-ChunkFinderV2");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        scheduleChunkScan();
    }

    @Override
    public void onDisable() {
        shouldStop = true;
        if (scannerThread != null) {
            scannerThread.shutdownNow();
            scannerThread = null;
        }
        clearAllData();
    }

    @Override
    public void onTick() {
        if (mc.world == null || mc.player == null) return;

        checkServerIp();

        long now = System.currentTimeMillis();

        if (mc.getCurrentFps() < 8 && !isPausedDueToLag) {
            isPausedDueToLag  = true;
            lagPauseStartTime = now;
        }

        if (isPausedDueToLag) {
            if (now - lagPauseStartTime < 300L) return;
            isPausedDueToLag = false;
        }

        if (isDonutSmp) checkCherryGroveBiome(now);

        if (now - lastScanTime > 250L) {
            scheduleChunkScan();
            lastScanTime = now;
        }

        if (now - lastCleanupTime > 1500L) {
            cleanupDistantChunks();
            lastCleanupTime = now;
        }
    }

    @Override
    public void onPacketReceive(net.minecraft.network.packet.Packet<?> packet) {
        if (mc.world == null) return;
        if (packet instanceof net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket p) {
            ChunkPos chunkPos = new ChunkPos(p.getChunkX(), p.getChunkZ());
            scannedChunks.remove(chunkPos);
            flaggedChunks.remove(chunkPos);
            flaggedBlocks.remove(chunkPos);
            chunkAnalysisData.remove(chunkPos);
            notifiedChunks.remove(chunkPos);
            scheduleChunkScan();
        }
    }

    @Override
    public void onRender(MatrixStack matrices, float tickDelta) {
        if (mc.player == null || mc.world == null || flaggedChunks.isEmpty()) return;

        Camera camera = RenderUtils.getCamera();
        if (camera == null) return;

        Vec3d camPos = RenderUtils.getCameraPos(camera);

        List<ChunkPos> chunksToRender = flaggedChunks.stream()
                .sorted(Comparator.comparingDouble(this::distanceSqToChunkCenter))
                .limit((long) maxRenderedChunks.getIntValue())
                .toList();

        matrices.push();
        RenderUtils.WorldBatch batch = RenderUtils.beginWorldBatch(matrices);

        int renderedBlocks = 0;
        for (ChunkPos chunkPos : chunksToRender) {
            ChunkAnalysis analysis = chunkAnalysisData.get(chunkPos);
            Color accent = getChunkColor(analysis);
            drawChunkHighlight(batch, chunkPos, accent, camPos);

            if (highlightBlocks.getValue()) {
                Set<BlockPos> blocks = flaggedBlocks.get(chunkPos);
                if (blocks == null || blocks.isEmpty()) continue;
                for (BlockPos blockPos : blocks) {
                    if (renderedBlocks >= maxRenderedBlocks.getIntValue()) break;
                    drawBlockMarker(batch, blockPos, accent, camPos);
                    renderedBlocks++;
                }
            }
        }

        batch.flush();
        matrices.pop();
    }

    private void scheduleChunkScan() {
        if (shouldStop || scannerThread == null || scannerThread.isShutdown() || isPausedDueToLag) return;
        if (currentScanTask == null || currentScanTask.isDone()) {
            currentScanTask = scannerThread.submit(this::scanChunksBackground);
        }
    }

    private void scanChunksBackground() {
        if (shouldStop || mc.world == null || mc.player == null) return;

        for (WorldChunk chunk : getLoadedChunks()) {
            if (shouldStop || chunk == null) return;

            ChunkPos chunkPos = chunk.getPos();
            if (scannedChunks.contains(chunkPos)) continue;

            ChunkAnalysis analysis = analyzeChunk(chunk);
            scannedChunks.add(chunkPos);

            if (analysis.flaggedBlocks.isEmpty()) {
                flaggedChunks.remove(chunkPos);
                flaggedBlocks.remove(chunkPos);
                chunkAnalysisData.remove(chunkPos);
                continue;
            }

            flaggedChunks.add(chunkPos);
            flaggedBlocks.put(chunkPos, analysis.flaggedBlocks);
            chunkAnalysisData.put(chunkPos, analysis);

            if (notifiedChunks.add(chunkPos)) {
                notifyChunkFound(chunkPos, analysis.reasonLabel());
            }
        }
    }

    private ChunkAnalysis analyzeChunk(WorldChunk chunk) {
        ChunkAnalysis analysis = new ChunkAnalysis();
        ChunkPos chunkPos = chunk.getPos();
        int startX      = chunkPos.getStartX();
        int startZ      = chunkPos.getStartZ();
        int worldBottom = mc.world.getBottomY();
        int worldTop    = mc.world.getBottomY() + mc.world.getHeight() - 1;
        int yMin = Math.max(worldBottom, Math.min(minScanY.getIntValue(), maxScanY.getIntValue()));
        int yMax = Math.min(worldTop,    Math.max(minScanY.getIntValue(), maxScanY.getIntValue()));

        for (int x = startX; x < startX + 16; x++) {
            for (int z = startZ; z < startZ + 16; z++) {
                for (int y = yMin; y <= yMax; y++) {
                    if (shouldStop) return analysis;

                    BlockPos pos     = new BlockPos(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    Block block      = state.getBlock();

                    if (block == Blocks.ANCIENT_DEBRIS
                            && isBlockCovered(chunk, pos)
                            && isPositionUnderground(pos)) {
                        analysis.coveredAncientDebris++;
                        analysis.flaggedBlocks.add(pos);
                    }

                    if (block == Blocks.DEEPSLATE && state.contains(PillarBlock.AXIS)) {
                        Axis axis = state.get(PillarBlock.AXIS);
                        if ((axis == Axis.X || axis == Axis.Z)
                                && isBlockCovered(chunk, pos)
                                && isPositionUnderground(pos)) {
                            analysis.rotatedDeepslate++;
                            analysis.flaggedBlocks.add(pos);
                        }
                    }
                }
            }
        }

        if (analysis.coveredAncientDebris > 0)
            analysis.reasons.add("Covered Ancient Debris x" + analysis.coveredAncientDebris);
        if (analysis.rotatedDeepslate > 0)
            analysis.reasons.add("Rotated Deepslate x" + analysis.rotatedDeepslate);

        BlockPos dioritePos = checkHasDioriteVein(chunkPos);
        if (dioritePos != null) {
            analysis.hasDioriteVein = true;
            analysis.flaggedBlocks.add(dioritePos);
            analysis.reasons.add("Hidden Diorite Vein");
        }

        BlockPos vinePos = checkHasLongVine(chunkPos);
        if (vinePos != null) {
            analysis.hasLongVine = true;
            analysis.flaggedBlocks.add(vinePos);
            analysis.reasons.add("Long Vine");
        }

        BlockPos kelpPos = checkAllKelpFullyGrown(chunkPos);
        if (kelpPos != null) {
            analysis.hasFullKelp = true;
            analysis.flaggedBlocks.add(kelpPos);
            analysis.reasons.add("Full Kelp");
        }

        if (cherryGroveChunks.contains(chunkPos)) {
            analysis.cherryGrove = true;
            analysis.reasons.add("Cherry Grove");
        }

        return analysis;
    }

    private void checkServerIp() {
        if (hasCheckedServer) return;
        try {
            if (mc.getCurrentServerEntry() != null) {
                String address = mc.getCurrentServerEntry().address;
                isDonutSmp = address != null && address.toLowerCase().contains("donutsmp");
            } else {
                isDonutSmp = false;
            }
        } catch (Exception ignored) {
            isDonutSmp = false;
        }
        hasCheckedServer = true;
    }

    private void checkCherryGroveBiome(long currentTime) {
        BlockPos playerPos = mc.player.getBlockPos();
        RegistryEntry<Biome> biomeEntry = mc.world.getBiome(playerPos);
        boolean isCurrentlyCherryGrove = isCherryGroveBiome(biomeEntry);

        if (isCurrentlyCherryGrove && !inCherryGrove) {
            inCherryGrove        = true;
            cherryFlagPending    = true;
            cherryGroveEnterTime = currentTime;
        } else if (!isCurrentlyCherryGrove && inCherryGrove) {
            inCherryGrove     = false;
            cherryFlagPending = false;
        }

        if (!cherryFlagPending || !inCherryGrove || currentTime - cherryGroveEnterTime < 200L) return;

        ChunkPos currentChunk = new ChunkPos(playerPos);
        if (shouldFlagCherryChunk(currentChunk)) {
            cherryGroveChunks.add(currentChunk);
            flaggedChunks.add(currentChunk);
            flaggedBlocks.putIfAbsent(currentChunk, new LinkedHashSet<>());
            chunkAnalysisData.computeIfAbsent(currentChunk, ignored -> new ChunkAnalysis()).cherryGrove = true;
            if (notifiedChunks.add(currentChunk)) {
                notifyChunkFound(currentChunk, "Cherry Grove");
            }
            lastCherryFlaggedChunk = currentChunk;
        }
        cherryFlagPending = false;
    }

    private boolean isCherryGroveBiome(RegistryEntry<Biome> biomeEntry) {
        if (biomeEntry == null) return false;
        String key = biomeEntry.getKey().map(e -> e.getValue().toString()).orElse("");
        return key.contains("cherry_grove") || key.contains("cherry");
    }

    private boolean shouldFlagCherryChunk(ChunkPos current) {
        if (lastCherryFlaggedChunk == null) return true;
        double dx = (current.x - lastCherryFlaggedChunk.x) * 16.0;
        double dz = (current.z - lastCherryFlaggedChunk.z) * 16.0;
        return Math.sqrt(dx * dx + dz * dz) >= 100.0;
    }

    private void cleanupDistantChunks() {
        if (mc.player == null) return;
        int radius = getEffectiveScanRadius() + 2;
        ChunkPos playerChunk = mc.player.getChunkPos();
        flaggedChunks.removeIf(c -> isOutsideRadius(c, playerChunk, radius));
        scannedChunks.removeIf(c -> isOutsideRadius(c, playerChunk, radius));
        notifiedChunks.removeIf(c -> isOutsideRadius(c, playerChunk, radius));
        cherryGroveChunks.removeIf(c -> isOutsideRadius(c, playerChunk, radius));
        flaggedBlocks.entrySet().removeIf(e -> isOutsideRadius(e.getKey(), playerChunk, radius));
        chunkAnalysisData.entrySet().removeIf(e -> isOutsideRadius(e.getKey(), playerChunk, radius));
    }

    private boolean isOutsideRadius(ChunkPos candidate, ChunkPos center, int radius) {
        return Math.abs(candidate.x - center.x) > radius || Math.abs(candidate.z - center.z) > radius;
    }

    private List<WorldChunk> getLoadedChunks() {
        List<WorldChunk> chunks = new ArrayList<>();
        int radius = getEffectiveScanRadius();
        ChunkPos playerChunk = mc.player.getChunkPos();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(
                        playerChunk.x + x, playerChunk.z + z);
                if (chunk != null) chunks.add(chunk);
            }
        }
        return chunks;
    }

    private int getEffectiveScanRadius() {
        int viewDistance = mc.options.getViewDistance().getValue();
        return Math.max(2, Math.min(scanRadius.getIntValue(), viewDistance + 1));
    }

    private void notifyChunkFound(ChunkPos chunkPos, String reason) {
        mc.execute(() -> {
            int x = chunkPos.getStartX() + 8;
            int z = chunkPos.getStartZ() + 8;

            mc.getSoundManager().play(PositionedSoundInstance.master(
                    SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0F, 0.8F));

            if (mc.getToastManager() != null) {
                String msg = (reason == null || reason.isBlank())
                        ? "X: " + x + " Z: " + z
                        : reason + " (X: " + x + ", Z: " + z + ")";
                mc.getToastManager().add(new ChunkFinderToast(
                        Text.literal("ChunkFinderV2"),
                        Text.literal(msg),
                        new ItemStack(Items.ENDER_EYE)
                ));
            }

            if (mc.player != null && chatFeedback.getValue()) {
                String body = (reason == null || reason.isBlank())
                        ? "X: " + x + " Z: " + z
                        : reason + " (X: " + x + ", Z: " + z + ")";
                mc.player.sendMessage(Text.literal("[ChunkFinderV2] " + body), false);
            }
        });
    }

    private double distanceSqToChunkCenter(ChunkPos chunkPos) {
        if (mc.player == null) return 0.0;
        double centerX = chunkPos.getStartX() + 8.0;
        double centerZ = chunkPos.getStartZ() + 8.0;
        return mc.player.squaredDistanceTo(centerX, mc.player.getY(), centerZ);
    }

    private void drawChunkHighlight(RenderUtils.WorldBatch batch,
                                    ChunkPos chunkPos, Color color, Vec3d camPos) {
        double x    = chunkPos.getStartX() - camPos.x;
        double z    = chunkPos.getStartZ() - camPos.z;
        double minY = Math.max(mc.world.getBottomY(),
                Math.min(minScanY.getIntValue(), maxScanY.getIntValue())) - camPos.y;
        double maxY = Math.min(mc.world.getBottomY() + mc.world.getHeight() - 1,
                Math.max(minScanY.getIntValue(), maxScanY.getIntValue())) - camPos.y;
        double slabY = MathHelper.clamp(mc.player.getY(),
                minScanY.getValue(), maxScanY.getValue()) - camPos.y;

        if (fill.getValue()) {
            Color fillColor = new Color(color.getRed(), color.getGreen(), color.getBlue(), 75);
            batch.renderFilledBox(x, slabY, z, x + 16.0, slabY + 0.2, z + 16.0, fillColor);
        }
        if (outline.getValue()) {
            batch.renderOutlineBox(x, minY, z, x + 16.0, maxY, z + 16.0, color);
        }
        if (tracers.getValue()) {
            batch.renderLine(color, new Vec3d(0.0, 0.0, 0.0),
                    new Vec3d(x + 8.0, slabY + 0.1, z + 8.0), 1.5f);
        }
    }

    private void drawBlockMarker(RenderUtils.WorldBatch batch,
                                 BlockPos blockPos, Color color, Vec3d camPos) {
        double x = blockPos.getX() - camPos.x;
        double y = blockPos.getY() - camPos.y;
        double z = blockPos.getZ() - camPos.z;
        Color fillColor = new Color(color.getRed(), color.getGreen(), color.getBlue(), 80);
        batch.renderFilledBox(x, y, z, x + 1.0, y + 1.0, z + 1.0, fillColor);
        batch.renderOutlineBox(x, y, z, x + 1.0, y + 1.0, z + 1.0, color);
    }

    private Color getChunkColor(ChunkAnalysis analysis) {
        if (analysis == null)                             return new Color(60,  179, 113, 190);
        if (analysis.cherryGrove)                         return new Color(255, 141, 196, 205);
        if (analysis.coveredAncientDebris > 0)            return new Color(255, 184, 77,  205);
        if (analysis.rotatedDeepslate > 0)                return new Color(90,  190, 255, 205);
        if (analysis.hasDioriteVein)                      return new Color(196, 196, 196, 205);
        if (analysis.hasLongVine || analysis.hasFullKelp) return new Color(90,  214, 130, 205);
        return new Color(60, 179, 113, 190);
    }

    private boolean isBlockCovered(WorldChunk chunk, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = pos.offset(direction);
            BlockState adjacentState = chunk.getBlockState(adjacent);
            if (adjacentState.isAir() || !adjacentState.isOpaque()) return false;
        }
        return true;
    }

    private boolean isPositionUnderground(BlockPos pos) {
        int checkHeight = Math.min(pos.getY() + 20, 100);
        int solidAbove  = 0;
        for (int y = pos.getY() + 1; y < checkHeight; y++) {
            if (mc.world.getBlockState(new BlockPos(pos.getX(), y, pos.getZ())).isOpaque())
                solidAbove++;
        }
        return solidAbove > 5;
    }

    private BlockPos checkHasDioriteVein(ChunkPos chunkPos) {
        int startX = chunkPos.getStartX();
        int startZ = chunkPos.getStartZ();
        int yMin   = Math.max(mc.world.getBottomY(), minScanY.getIntValue());
        int yMax   = Math.min(mc.world.getBottomY() + mc.world.getHeight() - 1, maxScanY.getIntValue());
        Set<BlockPos> visited = new HashSet<>();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = yMin; y <= yMax; y++) {
                    if (shouldStop) return null;
                    BlockPos pos = new BlockPos(startX + x, y, startZ + z);
                    if (visited.contains(pos)) continue;
                    BlockState state = mc.world.getBlockState(pos);
                    if (!isTargetStone(state)) continue;

                    int lenUp   = countVerticalRun(pos, Direction.UP);
                    int lenDown = countVerticalRun(pos, Direction.DOWN);
                    int total   = lenUp + 1 + lenDown;
                    if (total < 5) continue;

                    BlockPos start   = pos.offset(Direction.DOWN, lenDown);
                    boolean enclosed = true;
                    for (int i = 0; i < total; i++) {
                        BlockPos current = start.offset(Direction.UP, i);
                        visited.add(current);
                        if (!isEnclosedByStone(current)) enclosed = false;
                    }
                    if (enclosed) return pos;
                }
            }
        }
        return null;
    }

    private boolean isTargetStone(BlockState state) {
        return state.isOf(Blocks.DIORITE) || state.isOf(Blocks.GRANITE) || state.isOf(Blocks.ANDESITE);
    }

    private int countVerticalRun(BlockPos from, Direction direction) {
        int count = 0;
        Mutable mutable = new Mutable(from.getX(), from.getY(), from.getZ());
        while (count <= 20) {
            mutable.move(direction);
            if (!isTargetStone(mc.world.getBlockState(mutable))) break;
            count++;
        }
        return count;
    }

    private boolean isEnclosedByStone(BlockPos pos) {
        Direction[] dirs = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        for (Direction d : dirs) {
            if (!mc.world.getBlockState(pos.offset(d)).isOf(Blocks.STONE)) return false;
        }
        return true;
    }

    private BlockPos checkHasLongVine(ChunkPos chunkPos) {
        int startX   = chunkPos.getStartX();
        int startZ   = chunkPos.getStartZ();
        int scanTopY = Math.min(mc.world.getBottomY() + mc.world.getHeight() - 1, maxScanY.getIntValue());

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = scanTopY; y >= Math.max(40, minScanY.getIntValue()); y--) {
                    if (shouldStop) return null;
                    BlockPos pos = new BlockPos(startX + x, y, startZ + z);
                    if (!mc.world.getBlockState(pos).isOf(Blocks.VINE)) continue;

                    BlockState aboveState = mc.world.getBlockState(pos.up());
                    boolean isTop = !aboveState.isOf(Blocks.VINE)
                            && (aboveState.isSolidBlock(mc.world, pos.up())
                                || !aboveState.isFullCube(mc.world, pos.up()));
                    if (!isTop) continue;

                    int length = 1;
                    for (BlockPos current = pos.down();
                         current.getY() >= Math.max(mc.world.getBottomY(), 40);
                         current = current.down()) {
                        if (!mc.world.getBlockState(current).isOf(Blocks.VINE)) break;
                        length++;
                    }
                    if (length >= 35) return pos;
                }
            }
        }
        return null;
    }

    private BlockPos checkAllKelpFullyGrown(ChunkPos chunkPos) {
        int startX    = chunkPos.getStartX();
        int startZ    = chunkPos.getStartZ();
        int worldMinY = mc.world.getBottomY();
        int worldMaxY = mc.world.getBottomY() + mc.world.getHeight() - 1;

        int kelpPlantsFound = 0;
        int fullKelpPlants  = 0;
        BlockPos firstKelpPos = null;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = Math.max(worldMinY, minScanY.getIntValue());
                     y <= Math.min(worldMaxY, maxScanY.getIntValue()); y++) {
                    if (shouldStop) return null;

                    BlockPos pos     = new BlockPos(startX + x, y, startZ + z);
                    BlockState state = mc.world.getBlockState(pos);
                    if (!state.isOf(Blocks.KELP) && !state.isOf(Blocks.KELP_PLANT)) continue;

                    BlockState belowState = mc.world.getBlockState(pos.down());
                    boolean isBase = !belowState.isOf(Blocks.KELP) && !belowState.isOf(Blocks.KELP_PLANT);
                    if (!isBase) continue;

                    if (firstKelpPos == null) firstKelpPos = pos;

                    int kelpLength = 1;
                    BlockPos kelpTop = pos;
                    for (BlockPos current = pos.up(); current.getY() <= worldMaxY; current = current.up()) {
                        BlockState cs = mc.world.getBlockState(current);
                        if (!cs.isOf(Blocks.KELP) && !cs.isOf(Blocks.KELP_PLANT)) break;
                        kelpLength++;
                        kelpTop = current;
                    }
                    if (kelpLength < 6) continue;

                    kelpPlantsFound++;
                    BlockState aboveTop   = mc.world.getBlockState(kelpTop.up());
                    BlockState aboveWater = mc.world.getBlockState(kelpTop.up(2));
                    if (!aboveTop.getFluidState().isEmpty() && aboveWater.isAir()) fullKelpPlants++;
                }
            }
        }

        if (kelpPlantsFound < 10) return null;
        double percentFull = (double) fullKelpPlants / (double) kelpPlantsFound;
        return percentFull >= 0.95 ? firstKelpPos : null;
    }

    private void clearAllData() {
        flaggedChunks.clear();
        scannedChunks.clear();
        notifiedChunks.clear();
        flaggedBlocks.clear();
        chunkAnalysisData.clear();
        cherryGroveChunks.clear();
    }

    private static final class ChunkAnalysis {
        private final Set<BlockPos> flaggedBlocks = new LinkedHashSet<>();
        private final List<String>  reasons       = new ArrayList<>();
        private int     coveredAncientDebris;
        private int     rotatedDeepslate;
        private boolean hasLongVine;
        private boolean hasFullKelp;
        private boolean hasDioriteVein;
        private boolean cherryGrove;

        private String reasonLabel() {
            return reasons.isEmpty() ? "Suspicious Chunk" : String.join(", ", reasons);
        }
    }

    private static final class ChunkFinderToast implements Toast {
        private static final Identifier TEXTURE = Identifier.ofVanilla("toast/advancement");
        private final Text      title;
        private final Text      description;
        private final ItemStack icon;
        private Visibility visibility = Visibility.HIDE;

        private ChunkFinderToast(Text title, Text description, ItemStack icon) {
            this.title       = title;
            this.description = description;
            this.icon        = icon;
        }

        @Override public Visibility getVisibility() { return visibility; }

        @Override
        public void update(ToastManager manager, long time) {
            visibility = time >= 5000.0 * manager.getNotificationDisplayTimeMultiplier()
                    ? Visibility.HIDE : Visibility.SHOW;
        }

        @Override
        public void draw(DrawContext context, TextRenderer textRenderer, long startTime) {
            context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, TEXTURE, 0, 0, getWidth(), getHeight());
            context.drawText(textRenderer, title, 30, 7, Colors.YELLOW, false);
            List<OrderedText> lines = textRenderer.wrapLines(description, 125);
            if (!lines.isEmpty())
                context.drawText(textRenderer, lines.getFirst(), 30, 18, -1, false);
            context.drawItemWithoutEntity(icon, 8, 8);
        }
    }
}

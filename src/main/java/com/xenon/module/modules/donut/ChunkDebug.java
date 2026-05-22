package com.xenon.module.modules.donut;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.Setting;
import com.xenon.utils.RenderUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.*;
import net.minecraft.world.Heightmap;

import java.awt.*;
import java.util.*;

public class ChunkDebug extends Module {

    private final Setting<Integer> targetLength = new Setting<>("Target Length", 20, 1, 100);
    private final Setting<Integer> sensitivity  = new Setting<>("Sensitivity",   1,  1, 20);
    private final Setting<Boolean> drawTracers  = new Setting<>("Tracers", true);
    private final Setting<Integer> chunksPerTick = new Setting<>("Chunks/Tick", 5, 1, 20);

    private final Map<ChunkPos, Integer> flaggedChunks = new LinkedHashMap<>();
    private final Set<ChunkPos> scannedChunks = new HashSet<>();
    private final ArrayDeque<ChunkPos> scanQueue = new ArrayDeque<>();

    public ChunkDebug() {
        super("Chunk Debug", Category.DONUT);
        addSetting(targetLength);
        addSetting(sensitivity);
        addSetting(drawTracers);
        addSetting(chunksPerTick);
    }

    @Override
    public void onEnable() {
        flaggedChunks.clear();
        scannedChunks.clear();
        scanQueue.clear();
    }

    @Override
    public void onDisable() {
        flaggedChunks.clear();
        scannedChunks.clear();
        scanQueue.clear();
    }

    @Override
    public void onTick() {
        if (mc.world == null || mc.player == null) return;

        int pX = mc.player.getChunkPos().x;
        int pZ = mc.player.getChunkPos().z;

        for (int x = pX - 12; x <= pX + 12; x++) {
            for (int z = pZ - 12; z <= pZ + 12; z++) {
                ChunkPos cp = new ChunkPos(x, z);
                if (!scannedChunks.contains(cp) && !scanQueue.contains(cp)
                        && mc.world.getChunkManager().isChunkLoaded(x, z)) {
                    scanQueue.addLast(cp);
                }
            }
        }

        int perTick = chunksPerTick.getValue();
        for (int i = 0; i < perTick; i++) {
            ChunkPos cp = scanQueue.pollFirst();
            if (cp == null) break;
            scannedChunks.add(cp);
            processChunk(cp);
        }
    }

    private boolean isBaseBlock(BlockState state) {
        return state.isOf(Blocks.STONE)       || state.isOf(Blocks.COBBLESTONE)
            || state.isOf(Blocks.DEEPSLATE)   || state.isOf(Blocks.DIRT)
            || state.isOf(Blocks.GRASS_BLOCK)  || state.isOf(Blocks.GRAVEL)
            || state.isOf(Blocks.SAND)         || state.isOf(Blocks.SANDSTONE)
            || state.isOf(Blocks.NETHERRACK)   || state.isOf(Blocks.BEDROCK)
            || state.isOf(Blocks.END_STONE)    || state.isOf(Blocks.WATER);
    }

    private void processChunk(ChunkPos cp) {
        if (mc.world == null) return;
        Set<BlockPos> visited = new HashSet<>();
        int longVineCount = 0;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = -64; y < 319; y++) {
                    BlockPos pos = new BlockPos(cp.getStartX() + x, y, cp.getStartZ() + z);
                    if (visited.contains(pos)) continue;
                    BlockState state = mc.world.getBlockState(pos);
                    if (!state.isOf(Blocks.VINE)) continue;

                    // Walk to top of vine
                    BlockPos current = pos;
                    while (mc.world.getBlockState(current.up()).isOf(Blocks.VINE)) {
                        current = current.up();
                    }

                    int length = 0;
                    boolean touchingBase = false;
                    BlockPos walker = current;
                    while (mc.world.getBlockState(walker).isOf(Blocks.VINE)) {
                        visited.add(walker);
                        length++;
                        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP}) {
                            if (isBaseBlock(mc.world.getBlockState(walker.offset(d)))) {
                                touchingBase = true;
                                break;
                            }
                        }
                        walker = walker.down();
                        if (length > 200) break;
                    }

                    if (length >= targetLength.getValue() && touchingBase) {
                        longVineCount++;
                    }
                }
            }
        }

        if (longVineCount >= sensitivity.getValue() && !flaggedChunks.containsKey(cp)) {
            flaggedChunks.put(cp, longVineCount);
            boolean isGold = longVineCount >= 12;
            mc.execute(() -> {
                com.xenon.gui.notification.NotificationManager.INSTANCE.push(
                        "Chunk Debug",
                        (isGold ? "GOLDEN CHUNK" : "Base Cluster") + " X:" + cp.x * 16 + " Z:" + cp.z * 16,
                        new net.minecraft.item.ItemStack(net.minecraft.item.Items.VINE),
                        isGold ? 0xFFFFD700 : 0xFFFF00FF
                );
            });
        }
    }

    @Override
    public void onRender(MatrixStack matrices, float tickDelta) {
        if (mc.player == null || mc.world == null || flaggedChunks.isEmpty()) return;

        Camera cam = RenderUtils.getCamera();
        Vec3d camPos = RenderUtils.getCameraPos(cam);

        Color goldColor   = new Color(255, 215, 0,   200);
        Color defaultCol  = new Color(255, 0,   255, 150);

        RenderUtils.WorldBatch batch = RenderUtils.beginWorldBatch(matrices);
        matrices.push();
        matrices.translate(-camPos.x, -camPos.y, -camPos.z);

        for (Map.Entry<ChunkPos, Integer> entry : flaggedChunks.entrySet()) {
            ChunkPos cp = entry.getKey();
            boolean isGold = entry.getValue() >= 12;
            Color col = isGold ? goldColor : defaultCol;
            Color fill = new Color(col.getRed(), col.getGreen(), col.getBlue(), 50);

            double x0 = cp.getStartX();
            double z0 = cp.getStartZ();
            double y  = mc.world.getTopY(Heightmap.Type.WORLD_SURFACE, cp.getStartX() + 8, cp.getStartZ() + 8) + 0.05;

            batch.renderFilledBox(x0, y - 0.1, z0, x0 + 16, y + 0.2, z0 + 16, fill);
            batch.renderOutlineBox(x0, y - 0.1, z0, x0 + 16, y + 0.2, z0 + 16, col);

            if (drawTracers.getValue()) {
                batch.renderLine(col, camPos, new Vec3d(x0 + 8, y, z0 + 8), 2.0f);
            }
        }

        matrices.pop();
        batch.flush();
    }
}

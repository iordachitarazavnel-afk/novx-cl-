package com.xenon.module.modules.donut;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.utils.RenderUtils;
import net.minecraft.block.BeehiveBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.awt.Color;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PrimeChunkFinder extends Module {
    private static final int BEEHIVE_MIN_LOADED_TICKS = 200;
    private static final int CHUNKS_PER_TICK = 8;
    private static final Color FILL_COLOR = new Color(5, 130, 45, 190);

    private final Set<ChunkPos> flaggedChunks = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> notifiedChunks = ConcurrentHashMap.newKeySet();
    private final Map<ChunkPos, Integer> firstLoadedTicks = new ConcurrentHashMap<>();
    private int scanCursor;
    private int tickCounter;

    public PrimeChunkFinder() {
        super("PrimeChunkFinder", Category.DONUT);
    }

    @Override
    public void onEnable() {
        reset();
    }

    @Override
    public void onDisable() {
        reset();
    }

    @Override
    public void onTick() {
        if (mc.world == null || mc.player == null) return;

        tickCounter++;

        int radius = mc.options.getClampedViewDistance();
        ChunkPos playerChunk = mc.player.getChunkPos();
        int side = radius * 2 + 1;
        int total = side * side;

        for (int i = 0; i < CHUNKS_PER_TICK; i++) {
            int index = scanCursor % total;
            scanCursor = (scanCursor + 1) % total;

            int dx = (index % side) - radius;
            int dz = (index / side) - radius;
            int cx = playerChunk.x + dx;
            int cz = playerChunk.z + dz;

            WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(cx, cz, false);
            if (chunk == null || chunk.isEmpty()) continue;

            ChunkPos pos = new ChunkPos(cx, cz);
            firstLoadedTicks.putIfAbsent(pos, tickCounter);
            boolean longLoaded = tickCounter - firstLoadedTicks.get(pos) >= BEEHIVE_MIN_LOADED_TICKS;

            if (longLoaded && hasFullBeehive(chunk)) {
                flaggedChunks.add(pos);
                if (notifiedChunks.add(pos)) {
                    showToast(pos);
                }
            } else {
                flaggedChunks.remove(pos);
            }
        }

        flaggedChunks.removeIf(chunk -> outOfRange(chunk, playerChunk, radius));
        firstLoadedTicks.keySet().removeIf(chunk -> outOfRange(chunk, playerChunk, radius));
    }

    private boolean hasFullBeehive(WorldChunk chunk) {
        for (ChunkSection section : chunk.getSectionArray()) {
            if (section == null || section.isEmpty()) continue;
            if (!section.hasAny(state -> state.isOf(Blocks.BEEHIVE) || state.isOf(Blocks.BEE_NEST))) continue;

            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int ly = 0; ly < 16; ly++) {
                        BlockState state = section.getBlockState(lx, ly, lz);
                        if ((state.isOf(Blocks.BEEHIVE) || state.isOf(Blocks.BEE_NEST))
                                && state.contains(BeehiveBlock.HONEY_LEVEL)
                                && state.get(BeehiveBlock.HONEY_LEVEL) == BeehiveBlock.FULL_HONEY_LEVEL) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    @Override
    public void onRender(MatrixStack matrices, float tickDelta) {
        if (mc.world == null || mc.player == null || flaggedChunks.isEmpty()) return;

        Camera camera = RenderUtils.getCamera();
        if (camera == null) return;

        Vec3d camPos = RenderUtils.getCameraPos(camera);
        matrices.push();
        RenderUtils.WorldBatch batch = RenderUtils.beginWorldBatch(matrices);

        for (ChunkPos chunk : flaggedChunks) {
            double x1 = chunk.getStartX() - camPos.x;
            double z1 = chunk.getStartZ() - camPos.z;
            double x2 = x1 + 16.0;
            double z2 = z1 + 16.0;
            double y1 = 55.0 - camPos.y;
            double y2 = y1 + 0.001;

            batch.renderFilledBox(x1, y1, z1, x2, y2, z2, FILL_COLOR);
        }

        batch.flush();
        matrices.pop();
    }

    private boolean outOfRange(ChunkPos chunk, ChunkPos player, int radius) {
        return Math.abs(chunk.x - player.x) > radius || Math.abs(chunk.z - player.z) > radius;
    }

    private void showToast(ChunkPos chunk) {
        if (mc.getToastManager() == null) return;

        mc.getToastManager().add(new ChunkFinderToast(
                Text.literal("PrimeChunkFinder"),
                Text.literal("Full beehive at X:" + chunk.getCenterX() + " Z:" + chunk.getCenterZ()),
                new ItemStack(Items.ENDER_EYE)
        ));
    }

    private void reset() {
        flaggedChunks.clear();
        notifiedChunks.clear();
        firstLoadedTicks.clear();
        scanCursor = 0;
        tickCounter = 0;
    }

    private static final class ChunkFinderToast implements Toast {
        private static final Identifier TEXTURE = Identifier.ofVanilla("toast/advancement");
        private final Text title;
        private final Text description;
        private final ItemStack icon;
        private Visibility visibility = Visibility.HIDE;

        private ChunkFinderToast(Text title, Text description, ItemStack icon) {
            this.title = title;
            this.description = description;
            this.icon = icon;
        }

        @Override
        public Visibility getVisibility() {
            return visibility;
        }

        @Override
        public void update(ToastManager manager, long time) {
            visibility = time >= 5000.0 * manager.getNotificationDisplayTimeMultiplier() ? Visibility.HIDE : Visibility.SHOW;
        }

        @Override
        public void draw(DrawContext context, TextRenderer textRenderer, long startTime) {
            context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, TEXTURE, 0, 0, getWidth(), getHeight());
            context.drawText(textRenderer, title, 30, 7, Colors.YELLOW, false);
            List<OrderedText> lines = textRenderer.wrapLines(description, 125);
            if (!lines.isEmpty()) {
                context.drawText(textRenderer, lines.getFirst(), 30, 18, -1, false);
            }
            context.drawItemWithoutEntity(icon, 8, 8);
        }
    }
}

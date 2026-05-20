package com.xenon.module.modules.donut;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.ModeSetting;
import com.xenon.setting.Setting;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BoneDropperBot extends Module {
    private static final int MIN_DELAY_MS = 100;
    private static final int MAX_DELAY_MS = 2000;
    private static final int DEFAULT_DELAY_MS = 300;
    private static final int PLAYER_INVENTORY_SLOT_COUNT = 36;
    private static final int PRIMARY_CLICK_BUTTON = 0;
    private static final long SPAWNER_DROP_TIMEOUT_MS = 4_000L;

    private final ModeSetting mode = new ModeSetting("Mode", "Spawner", "Spawner", "Orders");
    private final Setting<Integer> delayMs = new Setting<>("Delay", DEFAULT_DELAY_MS, MIN_DELAY_MS, MAX_DELAY_MS);

    private BotState state = BotState.SPAWNER_OPEN_MENU;
    private String lastMode = mode.getValue();
    private long nextActionAtMs;
    private int spawnerBoneCountBeforeDrop;
    private long spawnerDropRequestedAtMs;
    private boolean spawnerGridWasFullBeforeDrop;

    public BoneDropperBot() {
        super("BoneDropper", Category.DONUT);
        addSetting(mode);
        addSetting(delayMs);
    }

    @Override
    public void onEnable() {
        resetState();
    }

    @Override
    public void onDisable() {
        state = BotState.SPAWNER_OPEN_MENU;
        nextActionAtMs = 0L;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            return;
        }
        if (mc.currentScreen != null && !(mc.currentScreen instanceof HandledScreen<?>)) {
            return;
        }

        if (!mode.getValue().equalsIgnoreCase(lastMode)) {
            resetState();
        }

        long now = System.currentTimeMillis();
        if (now < nextActionAtMs) {
            return;
        }

        if (mode.is("Spawner")) {
            tickSpawnerMode();
        } else {
            tickOrdersMode();
        }
    }

    private void tickSpawnerMode() {
        switch (state) {
            case SPAWNER_OPEN_MENU -> {
                if (isHandledMenuOpen()) {
                    state = BotState.SPAWNER_WAIT_MENU;
                    scheduleNextAction();
                    return;
                }

                interactWithTargetBlock();
                state = BotState.SPAWNER_WAIT_MENU;
                scheduleNextAction();
            }
            case SPAWNER_WAIT_MENU -> {
                if (!isHandledMenuOpen()) {
                    state = BotState.SPAWNER_OPEN_MENU;
                    scheduleNextAction();
                    return;
                }

                state = BotState.SPAWNER_SCAN_GRID;
                scheduleNextAction();
            }
            case SPAWNER_SCAN_GRID -> {
                ScreenHandler handler = getMenuHandler();
                if (handler == null) {
                    state = BotState.SPAWNER_OPEN_MENU;
                    scheduleNextAction();
                    return;
                }

                if (!isSpawnerGridFull(handler)) {
                    scheduleNextAction();
                    return;
                }

                state = BotState.SPAWNER_CLICK_DROPPER;
                scheduleNextAction();
            }
            case SPAWNER_CLICK_DROPPER -> {
                ScreenHandler handler = getMenuHandler();
                if (handler == null) {
                    state = BotState.SPAWNER_OPEN_MENU;
                    scheduleNextAction();
                    return;
                }

                Slot dropperSlot = findSpawnerDropperSlot(handler);
                if (dropperSlot == null) {
                    scheduleNextAction();
                    return;
                }

                spawnerGridWasFullBeforeDrop = isSpawnerGridFull(handler);
                if (!spawnerGridWasFullBeforeDrop) {
                    state = BotState.SPAWNER_SCAN_GRID;
                    scheduleNextAction();
                    return;
                }

                spawnerBoneCountBeforeDrop = countInventoryItem(Items.BONE);
                spawnerDropRequestedAtMs = System.currentTimeMillis();
                clickSlot(dropperSlot);
                state = BotState.SPAWNER_WAIT_DROP_CONFIRM;
                scheduleNextAction();
            }
            case SPAWNER_WAIT_DROP_CONFIRM -> {
                long now = System.currentTimeMillis();
                ScreenHandler handler = getMenuHandler();
                boolean gridCleared = spawnerGridWasFullBeforeDrop && handler != null && !isSpawnerGridFull(handler);
                boolean gainedBones = countInventoryItem(Items.BONE) > spawnerBoneCountBeforeDrop;

                if (gridCleared || gainedBones) {
                    if (handler != null) {
                        closeMenu();
                    }
                    state = BotState.SPAWNER_DONE;
                    nextActionAtMs = Long.MAX_VALUE;
                    return;
                }

                if (spawnerDropRequestedAtMs > 0L && now - spawnerDropRequestedAtMs > SPAWNER_DROP_TIMEOUT_MS) {
                    state = handler == null ? BotState.SPAWNER_OPEN_MENU : BotState.SPAWNER_SCAN_GRID;
                    scheduleNextAction();
                    return;
                }

                scheduleNextAction();
            }
            case SPAWNER_DONE -> {
            }
            default -> {
                state = BotState.SPAWNER_OPEN_MENU;
                scheduleNextAction();
            }
        }
    }

    private void tickOrdersMode() {
        switch (state) {
            case ORDERS_SEND_COMMAND -> {
                if (!isHandledMenuOpen()) {
                    sendServerCommand("/order");
                    state = BotState.ORDERS_WAIT_MENU;
                    scheduleNextAction();
                    return;
                }

                state = BotState.ORDERS_CLICK_CHEST_ONE;
                scheduleNextAction();
            }
            case ORDERS_WAIT_MENU -> {
                if (!isHandledMenuOpen()) {
                    sendServerCommand("/order");
                    scheduleNextAction();
                    return;
                }

                state = BotState.ORDERS_CLICK_CHEST_ONE;
                scheduleNextAction();
            }
            case ORDERS_CLICK_CHEST_ONE -> handleOrdersClickStep(MenuTarget.CHEST, BotState.ORDERS_CLICK_BONE);
            case ORDERS_CLICK_BONE -> handleOrdersClickStep(MenuTarget.BONE, BotState.ORDERS_CLICK_CHEST_TWO);
            case ORDERS_CLICK_CHEST_TWO -> handleOrdersClickStep(MenuTarget.CHEST, BotState.ORDERS_CLICK_DROPPER_ONE);
            case ORDERS_CLICK_DROPPER_ONE -> handleOrdersClickStep(MenuTarget.DROPPER, BotState.ORDERS_CLICK_ARROW);
            case ORDERS_CLICK_ARROW -> handleOrdersClickStep(MenuTarget.ARROW, BotState.ORDERS_CLICK_DROPPER_TWO);
            case ORDERS_CLICK_DROPPER_TWO -> handleOrdersClickStep(MenuTarget.DROPPER, BotState.ORDERS_CLICK_CHEST_ONE);
            default -> {
                state = BotState.ORDERS_SEND_COMMAND;
                scheduleNextAction();
            }
        }
    }

    private void handleOrdersClickStep(MenuTarget target, BotState nextState) {
        ScreenHandler handler = getMenuHandler();
        if (handler == null) {
            sendServerCommand("/order");
            scheduleNextAction();
            return;
        }

        Slot slot = findUpperMenuSlot(handler, target, target == MenuTarget.DROPPER || target == MenuTarget.ARROW);
        if (slot == null) {
            scheduleNextAction();
            return;
        }

        clickSlot(slot);
        state = nextState;
        scheduleNextAction();
    }

    private boolean isSpawnerGridFull(ScreenHandler handler) {
        List<Slot> gridSlots = getSpawnerGridSlots(handler);
        if (gridSlots.isEmpty()) {
            return false;
        }

        for (Slot slot : gridSlots) {
            if (!slot.isEnabled()) {
                continue;
            }

            ItemStack stack = slot.getStack();
            if (stack.isEmpty() || !matchesTarget(stack, MenuTarget.BONE)) {
                return false;
            }
        }

        return true;
    }

    private List<Slot> getSpawnerGridSlots(ScreenHandler handler) {
        List<Slot> upperSlots = getUpperMenuSlots(handler);
        if (upperSlots.isEmpty()) {
            return List.of();
        }

        int maxY = upperSlots.stream().mapToInt(slot -> slot.y).max().orElse(Integer.MIN_VALUE);
        List<Slot> gridSlots = new ArrayList<>();
        for (Slot slot : upperSlots) {
            if (slot.y < maxY) {
                gridSlots.add(slot);
            }
        }

        return gridSlots.isEmpty() ? upperSlots : gridSlots;
    }

    private Slot findSpawnerDropperSlot(ScreenHandler handler) {
        List<Slot> upperSlots = getUpperMenuSlots(handler);
        if (upperSlots.isEmpty()) {
            return null;
        }

        int maxY = upperSlots.stream().mapToInt(slot -> slot.y).max().orElse(Integer.MIN_VALUE);
        Slot controlDropper = chooseBestMatchingSlot(upperSlots, MenuTarget.DROPPER, true, maxY);
        if (controlDropper != null) {
            return controlDropper;
        }

        Slot anyDropper = chooseBestMatchingSlot(upperSlots, MenuTarget.DROPPER, true, Integer.MIN_VALUE);
        if (anyDropper != null) {
            return anyDropper;
        }

        for (int i = upperSlots.size() - 1; i >= 0; i--) {
            Slot slot = upperSlots.get(i);
            if (slot.isEnabled()) {
                return slot;
            }
        }

        return null;
    }

    private Slot findUpperMenuSlot(ScreenHandler handler, MenuTarget target, boolean preferBottomRight) {
        return chooseBestMatchingSlot(getUpperMenuSlots(handler), target, preferBottomRight, Integer.MIN_VALUE);
    }

    private Slot chooseBestMatchingSlot(List<Slot> slots, MenuTarget target, boolean preferBottomRight, int yFilter) {
        Slot best = null;
        for (Slot slot : slots) {
            if (!slot.isEnabled()) {
                continue;
            }
            if (yFilter != Integer.MIN_VALUE && slot.y != yFilter) {
                continue;
            }
            if (!matchesTarget(slot.getStack(), target)) {
                continue;
            }

            if (best == null) {
                best = slot;
                continue;
            }

            if (preferBottomRight) {
                if (slot.y > best.y || (slot.y == best.y && slot.x >= best.x)) {
                    best = slot;
                }
            } else if (slot.y < best.y || (slot.y == best.y && slot.x < best.x)) {
                best = slot;
            }
        }
        return best;
    }

    private boolean matchesTarget(ItemStack stack, MenuTarget target) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        Item item = stack.getItem();
        String lowerName = stack.getName().getString().toLowerCase(Locale.ROOT);

        return switch (target) {
            case BONE -> item == Items.BONE || lowerName.contains("bone");
            case CHEST -> item == Items.CHEST
                    || item == Items.TRAPPED_CHEST
                    || item == Items.ENDER_CHEST
                    || lowerName.contains("chest")
                    || lowerName.contains("truhe");
            case DROPPER -> item == Items.DROPPER || lowerName.contains("dropper");
            case ARROW -> item == Items.ARROW || lowerName.contains("arrow") || lowerName.contains("pfeil");
        };
    }

    private List<Slot> getUpperMenuSlots(ScreenHandler handler) {
        if (handler == null || handler.slots == null || handler.slots.isEmpty()) {
            return List.of();
        }

        int upperSlotCount = Math.max(0, handler.slots.size() - PLAYER_INVENTORY_SLOT_COUNT);
        if (upperSlotCount == 0) {
            upperSlotCount = handler.slots.size();
        }

        List<Slot> upperSlots = new ArrayList<>(upperSlotCount);
        for (int i = 0; i < upperSlotCount; i++) {
            upperSlots.add(handler.slots.get(i));
        }
        return upperSlots;
    }

    private int countInventoryItem(Item item) {
        if (mc.player == null) {
            return 0;
        }

        int count = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private void clickSlot(Slot slot) {
        if (slot == null || mc.player == null || mc.interactionManager == null) {
            return;
        }

        mc.interactionManager.clickSlot(
                mc.player.currentScreenHandler.syncId,
                slot.id,
                PRIMARY_CLICK_BUTTON,
                SlotActionType.PICKUP,
                mc.player
        );
    }

    private void interactWithTargetBlock() {
        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || mc.crosshairTarget.getType() != HitResult.Type.BLOCK) {
            return;
        }

        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private void closeMenu() {
        if (mc.player == null) {
            return;
        }

        if (mc.currentScreen instanceof HandledScreen<?>) {
            mc.player.closeHandledScreen();
            mc.setScreen(null);
        }
    }

    private ScreenHandler getMenuHandler() {
        if (!(mc.currentScreen instanceof HandledScreen<?>) || mc.player == null) {
            return null;
        }
        return mc.player.currentScreenHandler;
    }

    private boolean isHandledMenuOpen() {
        return getMenuHandler() != null;
    }

    private void sendServerCommand(String command) {
        ClientPlayNetworkHandler networkHandler = mc.player != null ? mc.player.networkHandler : mc.getNetworkHandler();
        if (networkHandler == null) {
            return;
        }

        String commandWithoutSlash = command.startsWith("/") ? command.substring(1) : command;
        try {
            networkHandler.sendChatCommand(commandWithoutSlash);
        } catch (Throwable ignored) {
            networkHandler.sendChatMessage(command);
        }
    }

    private void resetState() {
        lastMode = mode.getValue();
        state = mode.is("Spawner") ? BotState.SPAWNER_OPEN_MENU : BotState.ORDERS_SEND_COMMAND;
        nextActionAtMs = 0L;
        spawnerBoneCountBeforeDrop = 0;
        spawnerDropRequestedAtMs = 0L;
        spawnerGridWasFullBeforeDrop = false;
    }

    private void scheduleNextAction() {
        nextActionAtMs = System.currentTimeMillis() + delayMs.getValue();
    }

    private enum MenuTarget {
        BONE,
        CHEST,
        DROPPER,
        ARROW
    }

    private enum BotState {
        SPAWNER_OPEN_MENU,
        SPAWNER_WAIT_MENU,
        SPAWNER_SCAN_GRID,
        SPAWNER_CLICK_DROPPER,
        SPAWNER_WAIT_DROP_CONFIRM,
        SPAWNER_DONE,
        ORDERS_SEND_COMMAND,
        ORDERS_WAIT_MENU,
        ORDERS_CLICK_CHEST_ONE,
        ORDERS_CLICK_BONE,
        ORDERS_CLICK_CHEST_TWO,
        ORDERS_CLICK_DROPPER_ONE,
        ORDERS_CLICK_ARROW,
        ORDERS_CLICK_DROPPER_TWO
    }
}

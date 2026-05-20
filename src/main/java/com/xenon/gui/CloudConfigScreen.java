package com.xenon.gui;

import com.xenon.module.modules.client.CloudConfigs;
import com.xenon.module.modules.client.XenonPlus;
import com.xenon.utils.ConfigStore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public final class CloudConfigScreen extends Screen {

    private static final int PANEL_W = 280;
    private static final int BTN_W = 240;
    private static final int BTN_H = 22;
    private static final int BTN_GAP = 5;
    private static final int PANEL_PAD = 14;
    private static final int HEADER_H = 26;
    private static final int FIELD_H = 22;
    private static final int LIST_VISIBLE = 6;

    private static final int COLOR_SCREEN_BG  = 0xC00B0F14;
    private static final int COLOR_PANEL_BG   = 0xFF121827;
    private static final int COLOR_PANEL_LINE = 0xFF1E2635;
    private static final int COLOR_HEADER_BG  = 0xFF151D2B;
    private static final int COLOR_ROW_BG     = 0xFF141C2A;
    private static final int COLOR_ROW_HOVER  = 0xFF1A2434;
    private static final int COLOR_FIELD_BG   = 0xFF0E141E;
    private static final int COLOR_TEXT       = 0xFFE8EDF5;
    private static final int COLOR_TEXT_MUTED = 0xFF98A2B3;

    private final CloudConfigs module;
    private View view = View.MAIN;
    private boolean closing = false;
    private final List<Button> buttons = new ArrayList<>();

    private String inputText = "";
    private int listScroll = 0;
    private List<String> currentList = new ArrayList<>();
    private String generatedCode = "";

    private String toast = null;
    private long toastUntil = 0L;

    private enum View { MAIN, CREATE, LOAD, SHARE, GENERATE, REDEEM }

    public CloudConfigScreen(CloudConfigs module) {
        super(Text.literal("Cloud Configs"));
        this.module = module;
    }

    @Override
    protected void init() {
        rebuild();
    }

    private int contentHeight() {
        return switch (view) {
            case MAIN, SHARE -> HEADER_H + PANEL_PAD + (3 * BTN_H) + (2 * BTN_GAP) + PANEL_PAD;
            case CREATE, REDEEM -> HEADER_H + PANEL_PAD + FIELD_H + 8 + (2 * BTN_H) + BTN_GAP + PANEL_PAD;
            case GENERATE -> HEADER_H + PANEL_PAD + FIELD_H + 8 + (2 * BTN_H) + BTN_GAP + PANEL_PAD;
            case LOAD -> {
                int rows = Math.min(LIST_VISIBLE, Math.max(1, currentList.size()));
                yield HEADER_H + PANEL_PAD + (rows * BTN_H) + ((rows - 1) * BTN_GAP) + 8 + BTN_H + PANEL_PAD;
            }
        };
    }

    private void rebuild() {
        buttons.clear();
        int contentH = contentHeight();
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - contentH) / 2;
        int btnX = panelX + (PANEL_W - BTN_W) / 2;
        int btnY = panelY + HEADER_H + PANEL_PAD;

        switch (view) {
            case MAIN -> {
                buttons.add(new Button(btnX, btnY, BTN_W, BTN_H, "Create Config", () -> { view = View.CREATE; inputText = ""; rebuild(); }));
                btnY += BTN_H + BTN_GAP;
                buttons.add(new Button(btnX, btnY, BTN_W, BTN_H, "Load Config", () -> { view = View.LOAD; currentList = ConfigStore.list(); listScroll = 0; rebuild(); }));
                btnY += BTN_H + BTN_GAP;
                buttons.add(new Button(btnX, btnY, BTN_W, BTN_H, "Share Config »", () -> { view = View.SHARE; rebuild(); }));
            }
            case CREATE -> {
                btnY += FIELD_H + 8;
                int halfW = (BTN_W - BTN_GAP) / 2;
                buttons.add(new Button(btnX, btnY, halfW, BTN_H, "Save", () -> {
                    if (ConfigStore.saveAs(inputText)) {
                        showToast("Saved as " + ConfigStore.sanitize(inputText));
                        view = View.MAIN; rebuild();
                    } else showToast("Invalid name");
                }));
                buttons.add(new Button(btnX + halfW + BTN_GAP, btnY, halfW, BTN_H, "Cancel", () -> { view = View.MAIN; rebuild(); }));
                btnY += BTN_H + BTN_GAP;
                buttons.add(new Button(btnX, btnY, BTN_W, BTN_H, "« Back", () -> { view = View.MAIN; rebuild(); }));
            }
            case LOAD -> {
                if (currentList.isEmpty()) {
                    btnY += BTN_H + BTN_GAP * 2;
                } else {
                    int max = Math.min(currentList.size(), listScroll + LIST_VISIBLE);
                    for (int i = listScroll; i < max; i++) {
                        final String name = currentList.get(i);
                        int halfW = (BTN_W - BTN_GAP - 30 - BTN_GAP);
                        buttons.add(new Button(btnX, btnY, halfW, BTN_H, "↓ " + name, () -> {
                            if (ConfigStore.load(name)) showToast("Loaded " + name);
                            else showToast("Failed to load");
                        }));
                        buttons.add(new Button(btnX + halfW + BTN_GAP, btnY, 30, BTN_H, "✕", () -> {
                            ConfigStore.delete(name);
                            currentList = ConfigStore.list();
                            listScroll = Math.max(0, Math.min(listScroll, currentList.size() - 1));
                            rebuild();
                        }));
                        btnY += BTN_H + BTN_GAP;
                    }
                }
                btnY += 8;
                buttons.add(new Button(btnX, btnY, BTN_W, BTN_H, "« Back", () -> { view = View.MAIN; rebuild(); }));
            }
            case SHARE -> {
                buttons.add(new Button(btnX, btnY, BTN_W, BTN_H, "Generate Share Code", () -> {
                    String code = ConfigStore.generateShareCode();
                    if (code != null) { generatedCode = code; view = View.GENERATE; rebuild(); showToast("Code generated"); }
                    else showToast("Failed to generate");
                }));
                btnY += BTN_H + BTN_GAP;
                buttons.add(new Button(btnX, btnY, BTN_W, BTN_H, "Redeem Share Code", () -> { view = View.REDEEM; inputText = ""; rebuild(); }));
                btnY += BTN_H + BTN_GAP;
                buttons.add(new Button(btnX, btnY, BTN_W, BTN_H, "« Back", () -> { view = View.MAIN; rebuild(); }));
            }
            case GENERATE -> {
                btnY += FIELD_H + 8;
                int halfW = (BTN_W - BTN_GAP) / 2;
                buttons.add(new Button(btnX, btnY, halfW, BTN_H, "Copy", () -> {
                    ConfigStore.writeClipboard(generatedCode);
                    showToast("Copied to clipboard");
                }));
                buttons.add(new Button(btnX + halfW + BTN_GAP, btnY, halfW, BTN_H, "« Back", () -> { view = View.SHARE; rebuild(); }));
                btnY += BTN_H + BTN_GAP;
                buttons.add(new Button(btnX, btnY, BTN_W, BTN_H, "Done", () -> { view = View.MAIN; rebuild(); }));
            }
            case REDEEM -> {
                btnY += FIELD_H + 8;
                int halfW = (BTN_W - BTN_GAP) / 2;
                buttons.add(new Button(btnX, btnY, halfW, BTN_H, "Paste", () -> {
                    inputText = ConfigStore.readClipboard();
                    if (inputText == null) inputText = "";
                }));
                buttons.add(new Button(btnX + halfW + BTN_GAP, btnY, halfW, BTN_H, "Apply", () -> {
                    if (ConfigStore.redeemShareCode(inputText)) { showToast("Config applied"); view = View.MAIN; rebuild(); }
                    else showToast("Invalid code");
                }));
                btnY += BTN_H + BTN_GAP;
                buttons.add(new Button(btnX, btnY, BTN_W, BTN_H, "« Back", () -> { view = View.SHARE; rebuild(); }));
            }
        }
    }

    private void showToast(String msg) {
        toast = msg;
        toastUntil = System.currentTimeMillis() + 2200L;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int contentH = contentHeight();
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - contentH) / 2;
        int accent = XenonPlus.getAccentARGB();

        context.fill(panelX, panelY, panelX + PANEL_W, panelY + contentH, COLOR_PANEL_BG);
        context.fill(panelX, panelY, panelX + PANEL_W, panelY + HEADER_H, COLOR_HEADER_BG);
        context.fill(panelX, panelY + HEADER_H - 1, panelX + PANEL_W, panelY + HEADER_H, accent);
        drawFrame(context, panelX, panelY, PANEL_W, contentH, COLOR_PANEL_LINE);

        String title = switch (view) {
            case MAIN -> "Cloud Configs";
            case CREATE -> "Create Config";
            case LOAD -> "Load Config";
            case SHARE -> "Share Config";
            case GENERATE -> "Generated Share Code";
            case REDEEM -> "Redeem Share Code";
        };
        context.drawText(this.textRenderer, title, panelX + PANEL_PAD,
                panelY + (HEADER_H - this.textRenderer.fontHeight) / 2, COLOR_TEXT, false);

        int btnX = panelX + (PANEL_W - BTN_W) / 2;
        int contentY = panelY + HEADER_H + PANEL_PAD;

        if (view == View.CREATE || view == View.REDEEM) {
            String label = view == View.CREATE ? "Name:" : "Code:";
            context.drawText(this.textRenderer, label, btnX, contentY - 10, COLOR_TEXT_MUTED, false);
            drawField(context, btnX, contentY, BTN_W, FIELD_H, inputText, accent, true);
        }

        if (view == View.GENERATE) {
            context.drawText(this.textRenderer, "Code:", btnX, contentY - 10, COLOR_TEXT_MUTED, false);
            drawField(context, btnX, contentY, BTN_W, FIELD_H, generatedCode, accent, false);
        }

        if (view == View.LOAD && currentList.isEmpty()) {
            String msg = "No saved configs yet.";
            int mw = this.textRenderer.getWidth(msg);
            context.drawText(this.textRenderer, msg, panelX + (PANEL_W - mw) / 2,
                    contentY + 6, COLOR_TEXT_MUTED, false);
        }

        for (Button b : buttons) {
            boolean hover = b.contains(mouseX, mouseY);
            int bg = hover ? COLOR_ROW_HOVER : COLOR_ROW_BG;
            context.fill(b.x, b.y, b.x + b.w, b.y + b.h, bg);
            context.fill(b.x, b.y, b.x + 2, b.y + b.h, accent);
            if (hover) drawFrame(context, b.x, b.y, b.w, b.h, accent);
            int tw = this.textRenderer.getWidth(b.label);
            int ty = b.y + (b.h - this.textRenderer.fontHeight) / 2;
            int tx = (b.w >= 60) ? b.x + (b.w - tw) / 2 : b.x + (b.w - tw) / 2;
            context.drawText(this.textRenderer, b.label, tx, ty, COLOR_TEXT, false);
        }

        String hint = "ESC to close";
        int hw = this.textRenderer.getWidth(hint);
        context.drawText(this.textRenderer, hint, (this.width - hw) / 2, panelY + contentH + 6, COLOR_TEXT_MUTED, false);

        if (toast != null && System.currentTimeMillis() < toastUntil) {
            int tw = this.textRenderer.getWidth(toast) + 16;
            int th = 18;
            int tx = (this.width - tw) / 2;
            int ty = panelY - th - 8;
            context.fill(tx, ty, tx + tw, ty + th, COLOR_PANEL_BG);
            drawFrame(context, tx, ty, tw, th, accent);
            context.drawText(this.textRenderer, toast, tx + 8,
                    ty + (th - this.textRenderer.fontHeight) / 2, COLOR_TEXT, false);
        }
    }

    private void drawField(DrawContext context, int x, int y, int w, int h, String text, int accent, boolean focused) {
        context.fill(x, y, x + w, y + h, COLOR_FIELD_BG);
        drawFrame(context, x, y, w, h, focused ? accent : COLOR_PANEL_LINE);
        if (text == null) text = "";
        String display = text;
        int maxW = w - 10;
        while (this.textRenderer.getWidth(display) > maxW && display.length() > 1) {
            display = display.substring(1);
        }
        int ty = y + (h - this.textRenderer.fontHeight) / 2;
        context.drawText(this.textRenderer, display, x + 5, ty, COLOR_TEXT, false);
        if (focused) {
            long t = System.currentTimeMillis() / 500L;
            if ((t & 1L) == 0L) {
                int cx = x + 5 + this.textRenderer.getWidth(display);
                context.fill(cx, ty - 1, cx + 1, ty + this.textRenderer.fontHeight, accent);
            }
        }
    }

    private static void drawFrame(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color);
        context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y, x + 1, y + h, color);
        context.fill(x + w - 1, y, x + w, y + h, color);
    }

    @Override
    public boolean mouseClicked(Click click, boolean bl) {
        if (click.button() == 0) {
            int mx = (int) click.x();
            int my = (int) click.y();
            for (Button b : buttons) {
                if (b.contains(mx, my)) {
                    b.action.run();
                    return true;
                }
            }
        }
        return super.mouseClicked(click, bl);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (view == View.LOAD && !currentList.isEmpty()) {
            int max = Math.max(0, currentList.size() - LIST_VISIBLE);
            listScroll = Math.max(0, Math.min(max, listScroll - (int) Math.signum(verticalAmount)));
            rebuild();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (view == View.CREATE || view == View.REDEEM) {
            String s = input.asString();
            if (s != null && !s.isEmpty()) {
                StringBuilder sb = new StringBuilder(inputText);
                s.codePoints().filter(c -> !Character.isISOControl(c)).forEach(sb::appendCodePoint);
                inputText = sb.toString();
                return true;
            }
        }
        return super.charTyped(input);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (view == View.CREATE || view == View.REDEEM) {
            int code = input.getKeycode();
            if (code == GLFW.GLFW_KEY_BACKSPACE) {
                if (!inputText.isEmpty()) {
                    int newEnd = inputText.offsetByCodePoints(inputText.length(), -1);
                    inputText = inputText.substring(0, newEnd);
                }
                return true;
            }
            if (code == GLFW.GLFW_KEY_ENTER || code == GLFW.GLFW_KEY_KP_ENTER) {
                if (view == View.CREATE) {
                    if (ConfigStore.saveAs(inputText)) {
                        showToast("Saved as " + ConfigStore.sanitize(inputText));
                        view = View.MAIN; rebuild();
                    } else showToast("Invalid name");
                } else {
                    if (ConfigStore.redeemShareCode(inputText)) {
                        showToast("Config applied");
                        view = View.MAIN; rebuild();
                    } else showToast("Invalid code");
                }
                return true;
            }
            if (code == GLFW.GLFW_KEY_V) {
                long h = MinecraftClient.getInstance().getWindow().getHandle();
                boolean ctrl = GLFW.glfwGetKey(h, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                        || GLFW.glfwGetKey(h, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
                if (ctrl) {
                    String c = ConfigStore.readClipboard();
                    if (c != null) inputText += c;
                    return true;
                }
            }
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        if (closing) {
            super.close();
            return;
        }
        closing = true;
        super.close();
        if (module != null && module.isEnabled()) {
            module.setEnabled(false);
        }
    }

    private static final class Button {
        final int x, y, w, h;
        final String label;
        final Runnable action;

        Button(int x, int y, int w, int h, String label, Runnable action) {
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.label = label;
            this.action = action;
        }

        boolean contains(int mx, int my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }
}

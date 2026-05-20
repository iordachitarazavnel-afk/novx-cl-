package com.xenon.gui;

import com.xenon.module.Category;
import com.xenon.module.ActivatableModule;
import com.xenon.module.Module;
import com.xenon.module.ModuleManager;
import com.xenon.setting.BlocksSetting;
import com.xenon.setting.MobsSetting;
import net.minecraft.entity.EntityType;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.item.Items;
import com.xenon.setting.ModeSetting;
import com.xenon.setting.Setting;
import com.xenon.utils.renderer.RenderUtil;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class ClickGUI extends Screen {

    // Cached enum values so we don't allocate a new array on every render call.
    private static final Category[] CACHED_CATEGORIES = Category.values();
    // Pre-computed ARGB for the subtle slider-track color; avoids `new Color(24,31,43).getRGB()` per row.
    private static final int SLIDER_TRACK_COLOR_ARGB = 0xFF181F2B;

    private static final int PANEL_W = 132;
    private static final int PANEL_PAD = 10;
    private static final int PANEL_HEADER_H = 22;
    private static final int PANEL_GAP = 12;
    private static final int PANEL_HEADER_SPACING = 6;
    private static final int ROW_H = 16;
    private static final int ROW_STEP = 18;
    private static final int SEARCH_H = 20;
    private static final float PANEL_RADIUS = 8.0f;
    private static final float ROW_RADIUS = 5.0f;
    private static final int COLOR_PICKER_FIELD_HEIGHT = 56;
    private static final int COLOR_PICKER_ALPHA_HEIGHT = 10;
    private static final int COLOR_PICKER_GAP = 10;
    private static final int COLOR_PICKER_BOTTOM_PAD = 6;
    private static final int COLOR_PICKER_EXTRA_HEIGHT = COLOR_PICKER_GAP + COLOR_PICKER_FIELD_HEIGHT + COLOR_PICKER_GAP
            + COLOR_PICKER_ALPHA_HEIGHT + COLOR_PICKER_BOTTOM_PAD;
    private static final int BLOCK_PICKER_SEARCH_H = 16;
    private static final int BLOCK_PICKER_ROW_H = 18;
    private static final int BLOCK_PICKER_VISIBLE_ROWS = 5;
    private static final int BLOCK_PICKER_GAP = 6;
    private static final int BLOCK_PICKER_CLEAR_W = 30;
    private static final int BLOCK_PICKER_BOTTOM_PAD = 6;
    private static final float BLOCK_PICKER_SCROLLBAR_W = 4.0f;
    private static final float BLOCK_PICKER_INDICATOR_SIZE = 6.0f;
    private static final float BLOCK_PICKER_TEXT_SCALE = 0.9f;

    private static final int COLOR_SCREEN_BG = 0xFF0B0F14;
    private static int COLOR_PANEL_BG = 0xFF121827;
    private static final int COLOR_PANEL_OUTLINE = 0xFF1E2635;
    private static final int COLOR_HEADER_BG = 0xFF151D2B;
    private static final int COLOR_ROW_BG = 0xFF141C2A;
    private static final int COLOR_ROW_HOVER = 0xFF1A2434;
    private static final int COLOR_ROW_ACTIVE = 0xFF1B2E2A;
    private static final int COLOR_TEXT = 0xFFE8EDF2;
    private static final int COLOR_TEXT_MUTED = 0xFF9AA4B2;
    // Dynamic — pulled from XenonPlus each frame
    private static int COLOR_ACCENT     = 0xFF6BD3A5;
    private static int COLOR_ACCENT_DIM = 0xFF4EAF86;
    private static final int COLOR_DIVIDER = 0xFF1A2230;
    private static final int COLOR_SEARCH_OUTLINE = 0xFF263041;
    private static final int COLOR_ROW_OUTLINE = 0xFF202A3A;
    private static final int COLOR_KEY_BG = 0xFF182131;
    private static final int SCROLL_STEP = 24;

    private Module listeningBind = null;
    private ActivatableModule listeningActivationBind = null;
    private Setting<String> listeningString = null;
    private Setting<String> expandedStringListSetting = null;
    private boolean stringListAddActive = false;
    private String stringListAddBuffer = "";
    private Setting<Color> expandedColorSetting = null;
    private Setting<Color> activeColorSetting = null;
    private BlocksSetting expandedBlocksSetting = null;
    private MobsSetting expandedMobsSetting = null;
    private ColorDragMode colorDragMode = ColorDragMode.NONE;

    private boolean searchActive = false;
    private boolean blockSearchActive = false;
    private boolean mobSearchActive = false;
    private String searchQuery = "";
    private String blockSearchQuery = "";
    private String mobSearchQuery = "";
    private int mobPickerScroll = 0;
    private int verticalScroll = 0;
    private int blockPickerScroll = 0;

    // UI scaling (controlled by Xenon+ "Menu Size"). We render the whole GUI in a scaled coordinate space and
    // convert mouse input into that same space.
    private float uiScale = 1.0f;

    private Setting<?> draggingNumericSetting = null;
    private Module draggingNumericModule = null;
    private int draggingNumericCatX = 0;

    // --- Category drag state ---
    private final java.util.EnumMap<Category, int[]> categoryOffsets = new java.util.EnumMap<>(Category.class);
    private Category draggingCategory = null;
    private int dragGrabOffsetX = 0;
    private int dragGrabOffsetY = 0;

    // --- Animations (exponential smoothing, frame-rate independent) ---
    private final java.util.HashMap<String, Float> animValues = new java.util.HashMap<>();
    private long lastAnimNanos = 0L;
    private float frameDt = 1f / 60f;

    private void updateAnimDt() {
        long now = System.nanoTime();
        if (lastAnimNanos != 0L) {
            frameDt = Math.min(0.1f, (now - lastAnimNanos) / 1_000_000_000f);
        }
        lastAnimNanos = now;
    }

    private float anim(String key, float target, float speed) {
        if (!com.xenon.module.modules.client.XenonPlus.animationsEnabled()) {
            animValues.put(key, target);
            return target;
        }
        float cur = animValues.getOrDefault(key, target);
        float factor = 1f - (float) Math.exp(-speed * frameDt);
        float next = cur + (target - cur) * factor;
        animValues.put(key, next);
        return next;
    }

    /** Full pixel height of a module's expanded settings block (bind + activation + settings). */
    private int getModuleExpandedHeight(Module module) {
        int h = ROW_STEP; // bind row
        if (module instanceof ActivatableModule) h += ROW_STEP;
        for (Setting<?> setting : module.getSettings()) {
            h += ROW_STEP;
            if (setting instanceof BlocksSetting bs && expandedBlocksSetting == bs) h += getBlockPickerExtraHeight(bs);
            if (setting instanceof MobsSetting ms && expandedMobsSetting == ms) h += getMobPickerExtraHeight(ms);
            if (setting.getValue() instanceof Color && expandedColorSetting == setting) h += COLOR_PICKER_EXTRA_HEIGHT;
            if (isStringListSetting(module, setting) && expandedStringListSetting == setting) h += getStringListEditorExtraHeight(setting);
        }
        return h;
    }

    private boolean isStringListSetting(Module module, Setting<?> setting) {
        if (module == null || setting == null) {
            return false;
        }
        if (!(setting.getValue() instanceof String)) {
            return false;
        }
        if ("Friends".equalsIgnoreCase(module.getName()) && setting.matchesName("Names")) {
            return true;
        }
        if ("TabDetector".equalsIgnoreCase(module.getName()) && setting.matchesName("Target Players")) {
            return true;
        }
        return false;
    }

    private int getStringListEditorExtraHeight(Setting<?> setting) {
        List<String> names = parseStringList(setting);
        int visible = Math.min(6, names.size());
        // entries + add row
        return (visible + 1) * ROW_STEP;
    }

    private List<String> parseStringList(Setting<?> setting) {
        if (setting == null || !(setting.getValue() instanceof String)) return new ArrayList<>();
        String raw = (String) setting.getValue();
        if (raw == null || raw.isBlank()) return new ArrayList<>();
        String normalized = raw.replace('\n', ',').replace('\r', ',');
        List<String> out = new ArrayList<>();
        for (String part : normalized.split(",")) {
            String t = part == null ? "" : part.trim();
            if (t.isEmpty()) continue;
            out.add(t);
        }
        // de-dupe (case-insensitive) while preserving insertion order
        java.util.LinkedHashSet<String> uniq = new java.util.LinkedHashSet<>();
        for (String n : out) {
            uniq.add(n.toLowerCase(Locale.ROOT));
        }
        return new ArrayList<>(uniq);
    }

    private void setStringListFromLowerList(Setting<String> setting, List<String> lowerNames) {
        if (setting == null) return;
        StringBuilder sb = new StringBuilder();
        for (String n : lowerNames) {
            String t = n == null ? "" : n.trim();
            if (t.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(t);
        }
        setting.setValue(sb.toString());
    }

    private float computeUiScale() {
        // Menu Size is 1..10. Map to ~0.80..1.40 (reasonable range on most screens).
        int step = com.xenon.module.modules.client.XenonPlus.menuSizePercent();
        step = Math.max(1, Math.min(10, step));
        return 0.80f + ((step - 1) * (0.60f / 9f));
    }

    private double toUiX(double rawX) {
        return rawX / Math.max(0.0001f, uiScale);
    }

    private double toUiY(double rawY) {
        return rawY / Math.max(0.0001f, uiScale);
    }

    private int uiWidth() {
        return Math.round(this.width / Math.max(0.0001f, uiScale));
    }

    private int uiHeight() {
        return Math.round(this.height / Math.max(0.0001f, uiScale));
    }

    /** Animated expand progress for a module (0=closed, 1=open). */
    private float getExpandProgress(Module module, String modKey) {
        return anim(modKey + "/expand", module.isExpanded() ? 1f : 0f, 20f);
    }

    private static int lerpARGB(int a, int b, float t) {
        if (t <= 0f) return a;
        if (t >= 1f) return b;
        int aa = (a >>> 24) & 0xFF, ar = (a >>> 16) & 0xFF, ag = (a >>> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >>> 16) & 0xFF, bg = (b >>> 8) & 0xFF, bb = b & 0xFF;
        int ra = (int) (aa + (ba - aa) * t);
        int rr = (int) (ar + (br - ar) * t);
        int rg = (int) (ag + (bg - ag) * t);
        int rb = (int) (ab + (bb - ab) * t);
        return (ra << 24) | (rr << 16) | (rg << 8) | rb;
    }

    private int[] getCategoryOffset(Category c) {
        return categoryOffsets.computeIfAbsent(c, k -> new int[2]);
    }
    private int getCategoryX(Category c, int index) {
        return 30 + index * (PANEL_W + PANEL_GAP) + getCategoryOffset(c)[0];
    }
    private int getCategoryY(Category c) {
        return getContentTop() + verticalScroll + getCategoryOffset(c)[1];
    }

    public static ClickGUI INSTANCE;

    public ClickGUI() {
        super(Text.literal("Xenon Menu"));
        INSTANCE = this;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Sync accent + background color from XenonPlus setting every frame
        COLOR_ACCENT     = com.xenon.module.modules.client.XenonPlus.getAccentARGB();
        COLOR_PANEL_BG   = com.xenon.module.modules.client.XenonPlus.getBackgroundARGB();
        java.awt.Color ac = com.xenon.module.modules.client.XenonPlus.getAccentColor();
        COLOR_ACCENT_DIM = (0xFF << 24)
                | (Math.max(0, ac.getRed()   - 30) << 16)
                | (Math.max(0, ac.getGreen() - 35) << 8)
                |  Math.max(0, ac.getBlue()  - 20);

        updateAnimDt();
        uiScale = computeUiScale();
        int mx = (int) Math.round(mouseX / uiScale);
        int my = (int) Math.round(mouseY / uiScale);

        context.getMatrices().pushMatrix();
        context.getMatrices().scale(uiScale, uiScale);

        verticalScroll = clampVerticalScroll(verticalScroll);

        int searchX = 30;
        int searchY = 16;
        int searchW = Math.min(260, uiWidth() - 60);
        int searchOutline = searchActive ? COLOR_ACCENT : COLOR_SEARCH_OUTLINE;
        RenderUtil.drawRoundedRect(context, searchX, searchY, searchW, SEARCH_H, 8.0f, COLOR_PANEL_BG, false);
        RenderUtil.drawOutline(context, searchX, searchY, searchW, SEARCH_H, 8.0f, 1.0f, searchOutline, false);
        String searchText = searchQuery.isEmpty() ? "Search modules..." : searchQuery;
        int searchColor = searchQuery.isEmpty() && !searchActive ? COLOR_TEXT_MUTED : COLOR_TEXT;
        boolean blink = (System.currentTimeMillis() / 500L) % 2L == 0L;
        if (searchActive && blink) {
            searchText += "_";
        }
        drawInputTextClipped(context, searchX, searchY, searchW, SEARCH_H, searchText, searchX + 8, searchY + 6, searchColor);

        Category[] _cats = CACHED_CATEGORIES;
        for (int _ci = 0; _ci < _cats.length; _ci++) {
            Category category = _cats[_ci];
            int catX = getCategoryX(category, _ci);
            int catY = getCategoryY(category);
            int panelHeight = getPanelHeight(category);

            RenderUtil.drawRoundedRect(context, catX, catY, PANEL_W, panelHeight, PANEL_RADIUS, COLOR_PANEL_BG, false);
            RenderUtil.drawOutline(context, catX, catY, PANEL_W, panelHeight, PANEL_RADIUS, 1.0f, COLOR_PANEL_OUTLINE,
                    false);
            RenderUtil.drawRoundedRect(context, catX, catY, PANEL_W, PANEL_HEADER_H, PANEL_RADIUS, PANEL_RADIUS, 6.0f,
                    6.0f, false, COLOR_HEADER_BG);
            net.minecraft.item.ItemStack catIconStack = category.getIcon();
            float iconScale = 0.75f;
            int iconBoxX = catX + PANEL_PAD;
            int iconBoxY = catY + (PANEL_HEADER_H - (int)(16 * iconScale)) / 2;
            context.getMatrices().pushMatrix();
            context.getMatrices().translate(iconBoxX, iconBoxY);
            context.getMatrices().scale(iconScale, iconScale);
            context.drawItem(catIconStack, 0, 0);
            context.getMatrices().popMatrix();
            int iconAdvance = (int)(16 * iconScale) + 4;
            context.drawText(this.textRenderer, category.getName(), catX + PANEL_PAD + iconAdvance, catY + 6, COLOR_TEXT, false);
            RenderUtil.drawRoundedRect(context, catX + PANEL_PAD, catY + PANEL_HEADER_H - 2, PANEL_W - PANEL_PAD * 2,
                    1, 0.5f, COLOR_DIVIDER, false);
            int panelBottom = catY + panelHeight; // hard cutoff — nothing renders below this

            int modY = catY + PANEL_HEADER_H + PANEL_HEADER_SPACING;
            List<Module> modules = ModuleManager.INSTANCE.getModulesInCategory(category);
            int visibleCount = 0;
            for (Module module : modules) {
                if (!matchesQuery(module)) {
                    continue;
                }
                visibleCount++;
                boolean hovered = mx >= catX + 4 && mx <= catX + PANEL_W - 4
                        && my >= modY && my <= modY + ROW_H;
                String modKey = category.name() + "/" + module.getName();
                float hoverA   = anim(modKey + "/hover",   hovered ? 1f : 0f, 14f);
                float enabledA = anim(modKey + "/enabled", module.isEnabled() ? 1f : 0f, 12f);
                int rowBase = lerpARGB(COLOR_ROW_BG, COLOR_ROW_HOVER, hoverA);
                int rowColor  = lerpARGB(rowBase, COLOR_ROW_ACTIVE, enabledA);
                int textBase = lerpARGB(COLOR_TEXT_MUTED, COLOR_TEXT, hoverA);
                int textColor = lerpARGB(textBase, COLOR_ACCENT, enabledA);

                RenderUtil.drawRoundedRect(context, catX + 4, modY, PANEL_W - 8, ROW_H, ROW_RADIUS, rowColor, false);
                RenderUtil.drawOutline(context, catX + 4, modY, PANEL_W - 8, ROW_H, ROW_RADIUS, 1.0f,
                        COLOR_ROW_OUTLINE, false);
                if (enabledA > 0.01f) {
                    int accentFaded = (COLOR_ACCENT & 0x00FFFFFF) | (((int)(255 * enabledA)) << 24);
                    RenderUtil.drawRoundedRect(context, catX + 6, modY + 2, 3, ROW_H - 4, 1.5f, accentFaded, false);
                }
                String bindKey = getBindLabel(module);
                if (!bindKey.isEmpty()) {
                    int bindWidth = this.textRenderer.getWidth(bindKey) + 8;
                    int bindX = catX + PANEL_W - PANEL_PAD - bindWidth;
                    int bindY = modY + 3;
                    RenderUtil.drawRoundedRect(context, bindX, bindY, bindWidth, 10, 4.0f, COLOR_KEY_BG, false);
                    RenderUtil.drawOutline(context, bindX, bindY, bindWidth, 10, 4.0f, 1.0f, COLOR_ROW_OUTLINE, false);
                    context.drawText(this.textRenderer, bindKey, bindX + 4, bindY + 2, COLOR_TEXT_MUTED, false);
                }
                context.drawText(this.textRenderer, module.getName(), catX + PANEL_PAD + 4, modY + 4, textColor, false);
                modY += ROW_STEP;

                float expandP = getExpandProgress(module, modKey);
                int fullExpandH = getModuleExpandedHeight(module);
                int animExpandH = Math.round(expandP * fullExpandH);

                if (expandP > 0.001f) {
                    int contentStartY = modY;
                    float contentAlpha = easeOutCubic(expandP);
                    float contentSlideY = -(1f - contentAlpha) * 4f;

                    {
                        String bindKeyN = getKeyDisplayName(module.getBind());
                        String bindText = listeningBind == module ? "Bind: ..." : "Bind: " + bindKeyN;
                        float rowReveal = clamp01((animExpandH - (modY - contentStartY)) / (float) ROW_H);
                        float rowAlpha = contentAlpha * rowReveal;
                        context.getMatrices().pushMatrix();
                        context.getMatrices().translate(0.0f, contentSlideY);
                        RenderUtil.drawRoundedRect(context, catX + 4, modY, PANEL_W - 8, ROW_H, ROW_RADIUS,
                                multiplyAlpha(SLIDER_TRACK_COLOR_ARGB, rowAlpha), false);
                        RenderUtil.drawOutline(context, catX + 4, modY, PANEL_W - 8, ROW_H, ROW_RADIUS, 1.0f,
                                multiplyAlpha(COLOR_ROW_OUTLINE, rowAlpha), false);
                        context.drawText(this.textRenderer, bindText, catX + PANEL_PAD, modY + 4,
                                multiplyAlpha(COLOR_TEXT_MUTED, rowAlpha), false);
                        context.getMatrices().popMatrix();
                    }
                    modY += ROW_STEP;

                    if (module instanceof ActivatableModule activatableModule) {
                        {
                            String activationKeyName = getKeyDisplayName(activatableModule.getActivationKey());
                            String activationText = listeningActivationBind == activatableModule ? "Activation: ..." : "Activation: " + activationKeyName;
                            float rowReveal = clamp01((animExpandH - (modY - contentStartY)) / (float) ROW_H);
                            float rowAlpha = contentAlpha * rowReveal;
                            context.getMatrices().pushMatrix();
                            context.getMatrices().translate(0.0f, contentSlideY);
                            RenderUtil.drawRoundedRect(context, catX + 4, modY, PANEL_W - 8, ROW_H, ROW_RADIUS,
                                    multiplyAlpha(SLIDER_TRACK_COLOR_ARGB, rowAlpha), false);
                            RenderUtil.drawOutline(context, catX + 4, modY, PANEL_W - 8, ROW_H, ROW_RADIUS, 1.0f,
                                    multiplyAlpha(COLOR_ROW_OUTLINE, rowAlpha), false);
                            context.drawText(this.textRenderer, activationText, catX + PANEL_PAD, modY + 4,
                                    multiplyAlpha(COLOR_TEXT_MUTED, rowAlpha), false);
                            context.getMatrices().popMatrix();
                        }
                        modY += ROW_STEP;
                    }

                    for (Setting<?> setting : module.getSettings()) {
                        float rowReveal = clamp01((animExpandH - (modY - contentStartY)) / (float) ROW_H);
                        float rowAlpha = contentAlpha * rowReveal;
                        context.getMatrices().pushMatrix();
                        context.getMatrices().translate(0.0f, contentSlideY);

                        RenderUtil.drawRoundedRect(context, catX + 4, modY, PANEL_W - 8, ROW_H, ROW_RADIUS,
                                multiplyAlpha(COLOR_ROW_BG, rowAlpha), false);
                        RenderUtil.drawOutline(context, catX + 4, modY, PANEL_W - 8, ROW_H, ROW_RADIUS, 1.0f,
                                multiplyAlpha(COLOR_ROW_OUTLINE, rowAlpha), false);
                        Object val = setting.getValue();
                        if (setting instanceof ModeSetting modeSetting) {
                            drawModeSetting(context, modeSetting, catX + 4, PANEL_W - 8, modY, rowAlpha);
                        } else if (val instanceof Boolean) {
                            boolean enabled = (Boolean) val;
                            int toggleX = catX + PANEL_W - PANEL_PAD - 20;
                            int toggleY = modY + 4;
                            String tKey = System.identityHashCode(setting) + "/tog";
                            float t = anim(tKey, enabled ? 1f : 0f, 16f);
                            int trackColor = multiplyAlpha(lerpARGB(0xFF2F3745, COLOR_ACCENT_DIM, t), rowAlpha);
                            int knobColor  = multiplyAlpha(lerpARGB(0xFFE8EDF2, COLOR_ACCENT, t), rowAlpha);
                            int knobX      = toggleX + 2 + Math.round(10f * t);
                            int labelColor = multiplyAlpha(lerpARGB(COLOR_TEXT_MUTED, COLOR_ACCENT, t), rowAlpha);
                            RenderUtil.drawRoundedRect(context, toggleX, toggleY, 20, 8, 4.0f, trackColor, false);
                            RenderUtil.drawRoundedRect(context, knobX, toggleY + 1, 6, 6, 3.0f, knobColor, false);
                            context.drawText(this.textRenderer, setting.getName(), catX + PANEL_PAD, modY + 4,
                                    labelColor, false);
                        } else if (val instanceof Float || val instanceof Double || val instanceof Integer) {
                            float fval;
                            float max;
                            float min;
                            String displayValue;
                            if (val instanceof Integer integerValue && setting.getMin() instanceof Integer && setting.getMax() instanceof Integer) {
                                fval = integerValue;
                                min = (Integer) setting.getMin();
                                max = (Integer) setting.getMax();
                                displayValue = Integer.toString(integerValue);
                            } else {
                                fval = val instanceof Float ? (Float) val : (float) (double) (Double) val;
                                max = setting.getMax() instanceof Float ? (Float) setting.getMax() : (float) (double) (Double) setting.getMax();
                                min = setting.getMin() instanceof Float ? (Float) setting.getMin() : (float) (double) (Double) setting.getMin();
                                if (allowDecimalForModule(module)) {
                                    displayValue = String.format("%.1f", fval);
                                } else {
                                    fval = Math.round(fval);
                                    displayValue = Integer.toString(Math.round(fval));
                                }
                            }
                            float pct = (fval - min) / (max - min);
                            int barX = catX + PANEL_PAD;
                            int barY = modY + 11;
                            int barW = PANEL_W - PANEL_PAD * 2;
                            int fillW = (int) (barW * Math.max(0.0f, Math.min(1.0f, pct)));
                            RenderUtil.drawRoundedRect(context, barX, barY, barW, 3, 1.5f, multiplyAlpha(0xFF273141, rowAlpha), false);
                            RenderUtil.drawRoundedRect(context, barX, barY, fillW, 3, 1.5f, multiplyAlpha(COLOR_ACCENT_DIM, rowAlpha), false);

                            // Slider knob (drawn on top of bar fill)
                            float knobRectX = (barX + fillW) - 3f;
                            float knobRectY = (barY + 1.5f) - 3f;
                            RenderUtil.drawRoundedRect(context, knobRectX, knobRectY, 6f, 6f, 3.0f, multiplyAlpha(COLOR_ACCENT, rowAlpha), false);
                            RenderUtil.drawOutline(context, knobRectX, knobRectY, 6f, 6f, 3.0f, 1.0f, multiplyAlpha(0x99FFFFFF, rowAlpha), false);

                            String txt = setting.getName() + ": " + displayValue;
                            context.drawText(this.textRenderer, txt, catX + PANEL_PAD, modY + 2, multiplyAlpha(COLOR_TEXT, rowAlpha), false);
                        } else if (val instanceof String) {
                            String text;
                            if (isStringListSetting(module, setting)) {
                                int count = parseStringList(setting).size();
                                text = setting.getName() + ": " + count + " entries";
                                if (expandedStringListSetting == setting) {
                                    text += " (edit)";
                                }
                            } else {
                                String displayValue = formatStringSettingValue(module, setting, (String) val);
                                text = setting.getName() + ": " + displayValue;
                                if (listeningString == setting) text += "_";
                            }
                            int rowX = catX + 4;
                            int rowW = PANEL_W - 8;
                            drawInputTextClipped(context, rowX, modY, rowW, ROW_H, text, catX + PANEL_PAD, modY + 4, multiplyAlpha(COLOR_TEXT, rowAlpha));
                        } else if (setting instanceof BlocksSetting blocksSetting) {
                            drawBlocksSettingSummary(context, blocksSetting, catX + 4, PANEL_W - 8, modY, ROW_H, rowAlpha);
                        } else if (setting instanceof MobsSetting mobsSetting) {
                            drawMobsSettingSummary(context, mobsSetting, catX + 4, PANEL_W - 8, modY, ROW_H, rowAlpha);
                        } else if (val instanceof Color color) {
                            boolean hovered_ = mx >= catX + 4 && mx <= catX + PANEL_W - 4
                                    && my >= modY && my <= modY + ROW_H;
                            context.drawText(this.textRenderer, setting.getName(), catX + PANEL_PAD, modY + 4, multiplyAlpha(COLOR_TEXT, rowAlpha), false);
                            drawColorSetting(context, (Setting<Color>) setting, catX + 4, PANEL_W - 8, modY, ROW_H,
                                    hovered_ ? 1f : 0f, expandedColorSetting == setting ? 1f : 0f, rowAlpha);
                        }

                        context.getMatrices().popMatrix();
                        modY += ROW_STEP;
                        if (isStringListSetting(module, setting) && expandedStringListSetting == setting) {
                            float reveal = clamp01((animExpandH - (modY - contentStartY)) / (float) ROW_H);
                            float editorAlpha = contentAlpha * reveal;
                            if (editorAlpha > 0.01f) {
                                context.getMatrices().pushMatrix();
                                context.getMatrices().translate(0.0f, contentSlideY);
                                drawStringListEditor(context, (Setting<String>) setting, catX + 4, modY, PANEL_W - 8, editorAlpha);
                                context.getMatrices().popMatrix();
                            }
                            modY += getStringListEditorExtraHeight(setting);
                        }
                        if (setting instanceof BlocksSetting blocksSetting && expandedBlocksSetting == blocksSetting) {
                            float pickerReveal = clamp01((animExpandH - (modY - contentStartY)) / (float) ROW_H);
                            float pickerAlpha = contentAlpha * pickerReveal;
                            context.getMatrices().pushMatrix();
                            context.getMatrices().translate(0.0f, contentSlideY);
                            if (pickerAlpha > 0.01f) {
                                drawBlocksPicker(context, blocksSetting, catX + 4, PANEL_W - 8, modY, mouseX, mouseY);
                            }
                            context.getMatrices().popMatrix();
                            modY += getBlockPickerExtraHeight(blocksSetting);
                        }
                        if (setting instanceof MobsSetting mobsSetting && expandedMobsSetting == mobsSetting) {
                            float pickerReveal = clamp01((animExpandH - (modY - contentStartY)) / (float) ROW_H);
                            float pickerAlpha = contentAlpha * pickerReveal;
                            context.getMatrices().pushMatrix();
                            context.getMatrices().translate(0.0f, contentSlideY);
                            if (pickerAlpha > 0.01f) {
                                drawMobsPicker(context, mobsSetting, catX + 4, PANEL_W - 8, modY, mouseX, mouseY);
                            }
                            context.getMatrices().popMatrix();
                            modY += getMobPickerExtraHeight(mobsSetting);
                        }
                        if (setting.getValue() instanceof Color && expandedColorSetting == setting) {
                            modY += COLOR_PICKER_EXTRA_HEIGHT;
                        }
                    }

                    // Always snap modY to animated boundary — never let it exceed panelBottom
                    modY = contentStartY + animExpandH;
                }
            }
            if (visibleCount == 0) {
                RenderUtil.drawRoundedRect(context, catX + 4, modY, PANEL_W - 8, ROW_H, ROW_RADIUS, COLOR_ROW_BG, false);
                RenderUtil.drawOutline(context, catX + 4, modY, PANEL_W - 8, ROW_H, ROW_RADIUS, 1.0f,
                        COLOR_ROW_OUTLINE, false);
                context.drawText(this.textRenderer, "No results", catX + PANEL_PAD, modY + 4, COLOR_TEXT_MUTED, false);
            }
        }

        context.getMatrices().popMatrix();

        if (listeningBind != null) {
            for (int i = 32; i <= 348; i++) {
                if (i != GLFW.GLFW_KEY_ESCAPE
                        && i != GLFW.GLFW_KEY_BACKSPACE
                        && GLFW.glfwGetKey(MinecraftClient.getInstance().getWindow().getHandle(), i) == GLFW.GLFW_PRESS) {
                    listeningBind.setBind(i);
                    listeningBind = null;
                    break;
                }
            }
            if (listeningBind != null
                    && GLFW.glfwGetKey(MinecraftClient.getInstance().getWindow().getHandle(), GLFW.GLFW_KEY_BACKSPACE) == GLFW.GLFW_PRESS) {
                listeningBind.setBind(0);
                listeningBind = null;
            }
            if (GLFW.glfwGetKey(MinecraftClient.getInstance().getWindow().getHandle(), GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS) {
                listeningBind = null;
            }
        }

        if (listeningActivationBind != null) {
            for (int i = 32; i <= 348; i++) {
                if (i != GLFW.GLFW_KEY_ESCAPE
                        && i != GLFW.GLFW_KEY_BACKSPACE
                        && GLFW.glfwGetKey(MinecraftClient.getInstance().getWindow().getHandle(), i) == GLFW.GLFW_PRESS) {
                    listeningActivationBind.setActivationKey(i);
                    listeningActivationBind = null;
                    break;
                }
            }
            if (listeningActivationBind != null
                    && GLFW.glfwGetKey(MinecraftClient.getInstance().getWindow().getHandle(), GLFW.GLFW_KEY_BACKSPACE) == GLFW.GLFW_PRESS) {
                listeningActivationBind.setActivationKey(0);
                listeningActivationBind = null;
            }
            if (GLFW.glfwGetKey(MinecraftClient.getInstance().getWindow().getHandle(), GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS) {
                listeningActivationBind = null;
            }
        }

    }

    private void drawBlocksSettingSummary(DrawContext context, BlocksSetting setting, float panelX, float panelWidth, float rowY, int rowHeight, float revealAlpha) {
        String arrow = expandedBlocksSetting == setting ? "v" : ">";
        int arrowWidth = this.textRenderer.getWidth(arrow);
        int arrowX = Math.round(panelX + panelWidth - 6 - arrowWidth);
        int previewMaxWidth = Math.max(30, arrowX - (Math.round(panelX) + PANEL_PAD + this.textRenderer.getWidth(setting.getName()) + 14));
        int textColor = multiplyAlpha(expandedBlocksSetting == setting || setting.size() > 0 ? COLOR_TEXT : COLOR_TEXT_MUTED, revealAlpha);
        String previewText = setting.size() == 0 ? "Choose" : buildBlocksPreviewText(setting);
        String previewLabel = trimWithEllipsis(previewText, Math.round((previewMaxWidth - 18) / BLOCK_PICKER_TEXT_SCALE));
        int previewWidth = Math.max(34, Math.min(previewMaxWidth, this.textRenderer.getWidth(previewLabel) + 22));
        int previewX = arrowX - previewWidth - 6;
        int previewColor = multiplyAlpha(setting.size() > 0 ? COLOR_ACCENT_DIM : COLOR_KEY_BG, revealAlpha);
        ItemStack previewStack = getPreviewBlockStack(setting);

        context.drawText(this.textRenderer, setting.getName(), Math.round(panelX) + PANEL_PAD, Math.round(rowY) + 4, textColor, false);
        RenderUtil.drawRoundedRect(context, previewX, rowY + 2, previewWidth, 12, 5.0f, previewColor, false);
        RenderUtil.drawOutline(context, previewX, rowY + 2, previewWidth, 12, 5.0f, 1.0f, multiplyAlpha(COLOR_ROW_OUTLINE, revealAlpha), false);
        if (!previewStack.isEmpty()) {
            context.drawItem(previewStack, previewX + 2, Math.round(rowY) + 1);
        }
        int previewTextX = previewX + (previewStack.isEmpty() ? 6 : 16);
        drawScaledText(context, previewLabel, previewTextX, rowY + 4f, BLOCK_PICKER_TEXT_SCALE, multiplyAlpha(COLOR_TEXT, revealAlpha));
        context.drawText(this.textRenderer, arrow, arrowX, Math.round(rowY) + 4, multiplyAlpha(COLOR_TEXT_MUTED, revealAlpha), false);
    }

    private void drawBlocksPicker(
            DrawContext context,
            BlocksSetting setting,
            float panelX,
            float panelWidth,
            float pickerY,
            int mouseX,
            int mouseY
    ) {
        BlockPickerLayout layout = buildBlockPickerLayout(panelX, panelWidth, pickerY, setting);
        List<Block> filteredBlocks = getFilteredBlocks(setting);
        blockPickerScroll = clampBlockPickerScroll(filteredBlocks.size(), blockPickerScroll);

        RenderUtil.drawRoundedRect(context, layout.x, layout.y, layout.width, layout.height, 6.0f, COLOR_KEY_BG, false);
        RenderUtil.drawOutline(context, layout.x, layout.y, layout.width, layout.height, 6.0f, 1.0f, COLOR_ROW_OUTLINE, false);

        int searchOutline = blockSearchActive && expandedBlocksSetting == setting ? COLOR_ACCENT : COLOR_SEARCH_OUTLINE;
        RenderUtil.drawRoundedRect(context, layout.searchX, layout.searchY, layout.searchWidth, layout.searchHeight, 5.0f, COLOR_PANEL_BG, false);
        RenderUtil.drawOutline(context, layout.searchX, layout.searchY, layout.searchWidth, layout.searchHeight, 5.0f, 1.0f, searchOutline, false);
        RenderUtil.drawRoundedRect(context, layout.clearX, layout.clearY, layout.clearWidth, layout.clearHeight, 5.0f, COLOR_ROW_BG, false);
        RenderUtil.drawOutline(context, layout.clearX, layout.clearY, layout.clearWidth, layout.clearHeight, 5.0f, 1.0f, COLOR_ROW_OUTLINE, false);

        String searchText = blockSearchQuery.isEmpty() ? "Search blocks..." : blockSearchQuery;
        if (blockSearchActive && expandedBlocksSetting == setting && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            searchText += "_";
        }
        int searchColor = blockSearchQuery.isEmpty() && !blockSearchActive ? COLOR_TEXT_MUTED : COLOR_TEXT;
        drawInputTextClipped(
                context,
                layout.searchX,
                layout.searchY,
                // Clamp to container width at layout time: never exceed parent bounds.
                Math.max(0f, layout.searchWidth),
                Math.max(0f, layout.searchHeight),
                searchText,
                Math.round(layout.searchX) + 6,
                Math.round(layout.searchY) + 4,
                searchColor
        );
        context.drawText(this.textRenderer, "Clear", Math.round(layout.clearX) + 4, Math.round(layout.clearY) + 4, COLOR_TEXT_MUTED, false);

        if (filteredBlocks.isEmpty()) {
            RenderUtil.drawRoundedRect(context, layout.listX, layout.listY, layout.listWidth, BLOCK_PICKER_ROW_H - 2, 5.0f, COLOR_ROW_BG, false);
            RenderUtil.drawOutline(context, layout.listX, layout.listY, layout.listWidth, BLOCK_PICKER_ROW_H - 2, 5.0f, 1.0f, COLOR_ROW_OUTLINE, false);
            context.drawText(this.textRenderer, "No blocks found", Math.round(layout.listX) + 6, Math.round(layout.listY) + 4, COLOR_TEXT_MUTED, false);
            return;
        }

        int visibleRows = Math.min(BLOCK_PICKER_VISIBLE_ROWS, filteredBlocks.size());
        boolean showScrollbar = filteredBlocks.size() > visibleRows;
        float rowWidth = layout.listWidth;

        for (int row = 0; row < visibleRows; row++) {
            int index = blockPickerScroll + row;
            if (index >= filteredBlocks.size()) {
                break;
            }

            Block block = filteredBlocks.get(index);
            float rowY = layout.listY + (row * BLOCK_PICKER_ROW_H);
            boolean hovered = mouseX >= layout.listX && mouseX <= layout.listX + layout.listWidth
                    && mouseY >= rowY && mouseY <= rowY + BLOCK_PICKER_ROW_H - 2;
            boolean selected = setting.contains(block);
            int rowColor = selected ? COLOR_ROW_ACTIVE : (hovered ? COLOR_ROW_HOVER : COLOR_ROW_BG);
            int textColor = selected ? COLOR_ACCENT : COLOR_TEXT;

            RenderUtil.drawRoundedRect(context, layout.listX, rowY, rowWidth, BLOCK_PICKER_ROW_H - 2, 5.0f, rowColor, false);
            RenderUtil.drawOutline(context, layout.listX, rowY, rowWidth, BLOCK_PICKER_ROW_H - 2, 5.0f, 1.0f, COLOR_ROW_OUTLINE, false);

            ItemStack stack = new ItemStack(block);
            int textX = Math.round(layout.listX) + 5;
            if (!stack.isEmpty()) {
                context.drawItem(stack, Math.round(layout.listX) + 2, Math.round(rowY) + 1);
                textX += 16;
            }

            float indicatorX = layout.listX + rowWidth - 10;
            int textWidth = Math.max(20, Math.round(indicatorX) - textX - 4);
            String displayName = trimWithEllipsis(setting.getDisplayName(block), Math.round(textWidth / BLOCK_PICKER_TEXT_SCALE));
            drawScaledText(context, displayName, textX, rowY + 4f, BLOCK_PICKER_TEXT_SCALE, textColor);

            int indicatorColor = selected ? COLOR_ACCENT : COLOR_SEARCH_OUTLINE;
            RenderUtil.drawRoundedRect(context, indicatorX, rowY + 5, BLOCK_PICKER_INDICATOR_SIZE, BLOCK_PICKER_INDICATOR_SIZE, 2.5f, selected ? COLOR_ACCENT : COLOR_KEY_BG, false);
            RenderUtil.drawOutline(context, indicatorX, rowY + 5, BLOCK_PICKER_INDICATOR_SIZE, BLOCK_PICKER_INDICATOR_SIZE, 2.5f, 1.0f, indicatorColor, false);
        }

        if (showScrollbar) {
            int maxScroll = Math.max(1, filteredBlocks.size() - visibleRows);
            float trackX = layout.listX + layout.listWidth - BLOCK_PICKER_SCROLLBAR_W;
            float trackY = layout.listY + 1f;
            float trackHeight = layout.listHeight - 2f;
            float thumbHeight = Math.max(12f, trackHeight * (visibleRows / (float) filteredBlocks.size()));
            float thumbOffset = (trackHeight - thumbHeight) * (blockPickerScroll / (float) maxScroll);

            RenderUtil.drawRoundedRect(context, trackX, trackY, BLOCK_PICKER_SCROLLBAR_W, trackHeight, 2.0f, COLOR_PANEL_BG, false);
            RenderUtil.drawRoundedRect(context, trackX, trackY + thumbOffset, BLOCK_PICKER_SCROLLBAR_W, thumbHeight, 2.0f, COLOR_ACCENT_DIM, false);
        }
    }

    private void drawColorSetting(DrawContext context, Setting<Color> setting, float panelX, float panelWidth, float rowY, int rowHeight, float hoverProgress, float expansionProgress, float revealAlpha) {
        Color c = setting.getValue();
        String hex = String.format("#%06X", c.getRGB() & 0x00FFFFFF);
        int hexWidth = MinecraftClient.getInstance().textRenderer.getWidth(hex);
        int valueColor = multiplyAlpha(lerpColor(0xFF91D1FF, 0xFFC8ECFF, hoverProgress), revealAlpha);
        float swatchSize = 12f;
        float swatchX = panelX + panelWidth - 5f - swatchSize;
        float swatchY = rowY + ((rowHeight - 4f) - swatchSize) / 2f;

        //context.drawText(MinecraftClient.getInstance().textRenderer, hex, (int) (swatchX - 8 - hexWidth), (int) rowY + Math.max(4, (rowHeight - 12) / 2 - 1) - 7, valueColor, false);
        RenderUtil.drawRoundedRect(context, swatchX, swatchY + 2, swatchSize, swatchSize, 4f, multiplyAlpha(setting.getValue(), revealAlpha), false);
        RenderUtil.drawOutline(context, swatchX, swatchY + 2, swatchSize, swatchSize, 4f, 1f, multiplyAlpha(0xAAEFF6FB, revealAlpha), false);

        if (expansionProgress <= 0.01f) {
            return;
        }

        float reveal = easeOutCubic(expansionProgress) * revealAlpha;
        float fieldX = panelX + 18f;
        float fieldY = rowY + rowHeight + (COLOR_PICKER_GAP * reveal);
        float fieldWidth = panelWidth - 36f;
        float pickerHeight = Math.max(1f, COLOR_PICKER_FIELD_HEIGHT * reveal);
        float alphaHeight = Math.max(1f, COLOR_PICKER_ALPHA_HEIGHT * reveal);
        int contentAlpha = withAlpha(0xFFFFFFFF, reveal);
        float fieldInset = 1f;
        float innerFieldX = fieldX + fieldInset;
        float innerFieldY = fieldY + fieldInset;
        float innerFieldW = Math.max(1f, fieldWidth - (fieldInset * 2f));
        float innerFieldH = Math.max(1f, pickerHeight - (fieldInset * 2f));
        float innerFieldRadius = 5f;

        drawStripBar(context, innerFieldX, innerFieldY, innerFieldW, innerFieldH, innerFieldRadius, 48, index -> {
            float hue = index / 48f;
            int color = 0xFF000000 | (Color.HSBtoRGB(hue, 1f, 1f) & 0x00FFFFFF);
            return withAlpha(color, reveal);
        });
        RenderUtil.drawRoundedRect(context, innerFieldX, innerFieldY, innerFieldW, innerFieldH, innerFieldRadius, true,
                0x00000000, 0x00000000, withAlpha(0xFF000000, reveal), withAlpha(0xFF000000, reveal));
        RenderUtil.drawOutline(context, fieldX, fieldY, fieldWidth, pickerHeight, 6f, 1f, withAlpha(0x88465059, reveal), false);

        float cursorX = fieldX + getHue(c) * fieldWidth;
        float cursorY = fieldY + (1f - getBrightness(c)) * pickerHeight;
        RenderUtil.drawOutline(context, cursorX - 3f, cursorY - 3f, 6f, 6f, 3f, 1f, contentAlpha, false);

        float alphaY = fieldY + pickerHeight + (COLOR_PICKER_GAP * reveal);
        int rgb = c.getRGB() & 0x00FFFFFF;
        float innerAlphaX = fieldX + fieldInset;
        float innerAlphaY = alphaY + fieldInset;
        float innerAlphaW = Math.max(1f, fieldWidth - (fieldInset * 2f));
        float innerAlphaH = Math.max(1f, alphaHeight - (fieldInset * 2f));
        drawStripBar(context, innerAlphaX, innerAlphaY, innerAlphaW, innerAlphaH, 3f, 32, index -> {
            float alpha = index / 31f;
            return multiplyAlpha(((((int) (alpha * 255f)) << 24) | rgb), reveal);
        });
        RenderUtil.drawOutline(context, fieldX, alphaY, fieldWidth, alphaHeight, 4f, 1f, withAlpha(0x88465059, reveal), false);

        float alphaCursorX = fieldX + getAlphaFloat(c) * fieldWidth;
        RenderUtil.drawRoundedRect(context, alphaCursorX - 2f, alphaY - 2f, 4f, alphaHeight + 4f, 2f, withAlpha(0xFFF3FAFF, reveal), false);
    }

    public float getHue(Color c) {
        return Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null)[0];
    }

    public float getSaturation(Color c) {
        return Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null)[1];
    }

    public float getBrightness(Color c) {
        return Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null)[2];
    }

    public float getAlphaFloat(Color c) {
        return c.getAlpha() / 255f;
    }

    private void drawStripBar(DrawContext context, float x, float y, float width, float height, float radius, int segments, java.util.function.IntFunction<Integer> colorProvider) {
        if (height <= 0f) {
            return;
        }
        float segmentWidth = Math.max(1f, width / segments);
        for (int i = 0; i < segments; i++) {
            float segmentX = x + (segmentWidth * i);
            float drawWidth = i == segments - 1 ? (x + width) - segmentX : segmentWidth + 1f;
            float leftRadius = i == 0 ? radius : 0f;
            float rightRadius = i == segments - 1 ? radius : 0f;
            int color = colorProvider.apply(i);
            RenderUtil.drawRoundedRect(context, segmentX, y, drawWidth, height, leftRadius, rightRadius, rightRadius, leftRadius, false, color);
        }
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        //nop
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean bl) {
        uiScale = computeUiScale();
        double mouseX = toUiX(click.x());
        double mouseY = toUiY(click.y());
        int button = click.button();
        activeColorSetting = null;
        colorDragMode = ColorDragMode.NONE;
        draggingNumericSetting = null;
        draggingNumericModule = null;
        int searchX = 30;
        int searchY = 16;
        int searchW = Math.min(260, uiWidth() - 60);
        if (mouseX >= searchX && mouseX <= searchX + searchW && mouseY >= searchY && mouseY <= searchY + SEARCH_H) {
            searchActive = true;
            blockSearchActive = false;
            listeningBind = null;
            listeningActivationBind = null;
            listeningString = null;
            return true;
        }
        searchActive = false;

        // --- Category header drag start ---
        if (button == 0) {
            Category[] dragCats = CACHED_CATEGORIES;
            for (int i = 0; i < dragCats.length; i++) {
                int hx = getCategoryX(dragCats[i], i);
                int hy = getCategoryY(dragCats[i]);
                if (mouseX >= hx && mouseX <= hx + PANEL_W
                        && mouseY >= hy && mouseY <= hy + PANEL_HEADER_H) {
                    draggingCategory = dragCats[i];
                    dragGrabOffsetX = (int) (mouseX - hx);
                    dragGrabOffsetY = (int) (mouseY - hy);
                    return true;
                }
            }
        }

        Category[] _cats2 = CACHED_CATEGORIES;
        for (int _ci2 = 0; _ci2 < _cats2.length; _ci2++) {
            Category category = _cats2[_ci2];
            int catX = getCategoryX(category, _ci2);
            int catY = getCategoryY(category);
            int modY = catY + PANEL_HEADER_H + PANEL_HEADER_SPACING;
            for (Module module : ModuleManager.INSTANCE.getModulesInCategory(category)) {
                if (!matchesQuery(module)) {
                    continue;
                }
                if (mouseX >= catX + 4 && mouseX <= catX + PANEL_W - 4 && mouseY >= modY && mouseY <= modY + ROW_H) {
                    if (button == 0) module.toggle();
                    else if (button == 1) module.setExpanded(!module.isExpanded());
                    return true;
                }
                modY += ROW_STEP;

                if (module.isExpanded()) {
                    if (mouseX >= catX + 4 && mouseX <= catX + PANEL_W - 4 && mouseY >= modY && mouseY <= modY + ROW_H) {
                        if (button == 1) {
                            module.setBind(0);
                            listeningBind = null;
                            listeningActivationBind = null;
                        } else if (button == 0) {
                            listeningBind = module;
                            listeningActivationBind = null;
                        }
                        return true;
                    }
                    modY += ROW_STEP;

                    if (module instanceof ActivatableModule activatableModule) {
                        if (mouseX >= catX + 4 && mouseX <= catX + PANEL_W - 4 && mouseY >= modY && mouseY <= modY + ROW_H) {
                            if (button == 1) {
                                activatableModule.setActivationKey(0);
                                listeningActivationBind = null;
                                listeningBind = null;
                            } else if (button == 0) {
                                listeningActivationBind = activatableModule;
                                listeningBind = null;
                            }
                            return true;
                        }
                        modY += ROW_STEP;
                    }

                    for (Setting<?> setting : module.getSettings()) {
                        if (mouseX >= catX + 4 && mouseX <= catX + PANEL_W - 4 && mouseY >= modY && mouseY <= modY + ROW_H) {
                            if (setting instanceof ModeSetting modeSetting) {
                                if (button == 1) {
                                    modeSetting.cyclePrevious();
                                } else {
                                    modeSetting.cycleNext();
                                }
                            } else if (setting.getValue() instanceof Boolean) {
                                ((Setting<Boolean>)setting).setValue(!(Boolean)setting.getValue());
                            } else if (setting.getValue() instanceof String) {
                                if (isStringListSetting(module, setting)) {
                                    if (button == 0) {
                                        expandedStringListSetting = expandedStringListSetting == setting ? null : (Setting<String>) setting;
                                        stringListAddActive = expandedStringListSetting == setting;
                                        stringListAddBuffer = "";
                                        listeningString = null;
                                    } else if (button == 1) {
                                        expandedStringListSetting = null;
                                        stringListAddActive = false;
                                        stringListAddBuffer = "";
                                    }
                                } else {
                                    expandedStringListSetting = null;
                                    stringListAddActive = false;
                                    stringListAddBuffer = "";
                                    listeningString = (Setting<String>)setting;
                                }
                            } else if (setting.getValue() instanceof Float || setting.getValue() instanceof Double || setting.getValue() instanceof Integer) {
                                if (button == 0) {
                                    draggingNumericSetting = setting;
                                    draggingNumericModule = module;
                                    draggingNumericCatX = catX;
                                    updateNumericSetting(module, setting, mouseX, catX);
                                }
                            } else if (setting instanceof BlocksSetting blocksSetting) {
                                if (button == 0) {
                                    if (expandedBlocksSetting != blocksSetting) {
                                        blockSearchQuery = "";
                                        blockPickerScroll = 0;
                                    }
                                    expandedBlocksSetting = expandedBlocksSetting == blocksSetting ? null : blocksSetting;
                                    blockSearchActive = expandedBlocksSetting == blocksSetting;
                                } else if (button == 1) {
                                    blocksSetting.clear();
                                    blockPickerScroll = 0;
                                }
                            } else if (setting instanceof MobsSetting mobsSetting) {
                                if (button == 0) {
                                    if (expandedMobsSetting != mobsSetting) {
                                        mobSearchQuery = "";
                                        mobPickerScroll = 0;
                                    }
                                    expandedMobsSetting = expandedMobsSetting == mobsSetting ? null : mobsSetting;
                                    mobSearchActive = expandedMobsSetting == mobsSetting;
                                } else if (button == 1) {
                                    mobsSetting.clear();
                                    mobPickerScroll = 0;
                                }
                            } else if (setting.getValue() instanceof Color) {
                                if (button == 0) {
                                    expandedColorSetting = expandedColorSetting == setting ? null : (Setting<Color>) setting;
                                } else if (button == 1) {
                                    expandedColorSetting = null;
                                }
                            }
                            return true;
                        }
                        if (isStringListSetting(module, setting) && expandedStringListSetting == setting) {
                            int editorY = modY + ROW_STEP;
                            int editorH = getStringListEditorExtraHeight(setting);
                            int editorX = catX + 4;
                            int editorW = PANEL_W - 8;
                            if (pointInRect(mouseX, mouseY, editorX, editorY, editorW, editorH)) {
                                @SuppressWarnings("unchecked")
                                Setting<String> s = (Setting<String>) setting;
                                List<String> names = parseStringList(s);
                                int visible = Math.min(6, names.size());
                                for (int i = 0; i < visible; i++) {
                                    int rowTop = editorY + (i * ROW_STEP);
                                    int btnSize = 12;
                                    int btnX = editorX + editorW - PANEL_PAD - btnSize;
                                    int btnY = rowTop + 2;
                                    if (pointInRect(mouseX, mouseY, btnX, btnY, btnSize, btnSize) && button == 0) {
                                        String toRemove = names.get(i);
                                        names.removeIf(n -> n.equalsIgnoreCase(toRemove));
                                        setStringListFromLowerList(s, names);
                                        return true;
                                    }
                                }

                                int addRowTop = editorY + (visible * ROW_STEP);
                                int plusSize = 12;
                                int plusX = editorX + editorW - PANEL_PAD - plusSize;
                                int plusY = addRowTop + 2;
                                if (button == 0 && pointInRect(mouseX, mouseY, plusX, plusY, plusSize, plusSize)) {
                                    String candidate = stringListAddBuffer == null ? "" : stringListAddBuffer.trim();
                                    if (!candidate.isEmpty()) {
                                        String lower = candidate.toLowerCase(Locale.ROOT);
                                        boolean exists = false;
                                        for (String n : names) {
                                            if (n.equalsIgnoreCase(lower)) { exists = true; break; }
                                        }
                                        if (!exists) {
                                            names.add(lower);
                                            setStringListFromLowerList(s, names);
                                        }
                                    }
                                    stringListAddBuffer = "";
                                    stringListAddActive = true;
                                    listeningString = null;
                                    return true;
                                }

                                // Click anywhere in the add row to focus typing.
                                if (button == 0 && pointInRect(mouseX, mouseY, editorX, addRowTop, editorW, ROW_H)) {
                                    stringListAddActive = true;
                                    listeningString = null;
                                    return true;
                                }
                                return true;
                            }
                        }
                        if (setting instanceof BlocksSetting blocksSetting && expandedBlocksSetting == blocksSetting) {
                            BlockPickerLayout layout = buildBlockPickerLayout(catX + 4, PANEL_W - 8, modY + ROW_STEP, blocksSetting);
                            if (pointInRect(mouseX, mouseY, layout.clearX, layout.clearY, layout.clearWidth, layout.clearHeight)) {
                                blocksSetting.clear();
                                blockPickerScroll = 0;
                                blockSearchQuery = "";
                                return true;
                            }
                            if (pointInRect(mouseX, mouseY, layout.searchX, layout.searchY, layout.searchWidth, layout.searchHeight)) {
                                blockSearchActive = true;
                                searchActive = false;
                                listeningString = null;
                                return true;
                            }
                            if (pointInRect(mouseX, mouseY, layout.x, layout.y, layout.width, layout.height)) {
                                blockSearchActive = false;
                                List<Block> filteredBlocks = getFilteredBlocks(blocksSetting);
                                int visibleRows = Math.min(BLOCK_PICKER_VISIBLE_ROWS, Math.max(1, filteredBlocks.size()));
                                for (int row = 0; row < visibleRows; row++) {
                                    int index = clampBlockPickerScroll(filteredBlocks.size(), blockPickerScroll) + row;
                                    if (index >= filteredBlocks.size()) {
                                        break;
                                    }

                                    float optionY = layout.listY + (row * BLOCK_PICKER_ROW_H);
                                    if (!pointInRect(mouseX, mouseY, layout.listX, optionY, layout.listWidth, BLOCK_PICKER_ROW_H - 2)) {
                                        continue;
                                    }

                                    blocksSetting.toggle(filteredBlocks.get(index));
                                    return true;
                                }
                                return true;
                            }
                        }
                        if (setting instanceof MobsSetting mobsSetting && expandedMobsSetting == mobsSetting) {
                            BlockPickerLayout layout = buildMobPickerLayout(catX + 4, PANEL_W - 8, modY + ROW_STEP, mobsSetting);
                            if (pointInRect(mouseX, mouseY, layout.clearX, layout.clearY, layout.clearWidth, layout.clearHeight)) {
                                mobsSetting.clear();
                                mobPickerScroll = 0;
                                mobSearchQuery = "";
                                return true;
                            }
                            if (pointInRect(mouseX, mouseY, layout.searchX, layout.searchY, layout.searchWidth, layout.searchHeight)) {
                                mobSearchActive = true;
                                searchActive = false;
                                listeningString = null;
                                return true;
                            }
                            if (pointInRect(mouseX, mouseY, layout.x, layout.y, layout.width, layout.height)) {
                                mobSearchActive = false;
                                List<EntityType<?>> filteredMobs = getFilteredMobs(mobsSetting);
                                int visibleRows = Math.min(BLOCK_PICKER_VISIBLE_ROWS, Math.max(1, filteredMobs.size()));
                                for (int row = 0; row < visibleRows; row++) {
                                    int index = clampMobPickerScroll(filteredMobs.size(), mobPickerScroll) + row;
                                    if (index >= filteredMobs.size()) break;
                                    float optionY = layout.listY + (row * BLOCK_PICKER_ROW_H);
                                    if (!pointInRect(mouseX, mouseY, layout.listX, optionY, layout.listWidth, BLOCK_PICKER_ROW_H - 2)) continue;
                                    mobsSetting.toggle(filteredMobs.get(index));
                                    return true;
                                }
                                return true;
                            }
                        }
                        if (button == 0 && setting.getValue() instanceof Color && expandedColorSetting == setting) {
                            ColorPickerLayout layout = buildColorPickerLayout(catX + 4, PANEL_W - 8, modY, ROW_H);
                            if (pointInRect(mouseX, mouseY, layout.fieldX, layout.fieldY, layout.fieldWidth, layout.fieldHeight)) {
                                updateColorFromField((Setting<Color>) setting, layout, mouseX, mouseY);
                                activeColorSetting = (Setting<Color>) setting;
                                colorDragMode = ColorDragMode.FIELD;
                                return true;
                            }
                            if (pointInRect(mouseX, mouseY, layout.fieldX, layout.alphaY, layout.fieldWidth, layout.alphaHeight)) {
                                updateColorFromAlpha((Setting<Color>) setting, layout, mouseX);
                                activeColorSetting = (Setting<Color>) setting;
                                colorDragMode = ColorDragMode.ALPHA;
                                return true;
                            }
                        }
                        modY += ROW_STEP;
                        if (isStringListSetting(module, setting) && expandedStringListSetting == setting) {
                            modY += getStringListEditorExtraHeight(setting);
                        }
                        if (setting instanceof BlocksSetting blocksSetting && expandedBlocksSetting == blocksSetting) {
                            modY += getBlockPickerExtraHeight(blocksSetting);
                        }
                        if (setting instanceof MobsSetting mobsSetting && expandedMobsSetting == mobsSetting) {
                            modY += getMobPickerExtraHeight(mobsSetting);
                        }
                        if (setting.getValue() instanceof Color && expandedColorSetting == setting) {
                            modY += COLOR_PICKER_EXTRA_HEIGHT;
                        }
                    }
                }
            }
        }
        listeningBind = null;
        listeningActivationBind = null;
        listeningString = null;
        stringListAddActive = false;
        blockSearchActive = false;
        return super.mouseClicked(click, bl);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.gui.Click click, double deltaX, double deltaY) {
        uiScale = computeUiScale();
        double mouseX = toUiX(click.x());
        double mouseY = toUiY(click.y());
        int button = click.button();
        if (button != 0) return super.mouseDragged(click, deltaX, deltaY);
        if (colorDragMode != ColorDragMode.NONE && activeColorSetting != null) {
            if (updateActiveColorDrag(mouseX, mouseY)) {
                return true;
            }
        }

        if (draggingNumericSetting != null && draggingNumericModule != null) {
            updateNumericSetting(draggingNumericModule, draggingNumericSetting, mouseX, draggingNumericCatX);
            return true;
        }

        // --- Category move drag ---
        if (draggingCategory != null) {
            Category[] cArr = CACHED_CATEGORIES;
            int idx = 0;
            for (int i = 0; i < cArr.length; i++) if (cArr[i] == draggingCategory) { idx = i; break; }
            int defaultX = 30 + idx * (PANEL_W + PANEL_GAP);
            int defaultY = getContentTop() + verticalScroll;
            int newX = (int) (mouseX - dragGrabOffsetX);
            int newY = (int) (mouseY - dragGrabOffsetY);
            int[] off = getCategoryOffset(draggingCategory);
            off[0] = newX - defaultX;
            off[1] = newY - defaultY;
            return true;
        }

        Category[] _cats3 = CACHED_CATEGORIES;
        for (int _ci3 = 0; _ci3 < _cats3.length; _ci3++) {
            Category category = _cats3[_ci3];
            int catX = getCategoryX(category, _ci3);
            int catY = getCategoryY(category);
            int modY = catY + PANEL_HEADER_H + PANEL_HEADER_SPACING;
            for (Module module : ModuleManager.INSTANCE.getModulesInCategory(category)) {
                if (!matchesQuery(module)) {
                    continue;
                }
                modY += ROW_STEP;
                if (module.isExpanded()) {
                    modY += ROW_STEP;
                    modY += ROW_STEP;
                    for (Setting<?> setting : module.getSettings()) {
                        if (mouseX >= catX + 4 && mouseX <= catX + PANEL_W - 4 && mouseY >= modY && mouseY <= modY + ROW_H) {
                            if (setting.getValue() instanceof Float || setting.getValue() instanceof Double || setting.getValue() instanceof Integer) {
                                updateNumericSetting(module, setting, mouseX, catX);
                            }
                        }
                        modY += ROW_STEP;
                        if (setting instanceof BlocksSetting blocksSetting && expandedBlocksSetting == blocksSetting) {
                            modY += getBlockPickerExtraHeight(blocksSetting);
                        }
                        if (setting instanceof MobsSetting mobsSetting && expandedMobsSetting == mobsSetting) {
                            modY += getMobPickerExtraHeight(mobsSetting);
                        }
                        if (setting.getValue() instanceof Color && expandedColorSetting == setting) {
                            modY += COLOR_PICKER_EXTRA_HEIGHT;
                        }
                    }
                }
            }
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        activeColorSetting = null;
        colorDragMode = ColorDragMode.NONE;
        draggingCategory = null;
        draggingNumericSetting = null;
        draggingNumericModule = null;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        uiScale = computeUiScale();
        mouseX = toUiX(mouseX);
        mouseY = toUiY(mouseY);
        double amount = verticalAmount != 0.0 ? verticalAmount : horizontalAmount;
        if (amount == 0.0) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        BlockPickerContext pickerContext = getExpandedBlocksPickerContext();
        if (pickerContext != null && pointInRect(mouseX, mouseY, pickerContext.layout.x, pickerContext.layout.y, pickerContext.layout.width, pickerContext.layout.height)) {
            int direction = amount > 0.0 ? -1 : 1;
            blockPickerScroll = clampBlockPickerScroll(
                    getFilteredBlocks(pickerContext.setting).size(),
                    blockPickerScroll + direction
            );
            return true;
        }
        MobPickerContext mobCtx = getExpandedMobsPickerContext();
        if (mobCtx != null && pointInRect(mouseX, mouseY, mobCtx.layout.x, mobCtx.layout.y, mobCtx.layout.width, mobCtx.layout.height)) {
            int direction = amount > 0.0 ? -1 : 1;
            mobPickerScroll = clampMobPickerScroll(
                    getFilteredMobs(mobCtx.setting).size(),
                    mobPickerScroll + direction
            );
            return true;
        }

        verticalScroll = clampVerticalScroll(verticalScroll + (int) Math.round(amount * SCROLL_STEP));
        return true;
    }

    @Override
    public boolean charTyped(CharInput input) {
        String typed = sanitizeTextInput(input.asString());
        if (typed.isEmpty()) {
            return super.charTyped(input);
        }

        if (blockSearchActive && expandedBlocksSetting != null) {
            blockSearchQuery += typed;
            blockPickerScroll = 0;
            return true;
        }
        if (mobSearchActive && expandedMobsSetting != null) {
            mobSearchQuery += typed;
            mobPickerScroll = 0;
            return true;
        }
        if (searchActive && listeningString == null) {
            searchQuery += typed;
            return true;
        }
        if (stringListAddActive && expandedStringListSetting != null) {
            stringListAddBuffer = (stringListAddBuffer == null ? "" : stringListAddBuffer) + typed;
            return true;
        }
        if (listeningString != null) {
            listeningString.setValue(listeningString.getValue() + typed);
            return true;
        }
        return super.charTyped(input);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (blockSearchActive && expandedBlocksSetting != null) {
            if (handleBlockSearchKeyInput(input)) {
                return true;
            }
        }
        if (mobSearchActive && expandedMobsSetting != null) {
            if (handleMobSearchKeyInput(input)) {
                return true;
            }
        }

        if (searchActive) {
            if (handleSearchKeyInput(input)) {
                searchActive = false;
                return true;
            }
            if (input.getKeycode() == GLFW.GLFW_KEY_BACKSPACE) {
                searchQuery = removeLastCodePoint(searchQuery);
                return true;
            }
            if (input.isPaste()) {
                searchQuery += getClipboardText();
                return true;
            }
            return true;
        }

        if (listeningString != null) {
            if (handleStringKeyInput(input)) {
                return true;
            }
        }
        if (stringListAddActive && expandedStringListSetting != null) {
            if (handleFriendsAddKeyInput(input)) {
                return true;
            }
        }
        return super.keyPressed(input);
    }

    private boolean handleFriendsAddKeyInput(KeyInput input) {
        if (input.getKeycode() == GLFW.GLFW_KEY_BACKSPACE) {
            stringListAddBuffer = removeLastCodePoint(stringListAddBuffer == null ? "" : stringListAddBuffer);
            return true;
        }
        if (input.isPaste()) {
            stringListAddBuffer = (stringListAddBuffer == null ? "" : stringListAddBuffer) + getClipboardText();
            return true;
        }
        if (input.isEscape()) {
            stringListAddActive = false;
            stringListAddBuffer = "";
            return true;
        }
        if (input.isEnter()) {
            if (expandedStringListSetting != null) {
                List<String> names = parseStringList(expandedStringListSetting);
                String candidate = stringListAddBuffer == null ? "" : stringListAddBuffer.trim();
                if (!candidate.isEmpty()) {
                    String lower = candidate.toLowerCase(Locale.ROOT);
                    boolean exists = false;
                    for (String n : names) {
                        if (n.equalsIgnoreCase(lower)) { exists = true; break; }
                    }
                    if (!exists) {
                        names.add(lower);
                        setStringListFromLowerList(expandedStringListSetting, names);
                    }
                }
            }
            stringListAddBuffer = "";
            return true;
        }
        return true;
    }

    private boolean matchesQuery(Module module) {
        if (searchQuery.isBlank()) {
            return true;
        }
        return module.getName().toLowerCase().contains(searchQuery.trim().toLowerCase());
    }

    private String getBindLabel(Module module) {
        String keyName = getKeyDisplayName(module.getBind());
        return "None".equals(keyName) ? "" : keyName;
    }

    private String getKeyDisplayName(int keyCode) { return getKeyDisplayNameStatic(keyCode); }

    public static String getKeyDisplayNameStatic(int keyCode) {
        if (keyCode == 0) {
            return "None";
        }

        String glfwName = GLFW.glfwGetKeyName(keyCode, 0);
        if (glfwName != null && !glfwName.isBlank()) {
            return normalizeKeyName(glfwName);
        }

        return switch (keyCode) {
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> "RShift";
            case GLFW.GLFW_KEY_LEFT_SHIFT -> "LShift";
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> "RCtrl";
            case GLFW.GLFW_KEY_LEFT_CONTROL -> "LCtrl";
            case GLFW.GLFW_KEY_RIGHT_ALT -> "RAlt";
            case GLFW.GLFW_KEY_LEFT_ALT -> "LAlt";
            case GLFW.GLFW_KEY_ENTER -> "Enter";
            case GLFW.GLFW_KEY_TAB -> "Tab";
            case GLFW.GLFW_KEY_BACKSPACE -> "Backspace";
            case GLFW.GLFW_KEY_INSERT -> "Insert";
            case GLFW.GLFW_KEY_DELETE -> "Delete";
            case GLFW.GLFW_KEY_HOME -> "Home";
            case GLFW.GLFW_KEY_END -> "End";
            case GLFW.GLFW_KEY_PAGE_UP -> "Page Up";
            case GLFW.GLFW_KEY_PAGE_DOWN -> "Page Down";
            case GLFW.GLFW_KEY_ESCAPE -> "Esc";
            case GLFW.GLFW_KEY_SPACE -> "Space";
            case GLFW.GLFW_KEY_CAPS_LOCK -> "Caps";
            case GLFW.GLFW_KEY_UP -> "Up";
            case GLFW.GLFW_KEY_DOWN -> "Down";
            case GLFW.GLFW_KEY_LEFT -> "Left";
            case GLFW.GLFW_KEY_RIGHT -> "Right";
            default -> "Key " + keyCode;
        };
    }

    private static String normalizeKeyName(String value) {
        if (value == null) {
            return "";
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }

        return switch (trimmed.toLowerCase()) {
            case "right shift" -> "RShift";
            case "left shift" -> "LShift";
            case "right control", "right ctrl" -> "RCtrl";
            case "left control", "left ctrl" -> "LCtrl";
            case "right alt" -> "RAlt";
            case "left alt" -> "LAlt";
            case "escape" -> "Esc";
            case "caps lock" -> "Caps";
            case "page up" -> "Page Up";
            case "page down" -> "Page Down";
            default -> {
                if (trimmed.length() == 1) {
                    yield trimmed.toUpperCase();
                }
                yield trimmed;
            }
        };
    }

    private void drawModeSetting(DrawContext context, ModeSetting setting, int rowX, int rowWidth, int rowY, float revealAlpha) {
        String label = setting.getName();
        String value = setting.getValue();
        int valueWidth = this.textRenderer.getWidth(value) + 12;
        int valueX = rowX + rowWidth - PANEL_PAD - valueWidth;
        int textY = rowY + 4;

        int labelX = rowX + PANEL_PAD;
        int maxLabelW = Math.max(0, valueX - 4 - labelX);
        String displayLabel = label;
        if (this.textRenderer.getWidth(displayLabel) > maxLabelW) {
            String ellipsis = "...";
            int ellW = this.textRenderer.getWidth(ellipsis);
            while (displayLabel.length() > 0 && this.textRenderer.getWidth(displayLabel) + ellW > maxLabelW) {
                displayLabel = displayLabel.substring(0, displayLabel.length() - 1);
            }
            displayLabel = displayLabel + ellipsis;
        }
        context.drawText(this.textRenderer, displayLabel, labelX, textY, multiplyAlpha(COLOR_TEXT, revealAlpha), false);
        RenderUtil.drawRoundedRect(context, valueX, rowY + 2, valueWidth, 12, 5.0f, multiplyAlpha(COLOR_KEY_BG, revealAlpha), false);
        RenderUtil.drawOutline(context, valueX, rowY + 2, valueWidth, 12, 5.0f, 1.0f, multiplyAlpha(COLOR_ROW_OUTLINE, revealAlpha), false);
        context.drawText(this.textRenderer, value, valueX + 6, textY, multiplyAlpha(COLOR_ACCENT, revealAlpha), false);
    }

    private void drawStringListEditor(DrawContext context, Setting<String> setting, int x, int y, int w, float alpha) {
        List<String> names = parseStringList(setting);
        int visible = Math.min(6, names.size());
        int h = getStringListEditorExtraHeight(setting);

        RenderUtil.drawRoundedRect(context, x, y, w, h, ROW_RADIUS, multiplyAlpha(COLOR_ROW_BG, alpha), false);
        RenderUtil.drawOutline(context, x, y, w, h, ROW_RADIUS, 1.0f, multiplyAlpha(COLOR_ROW_OUTLINE, alpha), false);

        int rowY = y;
        for (int i = 0; i < visible; i++) {
            String name = names.get(i);
            context.drawText(this.textRenderer, name, x + PANEL_PAD, rowY + 4, multiplyAlpha(COLOR_TEXT, alpha), false);

            int btnSize = 12;
            int btnX = x + w - PANEL_PAD - btnSize;
            int btnY = rowY + 2;
            RenderUtil.drawRoundedRect(context, btnX, btnY, btnSize, btnSize, 4.0f, multiplyAlpha(0xFF2A3445, alpha), false);
            RenderUtil.drawOutline(context, btnX, btnY, btnSize, btnSize, 4.0f, 1.0f, multiplyAlpha(COLOR_ROW_OUTLINE, alpha), false);
            context.drawText(this.textRenderer, "x", btnX + 4, rowY + 4, multiplyAlpha(0xFFE26A6A, alpha), false);

            rowY += ROW_STEP;
        }

        String addText = "Add: " + (stringListAddBuffer == null ? "" : stringListAddBuffer);
        if (stringListAddActive && expandedStringListSetting == setting) {
            addText += "_";
        }
        int addRowX = x + PANEL_PAD;
        // Clamp to editor container bounds (parent panel) so the input text/caret can't bleed.
        drawInputTextClipped(
                context,
                x,
                rowY,
                w,
                ROW_H,
                addText,
                addRowX,
                rowY + 4,
                multiplyAlpha(COLOR_TEXT_MUTED, alpha)
        );

        int plusSize = 12;
        int plusX = x + w - PANEL_PAD - plusSize;
        int plusY = rowY + 2;
        RenderUtil.drawRoundedRect(context, plusX, plusY, plusSize, plusSize, 4.0f, multiplyAlpha(COLOR_KEY_BG, alpha), false);
        RenderUtil.drawOutline(context, plusX, plusY, plusSize, plusSize, 4.0f, 1.0f, multiplyAlpha(COLOR_ROW_OUTLINE, alpha), false);
        context.drawText(this.textRenderer, "+", plusX + 4, rowY + 4, multiplyAlpha(COLOR_ACCENT, alpha), false);
    }

    private int getPanelHeight(Category category) {
        int height = PANEL_HEADER_H + PANEL_HEADER_SPACING + PANEL_PAD;
        int visibleCount = 0;
        List<Module> modules = ModuleManager.INSTANCE.getModulesInCategory(category);
        for (Module module : modules) {
            if (!matchesQuery(module)) continue;
            visibleCount++;
            height += ROW_STEP;
            // Use animated progress so the panel background grows/shrinks smoothly.
            String modKey = category.name() + "/" + module.getName();
            float p = animValues.getOrDefault(modKey + "/expand", module.isExpanded() ? 1f : 0f);
            if (p > 0.001f) {
                height += Math.round(getModuleExpandedHeight(module) * p);
            }
        }
        if (visibleCount == 0) height += ROW_STEP;
        return height;
    }

    private BlockPickerLayout buildBlockPickerLayout(float panelX, float panelWidth, float pickerY, BlocksSetting setting) {
        int filteredCount = getFilteredBlocks(setting).size();
        int visibleRows = Math.min(BLOCK_PICKER_VISIBLE_ROWS, Math.max(1, filteredCount));
        float layoutWidth = panelWidth;
        float layoutX = panelX;
        float searchX = layoutX + 6f;
        float searchY = pickerY + BLOCK_PICKER_GAP;
        float clearX = layoutX + layoutWidth - BLOCK_PICKER_CLEAR_W - 6f;
        float searchWidth = Math.max(24f, clearX - searchX - 4f);
        float listX = layoutX + 6f;
        float listY = searchY + BLOCK_PICKER_SEARCH_H + BLOCK_PICKER_GAP;
        float listWidth = layoutWidth - 12f;
        float listHeight = visibleRows * BLOCK_PICKER_ROW_H;
        float height = BLOCK_PICKER_GAP + BLOCK_PICKER_SEARCH_H + BLOCK_PICKER_GAP + listHeight + BLOCK_PICKER_BOTTOM_PAD;
        return new BlockPickerLayout(
                layoutX,
                pickerY,
                layoutWidth,
                height,
                searchX,
                searchY,
                searchWidth,
                BLOCK_PICKER_SEARCH_H,
                clearX,
                searchY,
                BLOCK_PICKER_CLEAR_W,
                BLOCK_PICKER_SEARCH_H,
                listX,
                listY,
                listWidth,
                listHeight
        );
    }

    private List<Block> getFilteredBlocks(BlocksSetting setting) {
        List<Block> filtered = new ArrayList<>(setting.filter(blockSearchQuery));
        filtered.sort(Comparator
                .comparing((Block block) -> !setting.contains(block))
                .thenComparing(setting::getDisplayName, String.CASE_INSENSITIVE_ORDER));
        return filtered;
    }

    private String buildBlocksPreviewText(BlocksSetting setting) {
        Block firstBlock = setting.getSelectedBlocks().stream().findFirst().orElse(null);
        if (firstBlock == null) {
            return "Choose";
        }

        String name = setting.getDisplayName(firstBlock);
        int extra = setting.size() - 1;
        return extra > 0 ? name + " +" + extra : name;
    }

    private ItemStack getPreviewBlockStack(BlocksSetting setting) {
        Block firstBlock = setting.getSelectedBlocks().stream().findFirst().orElse(null);
        if (firstBlock == null) {
            return ItemStack.EMPTY;
        }

        ItemStack stack = new ItemStack(firstBlock);
        return stack.isEmpty() ? ItemStack.EMPTY : stack;
    }

    private String trimWithEllipsis(String text, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return "";
        }
        if (this.textRenderer.getWidth(text) <= maxWidth) {
            return text;
        }

        String ellipsis = "...";
        int ellipsisWidth = this.textRenderer.getWidth(ellipsis);
        if (ellipsisWidth >= maxWidth) {
            return this.textRenderer.trimToWidth(text, maxWidth);
        }

        return this.textRenderer.trimToWidth(text, maxWidth - ellipsisWidth) + ellipsis;
    }

    private String formatStringSettingValue(Module module, Setting<?> setting, String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        if (isCoordSnapperWebhookSetting(module, setting)) {
            return abbreviateSensitiveSuffix(value, 10);
        }

        return value;
    }

    private boolean isCoordSnapperWebhookSetting(Module module, Setting<?> setting) {
        return module != null
                && setting != null
                && "CoordSnapper".equalsIgnoreCase(module.getName())
                && setting.matchesName("Webhook");
    }

    private String abbreviateSensitiveSuffix(String value, int visibleChars) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }

        int lastSlash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        String tailSource = lastSlash >= 0 && lastSlash < trimmed.length() - 1
                ? trimmed.substring(lastSlash + 1)
                : trimmed;
        if (tailSource.length() <= visibleChars) {
            return "..." + tailSource;
        }
        return "..." + tailSource.substring(tailSource.length() - visibleChars);
    }

    private void drawScaledText(DrawContext context, String text, float x, float y, float scale, int color) {
        if (text == null || text.isEmpty()) {
            return;
        }

        var matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate(x, y);
        matrices.scale(scale, scale);
        context.drawText(this.textRenderer, text, 0, 0, color, false);
        matrices.popMatrix();
    }

    private void drawInputTextClipped(
            DrawContext context,
            float containerX,
            float containerY,
            float containerWidth,
            float containerHeight,
            String text,
            int textX,
            int textY,
            int color
    ) {
        if (text == null) {
            text = "";
        }

        // Global rule: no text input may render outside its parent container. Enforced here, not at call sites.
        float w = Math.max(0f, containerWidth);
        float h = Math.max(0f, containerHeight);
        RenderUtil.setScissor(containerX * uiScale, containerY * uiScale, w * uiScale, h * uiScale, false);
        context.drawText(this.textRenderer, text, textX, textY, color, false);
        RenderUtil.clearScissor(false);
    }

    private int getBlockPickerExtraHeight(BlocksSetting setting) {
        return Math.round(buildBlockPickerLayout(0f, PANEL_W - 8, 0f, setting).height);
    }

    private int clampBlockPickerScroll(int itemCount, int value) {
        int maxScroll = Math.max(0, itemCount - BLOCK_PICKER_VISIBLE_ROWS);
        return Math.max(0, Math.min(maxScroll, value));
    }

    // ==== MobsSetting picker (mirrors block picker) ====

    private BlockPickerLayout buildMobPickerLayout(float panelX, float panelWidth, float pickerY, MobsSetting setting) {
        int filteredCount = getFilteredMobs(setting).size();
        int visibleRows = Math.min(BLOCK_PICKER_VISIBLE_ROWS, Math.max(1, filteredCount));
        float layoutWidth = panelWidth;
        float layoutX = panelX;
        float searchX = layoutX + 6f;
        float searchY = pickerY + BLOCK_PICKER_GAP;
        float clearX = layoutX + layoutWidth - BLOCK_PICKER_CLEAR_W - 6f;
        float searchWidth = Math.max(24f, clearX - searchX - 4f);
        float listX = layoutX + 6f;
        float listY = searchY + BLOCK_PICKER_SEARCH_H + BLOCK_PICKER_GAP;
        float listWidth = layoutWidth - 12f;
        float listHeight = visibleRows * BLOCK_PICKER_ROW_H;
        float height = BLOCK_PICKER_GAP + BLOCK_PICKER_SEARCH_H + BLOCK_PICKER_GAP + listHeight + BLOCK_PICKER_BOTTOM_PAD;
        return new BlockPickerLayout(layoutX, pickerY, layoutWidth, height,
                searchX, searchY, searchWidth, BLOCK_PICKER_SEARCH_H,
                clearX, searchY, BLOCK_PICKER_CLEAR_W, BLOCK_PICKER_SEARCH_H,
                listX, listY, listWidth, listHeight);
    }

    private List<EntityType<?>> getFilteredMobs(MobsSetting setting) {
        List<EntityType<?>> filtered = new ArrayList<>(setting.filter(mobSearchQuery));
        filtered.sort(Comparator
                .comparing((EntityType<?> t) -> !setting.contains(t))
                .thenComparing(setting::getDisplayName, String.CASE_INSENSITIVE_ORDER));
        return filtered;
    }

    private int getMobPickerExtraHeight(MobsSetting setting) {
        return Math.round(buildMobPickerLayout(0f, PANEL_W - 8, 0f, setting).height);
    }

    private int clampMobPickerScroll(int itemCount, int value) {
        int maxScroll = Math.max(0, itemCount - BLOCK_PICKER_VISIBLE_ROWS);
        return Math.max(0, Math.min(maxScroll, value));
    }

    private String buildMobsPreviewText(MobsSetting setting) {
        EntityType<?> first = setting.getSelectedMobs().stream().findFirst().orElse(null);
        if (first == null) return "Choose";
        String name = setting.getDisplayName(first);
        int extra = setting.size() - 1;
        return extra > 0 ? name + " +" + extra : name;
    }

    private ItemStack getPreviewMobStack(MobsSetting setting) {
        EntityType<?> first = setting.getSelectedMobs().stream().findFirst().orElse(null);
        if (first == null) return ItemStack.EMPTY;
        return getMobStack(first);
    }

    private ItemStack getMobStack(EntityType<?> type) {
        try {
            SpawnEggItem egg = SpawnEggItem.forEntity(type);
            if (egg != null) return new ItemStack(egg);
        } catch (Throwable ignored) {}
        return new ItemStack(Items.EGG);
    }

    private void drawMobsSettingSummary(DrawContext context, MobsSetting setting, float panelX, float panelWidth, float rowY, int rowHeight, float revealAlpha) {
        String arrow = expandedMobsSetting == setting ? "v" : ">";
        int arrowWidth = this.textRenderer.getWidth(arrow);
        int arrowX = Math.round(panelX + panelWidth - 6 - arrowWidth);
        int previewMaxWidth = Math.max(30, arrowX - (Math.round(panelX) + PANEL_PAD + this.textRenderer.getWidth(setting.getName()) + 14));
        int textColor = multiplyAlpha(expandedMobsSetting == setting || setting.size() > 0 ? COLOR_TEXT : COLOR_TEXT_MUTED, revealAlpha);
        String previewText = setting.size() == 0 ? "Choose" : buildMobsPreviewText(setting);
        String previewLabel = trimWithEllipsis(previewText, Math.round((previewMaxWidth - 18) / BLOCK_PICKER_TEXT_SCALE));
        int previewWidth = Math.max(34, Math.min(previewMaxWidth, this.textRenderer.getWidth(previewLabel) + 22));
        int previewX = arrowX - previewWidth - 6;
        int previewColor = multiplyAlpha(setting.size() > 0 ? COLOR_ACCENT_DIM : COLOR_KEY_BG, revealAlpha);
        ItemStack previewStack = getPreviewMobStack(setting);

        context.drawText(this.textRenderer, setting.getName(), Math.round(panelX) + PANEL_PAD, Math.round(rowY) + 4, textColor, false);
        RenderUtil.drawRoundedRect(context, previewX, rowY + 2, previewWidth, 12, 5.0f, previewColor, false);
        RenderUtil.drawOutline(context, previewX, rowY + 2, previewWidth, 12, 5.0f, 1.0f, multiplyAlpha(COLOR_ROW_OUTLINE, revealAlpha), false);
        if (!previewStack.isEmpty()) {
            context.drawItem(previewStack, previewX + 2, Math.round(rowY) + 1);
        }
        int previewTextX = previewX + (previewStack.isEmpty() ? 6 : 16);
        drawScaledText(context, previewLabel, previewTextX, rowY + 4f, BLOCK_PICKER_TEXT_SCALE, multiplyAlpha(COLOR_TEXT, revealAlpha));
        context.drawText(this.textRenderer, arrow, arrowX, Math.round(rowY) + 4, multiplyAlpha(COLOR_TEXT_MUTED, revealAlpha), false);
    }

    private void drawMobsPicker(DrawContext context, MobsSetting setting, float panelX, float panelWidth, float pickerY, int mouseX, int mouseY) {
        BlockPickerLayout layout = buildMobPickerLayout(panelX, panelWidth, pickerY, setting);
        List<EntityType<?>> filtered = getFilteredMobs(setting);
        mobPickerScroll = clampMobPickerScroll(filtered.size(), mobPickerScroll);

        RenderUtil.drawRoundedRect(context, layout.x, layout.y, layout.width, layout.height, 6.0f, COLOR_KEY_BG, false);
        RenderUtil.drawOutline(context, layout.x, layout.y, layout.width, layout.height, 6.0f, 1.0f, COLOR_ROW_OUTLINE, false);

        int searchOutline = mobSearchActive && expandedMobsSetting == setting ? COLOR_ACCENT : COLOR_SEARCH_OUTLINE;
        RenderUtil.drawRoundedRect(context, layout.searchX, layout.searchY, layout.searchWidth, layout.searchHeight, 5.0f, COLOR_PANEL_BG, false);
        RenderUtil.drawOutline(context, layout.searchX, layout.searchY, layout.searchWidth, layout.searchHeight, 5.0f, 1.0f, searchOutline, false);
        RenderUtil.drawRoundedRect(context, layout.clearX, layout.clearY, layout.clearWidth, layout.clearHeight, 5.0f, COLOR_ROW_BG, false);
        RenderUtil.drawOutline(context, layout.clearX, layout.clearY, layout.clearWidth, layout.clearHeight, 5.0f, 1.0f, COLOR_ROW_OUTLINE, false);

        String searchText = mobSearchQuery.isEmpty() ? "Search mobs..." : mobSearchQuery;
        if (mobSearchActive && expandedMobsSetting == setting && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            searchText += "_";
        }
        int searchColor = mobSearchQuery.isEmpty() && !mobSearchActive ? COLOR_TEXT_MUTED : COLOR_TEXT;
        drawInputTextClipped(context, layout.searchX, layout.searchY,
                Math.max(0f, layout.searchWidth), Math.max(0f, layout.searchHeight),
                searchText, Math.round(layout.searchX) + 6, Math.round(layout.searchY) + 4, searchColor);
        context.drawText(this.textRenderer, "Clear", Math.round(layout.clearX) + 4, Math.round(layout.clearY) + 4, COLOR_TEXT_MUTED, false);

        if (filtered.isEmpty()) {
            RenderUtil.drawRoundedRect(context, layout.listX, layout.listY, layout.listWidth, BLOCK_PICKER_ROW_H - 2, 5.0f, COLOR_ROW_BG, false);
            RenderUtil.drawOutline(context, layout.listX, layout.listY, layout.listWidth, BLOCK_PICKER_ROW_H - 2, 5.0f, 1.0f, COLOR_ROW_OUTLINE, false);
            context.drawText(this.textRenderer, "No mobs found", Math.round(layout.listX) + 6, Math.round(layout.listY) + 4, COLOR_TEXT_MUTED, false);
            return;
        }

        int visibleRows = Math.min(BLOCK_PICKER_VISIBLE_ROWS, filtered.size());
        boolean showScrollbar = filtered.size() > visibleRows;
        float rowWidth = layout.listWidth;

        for (int row = 0; row < visibleRows; row++) {
            int index = mobPickerScroll + row;
            if (index >= filtered.size()) break;
            EntityType<?> mob = filtered.get(index);
            float rowY = layout.listY + (row * BLOCK_PICKER_ROW_H);
            boolean hovered = mouseX >= layout.listX && mouseX <= layout.listX + layout.listWidth
                    && mouseY >= rowY && mouseY <= rowY + BLOCK_PICKER_ROW_H - 2;
            boolean selected = setting.contains(mob);
            int rowColor = selected ? COLOR_ROW_ACTIVE : (hovered ? COLOR_ROW_HOVER : COLOR_ROW_BG);
            int textColor = selected ? COLOR_ACCENT : COLOR_TEXT;

            RenderUtil.drawRoundedRect(context, layout.listX, rowY, rowWidth, BLOCK_PICKER_ROW_H - 2, 5.0f, rowColor, false);
            RenderUtil.drawOutline(context, layout.listX, rowY, rowWidth, BLOCK_PICKER_ROW_H - 2, 5.0f, 1.0f, COLOR_ROW_OUTLINE, false);

            ItemStack stack = getMobStack(mob);
            int textX = Math.round(layout.listX) + 5;
            if (!stack.isEmpty()) {
                context.drawItem(stack, Math.round(layout.listX) + 2, Math.round(rowY) + 1);
                textX += 16;
            }

            float indicatorX = layout.listX + rowWidth - 10;
            int textWidth = Math.max(20, Math.round(indicatorX) - textX - 4);
            String displayName = trimWithEllipsis(setting.getDisplayName(mob), Math.round(textWidth / BLOCK_PICKER_TEXT_SCALE));
            drawScaledText(context, displayName, textX, rowY + 4f, BLOCK_PICKER_TEXT_SCALE, textColor);

            int indicatorColor = selected ? COLOR_ACCENT : COLOR_SEARCH_OUTLINE;
            RenderUtil.drawRoundedRect(context, indicatorX, rowY + 5, BLOCK_PICKER_INDICATOR_SIZE, BLOCK_PICKER_INDICATOR_SIZE, 2.5f, selected ? COLOR_ACCENT : COLOR_KEY_BG, false);
            RenderUtil.drawOutline(context, indicatorX, rowY + 5, BLOCK_PICKER_INDICATOR_SIZE, BLOCK_PICKER_INDICATOR_SIZE, 2.5f, 1.0f, indicatorColor, false);
        }

        if (showScrollbar) {
            int maxScroll = Math.max(1, filtered.size() - visibleRows);
            float trackX = layout.listX + layout.listWidth - BLOCK_PICKER_SCROLLBAR_W;
            float trackY = layout.listY + 1f;
            float trackHeight = layout.listHeight - 2f;
            float thumbHeight = Math.max(12f, trackHeight * (visibleRows / (float) filtered.size()));
            float thumbOffset = (trackHeight - thumbHeight) * (mobPickerScroll / (float) maxScroll);

            RenderUtil.drawRoundedRect(context, trackX, trackY, BLOCK_PICKER_SCROLLBAR_W, trackHeight, 2.0f, COLOR_PANEL_BG, false);
            RenderUtil.drawRoundedRect(context, trackX, trackY + thumbOffset, BLOCK_PICKER_SCROLLBAR_W, thumbHeight, 2.0f, COLOR_ACCENT_DIM, false);
        }
    }

    private ColorPickerLayout buildColorPickerLayout(float panelX, float panelWidth, float rowY, int rowHeight) {
        float fieldX = panelX + 18f;
        float fieldY = rowY + rowHeight + COLOR_PICKER_GAP;
        float fieldWidth = panelWidth - 36f;
        float fieldHeight = COLOR_PICKER_FIELD_HEIGHT;
        float alphaY = fieldY + fieldHeight + COLOR_PICKER_GAP;
        float alphaHeight = COLOR_PICKER_ALPHA_HEIGHT;
        return new ColorPickerLayout(fieldX, fieldY, fieldWidth, fieldHeight, alphaY, alphaHeight);
    }

    private boolean updateActiveColorDrag(double mouseX, double mouseY) {
        int catX = 30;
        int catY = getContentTop() + verticalScroll;
        for (Category category : CACHED_CATEGORIES) {
            int modY = catY + PANEL_HEADER_H + PANEL_HEADER_SPACING;
            for (Module module : ModuleManager.INSTANCE.getModulesInCategory(category)) {
                if (!matchesQuery(module)) {
                    continue;
                }
                modY += ROW_STEP;
                if (module.isExpanded()) {
                    modY += ROW_STEP;
                    modY += ROW_STEP;
                    for (Setting<?> setting : module.getSettings()) {
                        if (setting == activeColorSetting && setting.getValue() instanceof Color) {
                            ColorPickerLayout layout = buildColorPickerLayout(catX + 4, PANEL_W - 8, modY, ROW_H);
                            if (colorDragMode == ColorDragMode.FIELD) {
                                updateColorFromField((Setting<Color>) setting, layout, mouseX, mouseY);
                            } else if (colorDragMode == ColorDragMode.ALPHA) {
                                updateColorFromAlpha((Setting<Color>) setting, layout, mouseX);
                            }
                            return true;
                        }
                        modY += ROW_STEP;
                        if (setting instanceof BlocksSetting blocksSetting && expandedBlocksSetting == blocksSetting) {
                            modY += getBlockPickerExtraHeight(blocksSetting);
                        }
                        if (setting instanceof MobsSetting mobsSetting && expandedMobsSetting == mobsSetting) {
                            modY += getMobPickerExtraHeight(mobsSetting);
                        }
                        if (setting.getValue() instanceof Color && expandedColorSetting == setting) {
                            modY += COLOR_PICKER_EXTRA_HEIGHT;
                        }
                    }
                }
            }
            catX += PANEL_W + PANEL_GAP;
        }
        return false;
    }

    private MobPickerContext getExpandedMobsPickerContext() {
        if (expandedMobsSetting == null) return null;
        int catX = 30;
        int catY = getContentTop() + verticalScroll;
        for (Category category : CACHED_CATEGORIES) {
            int modY = catY + PANEL_HEADER_H + PANEL_HEADER_SPACING;
            for (Module module : ModuleManager.INSTANCE.getModulesInCategory(category)) {
                if (!matchesQuery(module)) continue;
                modY += ROW_STEP;
                if (!module.isExpanded()) continue;
                modY += ROW_STEP;
                modY += ROW_STEP;
                for (Setting<?> setting : module.getSettings()) {
                    if (setting == expandedMobsSetting) {
                        return new MobPickerContext(expandedMobsSetting, buildMobPickerLayout(catX + 4, PANEL_W - 8, modY + ROW_STEP, expandedMobsSetting));
                    }
                    modY += ROW_STEP;
                    if (setting instanceof BlocksSetting blocksSetting && expandedBlocksSetting == blocksSetting) {
                        modY += getBlockPickerExtraHeight(blocksSetting);
                    }
                    if (setting instanceof MobsSetting mobsSetting && expandedMobsSetting == mobsSetting) {
                        modY += getMobPickerExtraHeight(mobsSetting);
                    }
                    if (setting.getValue() instanceof Color && expandedColorSetting == setting) {
                        modY += COLOR_PICKER_EXTRA_HEIGHT;
                    }
                }
            }
            catX += PANEL_W + PANEL_GAP;
        }
        return null;
    }

    private BlockPickerContext getExpandedBlocksPickerContext() {
        if (expandedBlocksSetting == null) {
            return null;
        }

        int catX = 30;
        int catY = getContentTop() + verticalScroll;
        for (Category category : CACHED_CATEGORIES) {
            int modY = catY + PANEL_HEADER_H + PANEL_HEADER_SPACING;
            for (Module module : ModuleManager.INSTANCE.getModulesInCategory(category)) {
                if (!matchesQuery(module)) {
                    continue;
                }

                modY += ROW_STEP;
                if (!module.isExpanded()) {
                    continue;
                }

                modY += ROW_STEP;
                modY += ROW_STEP;
                for (Setting<?> setting : module.getSettings()) {
                    if (setting == expandedBlocksSetting) {
                        return new BlockPickerContext(expandedBlocksSetting, buildBlockPickerLayout(catX + 4, PANEL_W - 8, modY + ROW_STEP, expandedBlocksSetting));
                    }

                    modY += ROW_STEP;
                    if (setting instanceof BlocksSetting blocksSetting && expandedBlocksSetting == blocksSetting) {
                        modY += getBlockPickerExtraHeight(blocksSetting);
                    }
                    if (setting instanceof MobsSetting mobsSetting && expandedMobsSetting == mobsSetting) {
                        modY += getMobPickerExtraHeight(mobsSetting);
                    }
                    if (setting.getValue() instanceof Color && expandedColorSetting == setting) {
                        modY += COLOR_PICKER_EXTRA_HEIGHT;
                    }
                }
            }
            catX += PANEL_W + PANEL_GAP;
        }

        return null;
    }

    private boolean handleSearchKeyInput(KeyInput input) {
        if (input.isEscape() || input.isEnter()) {
            return true;
        }
        return false;
    }

    private boolean handleBlockSearchKeyInput(KeyInput input) {
        if (input.getKeycode() == GLFW.GLFW_KEY_BACKSPACE) {
            blockSearchQuery = removeLastCodePoint(blockSearchQuery);
            blockPickerScroll = 0;
            return true;
        }
        if (input.isPaste()) {
            blockSearchQuery += getClipboardText();
            blockPickerScroll = 0;
            return true;
        }
        if (input.isEscape() || input.isEnter()) {
            blockSearchActive = false;
            return true;
        }
        return true;
    }

    private boolean handleMobSearchKeyInput(KeyInput input) {
        if (input.getKeycode() == GLFW.GLFW_KEY_BACKSPACE) {
            mobSearchQuery = removeLastCodePoint(mobSearchQuery);
            mobPickerScroll = 0;
            return true;
        }
        if (input.isPaste()) {
            mobSearchQuery += getClipboardText();
            mobPickerScroll = 0;
            return true;
        }
        if (input.isEscape() || input.isEnter()) {
            mobSearchActive = false;
            return true;
        }
        return true;
    }

    private boolean handleStringKeyInput(KeyInput input) {
        if (input.getKeycode() == GLFW.GLFW_KEY_BACKSPACE) {
            listeningString.setValue(removeLastCodePoint(listeningString.getValue()));
            return true;
        }
        if (input.isPaste()) {
            listeningString.setValue(listeningString.getValue() + getClipboardText());
            return true;
        }
        if (input.isEscape() || input.isEnter()) {
            listeningString = null;
            return true;
        }
        return true;
    }

    private int getContentTop() {
        return 16 + SEARCH_H + 14;
    }

    private int getTallestPanelHeight() {
        int maxHeight = 0;
        for (Category category : CACHED_CATEGORIES) {
            maxHeight = Math.max(maxHeight, getPanelHeight(category));
        }
        return maxHeight;
    }

    private int clampVerticalScroll(int value) {
        int availableHeight = Math.max(0, uiHeight() - getContentTop() - 16);
        int minScroll = Math.min(0, availableHeight - getTallestPanelHeight());
        return Math.max(minScroll, Math.min(0, value));
    }

    private String getClipboardText() {
        return sanitizeTextInput(MinecraftClient.getInstance().keyboard.getClipboard());
    }

    private String sanitizeTextInput(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder(input.length());
        input.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .forEach(builder::appendCodePoint);
        return builder.toString();
    }

    private String removeLastCodePoint(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        int newEnd = value.offsetByCodePoints(value.length(), -1);
        return value.substring(0, newEnd);
    }

    private void updateColorFromField(Setting<Color> setting, ColorPickerLayout layout, double mouseX, double mouseY) {
        float hue = clamp01((float) ((mouseX - layout.fieldX) / layout.fieldWidth));
        float brightness = 1f - clamp01((float) ((mouseY - layout.fieldY) / layout.fieldHeight));
        int rgb = Color.HSBtoRGB(hue, 1f, brightness);
        int alpha = setting.getValue().getAlpha();
        setting.setValue(new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, alpha));
    }

    private void updateColorFromAlpha(Setting<Color> setting, ColorPickerLayout layout, double mouseX) {
        float alpha = clamp01((float) ((mouseX - layout.fieldX) / layout.fieldWidth));
        Color current = setting.getValue();
        setting.setValue(new Color(current.getRed(), current.getGreen(), current.getBlue(), Math.round(alpha * 255f)));
    }

    private boolean pointInRect(double x, double y, float rx, float ry, float rw, float rh) {
        return x >= rx && x <= rx + rw && y >= ry && y <= ry + rh;
    }

    private boolean allowDecimalForModule(Module module) {
        if (module == null) return false;
        String n = module.getName() == null ? "" : module.getName().toLowerCase().replace(" ", "");
        return n.equals("swingspeed")
                || n.equals("freelook")
                || n.equals("fastplace")
                || n.equals("playeresp")
                || n.equals("storageesp")
                || n.equals("freecam")
                || n.equals("holeesp")
                || n.equals("jumpcircles")
                || n.equals("autototem")
                || n.equals("autoinvtotem")
                || n.equals("hitbox")
                || n.equals("anchormacro")
                || n.equals("autocrystal")
                || n.equals("doubleanchor")
                || n.equals("triggerbot")
                || n.equals("shieldbreaker")
                || n.equals("spotifyhud")
                || n.equals("xenon+");
    }

    private void updateNumericSetting(Module module, Setting<?> setting, double mouseX, int catX) {
        double progress = (mouseX - (catX + PANEL_PAD)) / (double) (PANEL_W - PANEL_PAD * 2);
        double clampedProgress = Math.max(0.0D, Math.min(1.0D, progress));
        boolean allowDecimal = allowDecimalForModule(module);

        if (setting.getValue() instanceof Float && setting.getMin() instanceof Float && setting.getMax() instanceof Float) {
            float min = (Float) setting.getMin();
            float max = (Float) setting.getMax();
            float v = (float) (min + ((max - min) * clampedProgress));
            if (!allowDecimal) v = Math.round(v);
            ((Setting<Float>) setting).setValue(v);
            return;
        }

        if (setting.getValue() instanceof Integer && setting.getMin() instanceof Integer && setting.getMax() instanceof Integer) {
            int min = (Integer) setting.getMin();
            int max = (Integer) setting.getMax();
            int value = (int) Math.round(min + ((max - min) * clampedProgress));
            ((Setting<Integer>) setting).setValue(Math.max(min, Math.min(max, value)));
            return;
        }

        if (setting.getValue() instanceof Double && setting.getMin() instanceof Double && setting.getMax() instanceof Double) {
            double min = (Double) setting.getMin();
            double max = (Double) setting.getMax();
            double v = min + ((max - min) * clampedProgress);
            if (!allowDecimal) v = Math.round(v);
            ((Setting<Double>) setting).setValue(v);
        }
    }

    private float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private int withAlpha(int color, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private int multiplyAlpha(int color, float alphaMul) {
        int a = (color >> 24) & 0xFF;
        int newA = Math.max(0, Math.min(255, Math.round(a * alphaMul)));
        return (color & 0x00FFFFFF) | (newA << 24);
    }

    private int multiplyAlpha(Color color, float alphaMul) {
        int newA = Math.max(0, Math.min(255, Math.round(color.getAlpha() * alphaMul)));
        return (newA << 24) | (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
    }

    private int lerpColor(int from, int to, float t) {
        float clamped = clamp01(t);
        int a1 = (from >> 24) & 0xFF;
        int r1 = (from >> 16) & 0xFF;
        int g1 = (from >> 8) & 0xFF;
        int b1 = from & 0xFF;
        int a2 = (to >> 24) & 0xFF;
        int r2 = (to >> 16) & 0xFF;
        int g2 = (to >> 8) & 0xFF;
        int b2 = to & 0xFF;
        int a = (int) (a1 + (a2 - a1) * clamped);
        int r = (int) (r1 + (r2 - r1) * clamped);
        int g = (int) (g1 + (g2 - g1) * clamped);
        int b = (int) (b1 + (b2 - b1) * clamped);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private float easeOutCubic(float t) {
        float clamped = clamp01(t);
        return 1f - (float) Math.pow(1f - clamped, 3);
    }

    private enum ColorDragMode {
        NONE,
        FIELD,
        ALPHA
    }

    private record MobPickerContext(MobsSetting setting, BlockPickerLayout layout) {}

    private record BlockPickerContext(BlocksSetting setting, BlockPickerLayout layout) {
    }

    private static final class BlockPickerLayout {
        private final float x;
        private final float y;
        private final float width;
        private final float height;
        private final float searchX;
        private final float searchY;
        private final float searchWidth;
        private final float searchHeight;
        private final float clearX;
        private final float clearY;
        private final float clearWidth;
        private final float clearHeight;
        private final float listX;
        private final float listY;
        private final float listWidth;
        private final float listHeight;

        private BlockPickerLayout(
                float x,
                float y,
                float width,
                float height,
                float searchX,
                float searchY,
                float searchWidth,
                float searchHeight,
                float clearX,
                float clearY,
                float clearWidth,
                float clearHeight,
                float listX,
                float listY,
                float listWidth,
                float listHeight
        ) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.searchX = searchX;
            this.searchY = searchY;
            this.searchWidth = searchWidth;
            this.searchHeight = searchHeight;
            this.clearX = clearX;
            this.clearY = clearY;
            this.clearWidth = clearWidth;
            this.clearHeight = clearHeight;
            this.listX = listX;
            this.listY = listY;
            this.listWidth = listWidth;
            this.listHeight = listHeight;
        }
    }

    private static final class ColorPickerLayout {
        private final float fieldX;
        private final float fieldY;
        private final float fieldWidth;
        private final float fieldHeight;
        private final float alphaY;
        private final float alphaHeight;

        private ColorPickerLayout(float fieldX, float fieldY, float fieldWidth, float fieldHeight, float alphaY, float alphaHeight) {
            this.fieldX = fieldX;
            this.fieldY = fieldY;
            this.fieldWidth = fieldWidth;
            this.fieldHeight = fieldHeight;
            this.alphaY = alphaY;
            this.alphaHeight = alphaHeight;
        }
    }
}

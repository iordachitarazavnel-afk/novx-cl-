package com.xenon.gui;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.module.ModuleManager;
import com.xenon.module.ActivatableModule;
import com.xenon.setting.Setting;
import com.xenon.setting.BooleanSetting;
import com.xenon.setting.NumberSetting;
import com.xenon.utils.renderer.RenderUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MenuScreen extends Screen {

    public static MenuScreen INSTANCE;

    // ── Layout ────────────────────────────────────────────────────────────────
    private static final int SIDEBAR_W        = 56;
    private static final int SIDEBAR_PAD      = 8;
    private static final int ICON_SIZE        = 32;
    private static final int ICON_GAP         = 6;
    private static final int AVATAR_SIZE      = 36;

    private static final int HEADER_H         = 48;
    private static final int HEADER_PAD_X     = 12;
    private static final int HEADER_GAP       = 8;

    private static final int MODULE_ROW_H     = 40;
    private static final int MODULE_ROW_GAP   = 4;
    private static final int MODULE_PAD_X     = 16;

    private static final int SETTING_ROW_H    = 32;
    private static final int SETTING_PAD_X    = 20;

    private static final int CONTENT_PAD_X    = 12;
    private static final int CONTENT_PAD_Y    = 8;

    private static final int BOX_RADIUS       = 12;
    private static final int ROW_RADIUS       = 8;
    private static final int BADGE_RADIUS     = 6;
    private static final int SIDEBAR_RADIUS   = 12;

    // ── Colors ────────────────────────────────────────────────────────────────
    private static final int C_BG             = 0xF0131620;
    private static final int C_SIDEBAR        = 0xFF0F1219;
    private static final int C_HEADER         = 0xFF161B25;
    private static final int C_ROW            = 0xFF161B25;
    private static final int C_ROW_HOVER      = 0xFF1C2230;
    private static final int C_ROW_ACTIVE     = 0xFF1A2B28;
    private static final int C_OUTLINE        = 0xFF1E2535;
    private static final int C_OUTLINE_ACCENT = 0xFF2A3550;
    private static final int C_TEXT           = 0xFFDDE3EC;
    private static final int C_TEXT_MUTED     = 0xFF6B7891;
    private static final int C_ACCENT         = 0xFF8B7CF8;   // purple
    private static final int C_ACCENT_DIM     = 0xFF6355CC;
    private static final int C_ICON_INACTIVE  = 0xFF404860;
    private static final int C_BADGE          = 0xFF1E2535;
    private static final int C_SEARCH_BG      = 0xFF161B25;

    // ── State ─────────────────────────────────────────────────────────────────
    private Category selectedCategory = Category.COMBAT;
    private String   searchQuery      = "";
    private boolean  searchFocused    = false;

    // Bind listening
    private Module           listeningBind           = null;
    private ActivatableModule listeningActivationBind = null;

    // Expanded modules (right-click)
    private final java.util.Set<String> expandedModules = new java.util.HashSet<>();

    // Animations: exponential smoothing
    private final Map<String, Float> anim  = new HashMap<>();
    private long lastNanos = 0L;
    private float dt = 1f / 60f;

    // Dragging the whole window
    private boolean dragging;
    private int dragOffsetX, dragOffsetY;
    private int boxX, boxY, boxW, boxH;

    // Scroll
    private int scrollY = 0;

    public MenuScreen() {
        super(Text.literal("Xenon Menu"));
        INSTANCE = this;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void tickDt() {
        long now = System.nanoTime();
        if (lastNanos != 0) dt = Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
    }

    private float a(String key, float target, float speed) {
        float cur = anim.getOrDefault(key, target);
        float next = cur + (target - cur) * (1f - (float) Math.exp(-speed * dt));
        anim.put(key, next);
        return next;
    }

    private static int lerpC(int a, int b, float t) {
        if (t <= 0) return a;
        if (t >= 1) return b;
        int aa=(a>>24)&0xFF, ar=(a>>16)&0xFF, ag=(a>>8)&0xFF, ab=a&0xFF;
        int ba=(b>>24)&0xFF, br=(b>>16)&0xFF, bg=(b>>8)&0xFF, bb=b&0xFF;
        return (((int)(aa+(ba-aa)*t))<<24)|(((int)(ar+(br-ar)*t))<<16)|
               (((int)(ag+(bg-ag)*t))<<8)|((int)(ab+(bb-ab)*t));
    }

    private static int mulAlpha(int c, float f) {
        int a = Math.round(((c>>24)&0xFF)*f);
        return (c&0x00FFFFFF)|(Math.max(0,Math.min(255,a))<<24);
    }

    private boolean isExpanded(Module m) {
        return expandedModules.contains(m.getName());
    }

    private void toggleExpanded(Module m) {
        if (!expandedModules.remove(m.getName())) expandedModules.add(m.getName());
    }

    private List<Module> visibleModules() {
        return ModuleManager.INSTANCE.getModulesInCategory(selectedCategory).stream()
                .filter(m -> searchQuery.isBlank() || m.getName().toLowerCase().contains(searchQuery.toLowerCase()))
                .toList();
    }

    // ── Init / Sizes ──────────────────────────────────────────────────────────

    @Override
    protected void init() {
        boxW = Math.min(1020, this.width - 40);
        boxH = Math.min(600, this.height - 40);
        boxX = (this.width  - boxW) / 2;
        boxY = (this.height - boxH) / 2;
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        tickDt();

        int mx = mouseX, my = mouseY;

        // ── outer box ────────────────────────────────────────────────────────
        RenderUtil.drawRoundedRect(ctx, boxX, boxY, boxW, boxH, BOX_RADIUS, C_BG, false);
        RenderUtil.drawOutline    (ctx, boxX, boxY, boxW, boxH, BOX_RADIUS, 1f, C_OUTLINE, false);

        // ── sidebar ───────────────────────────────────────────────────────────
        int sbX = boxX;
        int sbY = boxY;
        int sbH = boxH;
        RenderUtil.drawRoundedRect(ctx, sbX, sbY, SIDEBAR_W, sbH,
                SIDEBAR_RADIUS, SIDEBAR_RADIUS, 0, 0, false, C_SIDEBAR);
        RenderUtil.drawOutline(ctx, sbX, sbY, SIDEBAR_W, sbH,
                SIDEBAR_RADIUS, SIDEBAR_RADIUS, 0, 0, 1f, false, C_OUTLINE);

        // logo at top of sidebar
        int logoX = sbX + (SIDEBAR_W - ICON_SIZE) / 2;
        int logoY = sbY + SIDEBAR_PAD + 4;
        RenderUtil.drawRoundedRect(ctx, logoX, logoY, ICON_SIZE, ICON_SIZE, 10f, C_ACCENT, false);
        ctx.drawCenteredTextWithShadow(this.textRenderer, "X",
                logoX + ICON_SIZE / 2, logoY + (ICON_SIZE - 8) / 2, 0xFFFFFFFF);

        // category icons
        Category[] cats = Category.values();
        int iconStartY = logoY + ICON_SIZE + 14;
        for (int ci = 0; ci < cats.length; ci++) {
            Category cat = cats[ci];
            boolean sel = cat == selectedCategory;
            float selA = a("cat/" + cat.name(), sel ? 1f : 0f, 14f);

            int icX = sbX + (SIDEBAR_W - ICON_SIZE) / 2;
            int icY = iconStartY + ci * (ICON_SIZE + ICON_GAP);

            // selected highlight pill
            if (selA > 0.01f) {
                int pillColor = mulAlpha(C_ACCENT, selA * 0.18f);
                RenderUtil.drawRoundedRect(ctx, icX, icY, ICON_SIZE, ICON_SIZE, 8f, pillColor, false);
                RenderUtil.drawOutline(ctx, icX, icY, ICON_SIZE, ICON_SIZE, 8f, 1f,
                        mulAlpha(C_ACCENT, selA * 0.55f), false);
            }

            // draw item icon using MC item renderer
            float iconItemScale = 1.4f;
            ctx.getMatrices().pushMatrix();
            ctx.getMatrices().translate(icX + (ICON_SIZE - 16 * iconItemScale) / 2f,
                                        icY + (ICON_SIZE - 16 * iconItemScale) / 2f);
            ctx.getMatrices().scale(iconItemScale, iconItemScale);
            ctx.drawItem(cat.getIcon(), 0, 0);
            ctx.getMatrices().popMatrix();

            // hover check for category click
            boolean catHov = mx >= icX && mx <= icX + ICON_SIZE && my >= icY && my <= icY + ICON_SIZE;
            if (catHov && !sel) {
                RenderUtil.drawRoundedRect(ctx, icX, icY, ICON_SIZE, ICON_SIZE, 8f,
                        mulAlpha(0xFFFFFFFF, 0.04f), false);
            }
        }

        // avatar at bottom of sidebar
        int avX = sbX + (SIDEBAR_W - AVATAR_SIZE) / 2;
        int avY = sbY + sbH - AVATAR_SIZE - SIDEBAR_PAD - 4;
        RenderUtil.drawRoundedRect(ctx, avX, avY, AVATAR_SIZE, AVATAR_SIZE, 10f, C_ICON_INACTIVE, false);
        RenderUtil.drawOutline    (ctx, avX, avY, AVATAR_SIZE, AVATAR_SIZE, 10f, 1f,
                mulAlpha(C_ACCENT, 0.6f), false);
        ctx.drawCenteredTextWithShadow(this.textRenderer, "?",
                avX + AVATAR_SIZE / 2, avY + (AVATAR_SIZE - 8) / 2, C_TEXT_MUTED);

        // ── content area ──────────────────────────────────────────────────────
        int contentX = boxX + SIDEBAR_W + CONTENT_PAD_X;
        int contentY = boxY + CONTENT_PAD_Y;
        int contentW = boxW - SIDEBAR_W - CONTENT_PAD_X * 2;

        // ── header ────────────────────────────────────────────────────────────
        // Breadcrumb pill: home icon + category name
        int breadW = 120;
        int breadX = contentX;
        int breadY = contentY;
        RenderUtil.drawRoundedRect(ctx, breadX, breadY, breadW, HEADER_H - 4, BOX_RADIUS, C_HEADER, false);
        RenderUtil.drawOutline    (ctx, breadX, breadY, breadW, HEADER_H - 4, BOX_RADIUS, 1f, C_OUTLINE, false);
        ctx.drawItem(selectedCategory.getIcon(), breadX + 10, breadY + (HEADER_H - 4 - 16) / 2);
        ctx.drawTextWithShadow(this.textRenderer,
                selectedCategory.getName(),
                breadX + 10 + 18, breadY + (HEADER_H - 4 - 8) / 2,
                C_TEXT);

        // stats pill: enabled / total
        List<Module> allInCat = ModuleManager.INSTANCE.getModulesInCategory(selectedCategory);
        long enabled = allInCat.stream().filter(Module::isEnabled).count();
        int total    = allInCat.size();
        String statsStr = "✔ " + enabled + "  ✔ " + total;
        int statsW = this.textRenderer.getWidth(statsStr) + 20;
        int statsX = breadX + breadW + HEADER_GAP;
        int statsY = breadY;
        RenderUtil.drawRoundedRect(ctx, statsX, statsY, statsW, HEADER_H - 4, BOX_RADIUS, C_HEADER, false);
        RenderUtil.drawOutline    (ctx, statsX, statsY, statsW, HEADER_H - 4, BOX_RADIUS, 1f, C_OUTLINE, false);
        ctx.drawTextWithShadow(this.textRenderer, statsStr,
                statsX + 10, statsY + (HEADER_H - 4 - 8) / 2, C_ACCENT);

        // theme button (moon icon)
        int themeX = statsX + statsW + HEADER_GAP;
        int themeY = breadY;
        int themeS = HEADER_H - 4;
        RenderUtil.drawRoundedRect(ctx, themeX, themeY, themeS, themeS, BOX_RADIUS, C_HEADER, false);
        RenderUtil.drawOutline    (ctx, themeX, themeY, themeS, themeS, BOX_RADIUS, 1f, C_OUTLINE, false);
        ctx.drawCenteredTextWithShadow(this.textRenderer, "☽",
                themeX + themeS / 2, themeY + (themeS - 8) / 2, C_TEXT_MUTED);

        // layout button
        int layoutX = themeX + themeS + HEADER_GAP;
        int layoutY = breadY;
        int layoutS = themeS;
        RenderUtil.drawRoundedRect(ctx, layoutX, layoutY, layoutS, layoutS, BOX_RADIUS, C_HEADER, false);
        RenderUtil.drawOutline    (ctx, layoutX, layoutY, layoutS, layoutS, BOX_RADIUS, 1f, C_OUTLINE, false);
        ctx.drawCenteredTextWithShadow(this.textRenderer, "≡",
                layoutX + layoutS / 2, layoutY + (layoutS - 8) / 2, C_TEXT_MUTED);

        // search bar (right side)
        int searchW = 180;
        int searchX = contentX + contentW - searchW;
        int searchY = breadY;
        int searchH = HEADER_H - 4;
        RenderUtil.drawRoundedRect(ctx, searchX, searchY, searchW, searchH, BOX_RADIUS, C_SEARCH_BG, false);
        RenderUtil.drawOutline    (ctx, searchX, searchY, searchW, searchH, BOX_RADIUS, 1f,
                searchFocused ? C_ACCENT : C_OUTLINE, false);
        String searchDisplay = searchQuery.isEmpty()
                ? (searchFocused ? "_" : "Search")
                : searchQuery + (searchFocused && (System.currentTimeMillis()/500L)%2==0 ? "_" : "");
        int searchTextColor = searchQuery.isEmpty() && !searchFocused ? C_TEXT_MUTED : C_TEXT;
        // scissor search text
        RenderUtil.setScissor(searchX + 8, searchY, searchW - 16, searchH, false);
        ctx.drawTextWithShadow(this.textRenderer, searchDisplay, searchX + 10, searchY + (searchH - 8) / 2, searchTextColor);
        RenderUtil.clearScissor(false);

        // ── module list ───────────────────────────────────────────────────────
        int listX = contentX;
        int listY = contentY + HEADER_H + 4;
        int listW = contentW;
        int listH = boxH - CONTENT_PAD_Y * 2 - HEADER_H - 8;

        // scissor content area
        RenderUtil.setScissor(listX, listY, listW, listH, false);

        int curY = listY + scrollY;
        List<Module> mods = visibleModules();

        if (mods.isEmpty()) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, "No results",
                    listX + listW / 2, curY + 16, C_TEXT_MUTED);
        }

        for (Module mod : mods) {
            String mk = "mod/" + mod.getName();
            boolean hovered = mx >= listX && mx <= listX + listW - 4
                    && my >= curY && my <= curY + MODULE_ROW_H;
            float hA = a(mk + "/hov", hovered ? 1f : 0f, 14f);
            float eA = a(mk + "/ena", mod.isEnabled() ? 1f : 0f, 12f);

            int rowBg   = lerpC(C_ROW, C_ROW_HOVER, hA);
            rowBg       = lerpC(rowBg, C_ROW_ACTIVE, eA);
            int rowText = lerpC(C_TEXT_MUTED, C_ACCENT, eA);

            // row background
            RenderUtil.drawRoundedRect(ctx, listX, curY, listW - 4, MODULE_ROW_H, ROW_RADIUS, rowBg, false);
            RenderUtil.drawOutline    (ctx, listX, curY, listW - 4, MODULE_ROW_H, ROW_RADIUS, 1f,
                    eA > 0.5f ? mulAlpha(C_ACCENT, 0.3f) : C_OUTLINE, false);

            // left accent bar when enabled
            if (eA > 0.01f) {
                RenderUtil.drawRoundedRect(ctx, listX + 2, curY + 6, 3, MODULE_ROW_H - 12, 2f,
                        mulAlpha(C_ACCENT, eA), false);
            }

            // module icon (category item, small)
            ctx.getMatrices().pushMatrix();
            float isc = 0.8f;
            ctx.getMatrices().translate(listX + MODULE_PAD_X, curY + (MODULE_ROW_H - 16 * isc) / 2f);
            ctx.getMatrices().scale(isc, isc);
            ctx.drawItem(selectedCategory.getIcon(), 0, 0);
            ctx.getMatrices().popMatrix();

            // module name
            ctx.drawTextWithShadow(this.textRenderer, mod.getName(),
                    listX + MODULE_PAD_X + 20, curY + (MODULE_ROW_H - 8) / 2, rowText);

            // bind badge (right side)
            String bindLabel = getKeyName(mod.getBind());
            if (!bindLabel.equals("None")) {
                int bw = this.textRenderer.getWidth(bindLabel) + 12;
                int bx = listX + listW - 4 - bw - 8;
                int by = curY + (MODULE_ROW_H - 14) / 2;
                RenderUtil.drawRoundedRect(ctx, bx, by, bw, 14, BADGE_RADIUS, C_BADGE, false);
                RenderUtil.drawOutline    (ctx, bx, by, bw, 14, BADGE_RADIUS, 1f, C_OUTLINE, false);
                ctx.drawTextWithShadow(this.textRenderer, bindLabel, bx + 6, by + 3, C_TEXT_MUTED);
            } else {
                // "n/a" badge
                int bw = this.textRenderer.getWidth("n/a") + 12;
                int bx = listX + listW - 4 - bw - 8;
                int by = curY + (MODULE_ROW_H - 14) / 2;
                RenderUtil.drawRoundedRect(ctx, bx, by, bw, 14, BADGE_RADIUS, C_BADGE, false);
                RenderUtil.drawOutline    (ctx, bx, by, bw, 14, BADGE_RADIUS, 1f, C_OUTLINE, false);
                ctx.drawTextWithShadow(this.textRenderer, "n/a", bx + 6, by + 3, C_TEXT_MUTED);
            }

            curY += MODULE_ROW_H + MODULE_ROW_GAP;

            // ── expanded settings ─────────────────────────────────────────────
            if (isExpanded(mod)) {
                float expP = a(mk + "/exp", 1f, 18f);

                // bind row
                {
                    String bt = listeningBind == mod ? "Bind: ..." : "Bind: " + getKeyName(mod.getBind());
                    RenderUtil.drawRoundedRect(ctx, listX + 8, curY, listW - 20, SETTING_ROW_H, ROW_RADIUS,
                            mulAlpha(0xFF0D111A, expP), false);
                    RenderUtil.drawOutline    (ctx, listX + 8, curY, listW - 20, SETTING_ROW_H, ROW_RADIUS, 1f,
                            mulAlpha(C_OUTLINE, expP), false);
                    ctx.drawTextWithShadow(this.textRenderer, bt,
                            listX + SETTING_PAD_X, curY + (SETTING_ROW_H - 8) / 2,
                            mulAlpha(C_TEXT_MUTED, expP));
                    curY += SETTING_ROW_H + MODULE_ROW_GAP;
                }

                // activation key row (if applicable)
                if (mod instanceof ActivatableModule am) {
                    String at = listeningActivationBind == am ? "Activation: ..." : "Activation: " + getKeyName(am.getActivationKey());
                    RenderUtil.drawRoundedRect(ctx, listX + 8, curY, listW - 20, SETTING_ROW_H, ROW_RADIUS,
                            mulAlpha(0xFF0D111A, expP), false);
                    RenderUtil.drawOutline    (ctx, listX + 8, curY, listW - 20, SETTING_ROW_H, ROW_RADIUS, 1f,
                            mulAlpha(C_OUTLINE, expP), false);
                    ctx.drawTextWithShadow(this.textRenderer, at,
                            listX + SETTING_PAD_X, curY + (SETTING_ROW_H - 8) / 2,
                            mulAlpha(C_TEXT_MUTED, expP));
                    curY += SETTING_ROW_H + MODULE_ROW_GAP;
                }

                // settings
                for (Setting<?> s : mod.getSettings()) {
                    Object val = s.getValue();

                    RenderUtil.drawRoundedRect(ctx, listX + 8, curY, listW - 20, SETTING_ROW_H, ROW_RADIUS,
                            mulAlpha(0xFF0D111A, expP), false);
                    RenderUtil.drawOutline    (ctx, listX + 8, curY, listW - 20, SETTING_ROW_H, ROW_RADIUS, 1f,
                            mulAlpha(C_OUTLINE, expP), false);

                    if (val instanceof Boolean bv) {
                        // toggle
                        float tA = a("tog/" + System.identityHashCode(s), bv ? 1f : 0f, 16f);
                        int trackC = lerpC(0xFF2F3745, C_ACCENT_DIM, tA);
                        int knobC  = lerpC(0xFFE8EDF2, C_ACCENT, tA);
                        int togX   = listX + listW - 20 - 28;
                        int togY   = curY + (SETTING_ROW_H - 10) / 2;
                        RenderUtil.drawRoundedRect(ctx, togX, togY, 24, 10, 5f,
                                mulAlpha(trackC, expP), false);
                        int knobX = togX + 2 + Math.round(12f * tA);
                        RenderUtil.drawRoundedRect(ctx, knobX, togY + 2, 7, 7, 3f,
                                mulAlpha(knobC, expP), false);
                        ctx.drawTextWithShadow(this.textRenderer, s.getName(),
                                listX + SETTING_PAD_X, curY + (SETTING_ROW_H - 8) / 2,
                                mulAlpha(lerpC(C_TEXT_MUTED, C_ACCENT, tA), expP));

                    } else if (val instanceof Float || val instanceof Double || val instanceof Integer) {
                        // slider
                        float fval, fmin, fmax;
                        String display;
                        if (val instanceof Integer iv && s.getMin() instanceof Integer && s.getMax() instanceof Integer) {
                            fval=(float)(int)iv; fmin=(Integer)s.getMin(); fmax=(Integer)s.getMax();
                            display = Integer.toString(iv);
                        } else {
                            fval = val instanceof Float ? (Float)val : (float)(double)(Double)val;
                            fmin = s.getMin() instanceof Float ? (Float)s.getMin() : (float)(double)(Double)s.getMin();
                            fmax = s.getMax() instanceof Float ? (Float)s.getMax() : (float)(double)(Double)s.getMax();
                            display = String.format("%.1f", fval);
                        }
                        float pct = Math.max(0, Math.min(1, (fval - fmin) / (fmax - fmin)));
                        int barX = listX + SETTING_PAD_X;
                        int barY = curY + SETTING_ROW_H - 9;
                        int barW = listW - 20 - SETTING_PAD_X * 2 + 8;
                        int fillW = Math.round(barW * pct);
                        RenderUtil.drawRoundedRect(ctx, barX, barY, barW, 3, 1.5f,
                                mulAlpha(0xFF273141, expP), false);
                        if (fillW > 0)
                            RenderUtil.drawRoundedRect(ctx, barX, barY, fillW, 3, 1.5f,
                                    mulAlpha(C_ACCENT_DIM, expP), false);
                        // knob
                        float kx = barX + fillW - 3f;
                        float ky = barY + 1.5f - 3f;
                        RenderUtil.drawRoundedRect(ctx, kx, ky, 6, 6, 3f, mulAlpha(C_ACCENT, expP), false);
                        ctx.drawTextWithShadow(this.textRenderer, s.getName() + ": " + display,
                                listX + SETTING_PAD_X, curY + 4,
                                mulAlpha(C_TEXT, expP));

                    } else if (val instanceof String sv) {
                        ctx.drawTextWithShadow(this.textRenderer, s.getName() + ": " + sv,
                                listX + SETTING_PAD_X, curY + (SETTING_ROW_H - 8) / 2,
                                mulAlpha(C_TEXT, expP));
                    } else {
                        ctx.drawTextWithShadow(this.textRenderer, s.getName(),
                                listX + SETTING_PAD_X, curY + (SETTING_ROW_H - 8) / 2,
                                mulAlpha(C_TEXT_MUTED, expP));
                    }

                    curY += SETTING_ROW_H + MODULE_ROW_GAP;
                }
            } else {
                // snap animation to 0 when collapsed
                anim.put("mod/" + mod.getName() + "/exp", 0f);
            }
        }

        RenderUtil.clearScissor(false);

        // ── bind listener (overlay) ───────────────────────────────────────────
        if (listeningBind != null) {
            for (int i = 32; i <= 348; i++) {
                if (i == GLFW.GLFW_KEY_ESCAPE || i == GLFW.GLFW_KEY_BACKSPACE) continue;
                if (GLFW.glfwGetKey(MinecraftClient.getInstance().getWindow().getHandle(), i) == GLFW.GLFW_PRESS) {
                    listeningBind.setBind(i); listeningBind = null; break;
                }
            }
            if (listeningBind != null &&
                    GLFW.glfwGetKey(MinecraftClient.getInstance().getWindow().getHandle(), GLFW.GLFW_KEY_BACKSPACE) == GLFW.GLFW_PRESS) {
                listeningBind.setBind(0); listeningBind = null;
            }
            if (GLFW.glfwGetKey(MinecraftClient.getInstance().getWindow().getHandle(), GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS) {
                listeningBind = null;
            }
        }
        if (listeningActivationBind != null) {
            for (int i = 32; i <= 348; i++) {
                if (i == GLFW.GLFW_KEY_ESCAPE || i == GLFW.GLFW_KEY_BACKSPACE) continue;
                if (GLFW.glfwGetKey(MinecraftClient.getInstance().getWindow().getHandle(), i) == GLFW.GLFW_PRESS) {
                    listeningActivationBind.setActivationKey(i); listeningActivationBind = null; break;
                }
            }
            if (listeningActivationBind != null &&
                    GLFW.glfwGetKey(MinecraftClient.getInstance().getWindow().getHandle(), GLFW.GLFW_KEY_BACKSPACE) == GLFW.GLFW_PRESS) {
                listeningActivationBind.setActivationKey(0); listeningActivationBind = null;
            }
            if (GLFW.glfwGetKey(MinecraftClient.getInstance().getWindow().getHandle(), GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS) {
                listeningActivationBind = null;
            }
        }
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean bl) {
        double rawX = click.x(), rawY = click.y();
        int mx = (int) rawX, my = (int) rawY;
        int btn = click.button();

        // Header drag
        if (btn == 0 && mx >= boxX && mx <= boxX + boxW && my >= boxY && my <= boxY + 48) {
            dragging = true;
            dragOffsetX = mx - boxX;
            dragOffsetY = my - boxY;
            return true;
        }

        // Category sidebar click
        Category[] cats = Category.values();
        int iconStartY = boxY + SIDEBAR_PAD + 4 + ICON_SIZE + 14;
        for (int ci = 0; ci < cats.length; ci++) {
            int icX = boxX + (SIDEBAR_W - ICON_SIZE) / 2;
            int icY = iconStartY + ci * (ICON_SIZE + ICON_GAP);
            if (mx >= icX && mx <= icX + ICON_SIZE && my >= icY && my <= icY + ICON_SIZE) {
                selectedCategory = cats[ci];
                scrollY = 0;
                return true;
            }
        }

        // Search bar click
        int contentX = boxX + SIDEBAR_W + CONTENT_PAD_X;
        int contentY = boxY + CONTENT_PAD_Y;
        int contentW = boxW - SIDEBAR_W - CONTENT_PAD_X * 2;
        int searchW  = 180;
        int searchX  = contentX + contentW - searchW;
        int searchYY = contentY;
        int searchH  = HEADER_H - 4;
        if (mx >= searchX && mx <= searchX + searchW && my >= searchYY && my <= searchYY + searchH) {
            searchFocused = true;
            return true;
        }
        searchFocused = false;

        // Module list click
        int listX = contentX;
        int listY = contentY + HEADER_H + 4;
        int listW = contentW;
        int curY  = listY + scrollY;
        for (Module mod : visibleModules()) {
            // module row
            if (mx >= listX && mx <= listX + listW - 4 && my >= curY && my <= curY + MODULE_ROW_H) {
                if (btn == 0) mod.toggle();
                else if (btn == 1) toggleExpanded(mod);
                return true;
            }
            curY += MODULE_ROW_H + MODULE_ROW_GAP;

            if (isExpanded(mod)) {
                // bind row
                if (mx >= listX + 8 && mx <= listX + listW - 20 && my >= curY && my <= curY + SETTING_ROW_H) {
                    if (btn == 0) listeningBind = mod;
                    else if (btn == 1) { mod.setBind(0); listeningBind = null; }
                    return true;
                }
                curY += SETTING_ROW_H + MODULE_ROW_GAP;

                if (mod instanceof ActivatableModule am) {
                    if (mx >= listX + 8 && mx <= listX + listW - 20 && my >= curY && my <= curY + SETTING_ROW_H) {
                        if (btn == 0) listeningActivationBind = am;
                        else if (btn == 1) { am.setActivationKey(0); listeningActivationBind = null; }
                        return true;
                    }
                    curY += SETTING_ROW_H + MODULE_ROW_GAP;
                }

                for (Setting<?> s : mod.getSettings()) {
                    if (mx >= listX + 8 && mx <= listX + listW - 20 && my >= curY && my <= curY + SETTING_ROW_H) {
                        Object val = s.getValue();
                        if (val instanceof Boolean) {
                            @SuppressWarnings("unchecked") Setting<Boolean> bs = (Setting<Boolean>) s;
                            bs.setValue(!(Boolean) val);
                        } else if (val instanceof Float || val instanceof Double || val instanceof Integer) {
                            updateSlider(s, mx, listX + SETTING_PAD_X,
                                    listX + listW - 20 - SETTING_PAD_X + 8);
                        }
                        return true;
                    }
                    curY += SETTING_ROW_H + MODULE_ROW_GAP;
                }
            }
        }
        return super.mouseClicked(click, bl);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.gui.Click click, double deltaX, double deltaY) {
        int mx = (int) click.x(), my = (int) click.y();
        if (dragging && click.button() == 0) {
            boxX = mx - dragOffsetX;
            boxY = my - dragOffsetY;
            return true;
        }
        // Slider drag
        int contentX = boxX + SIDEBAR_W + CONTENT_PAD_X;
        int contentY = boxY + CONTENT_PAD_Y;
        int contentW = boxW - SIDEBAR_W - CONTENT_PAD_X * 2;
        int listX = contentX;
        int listY = contentY + HEADER_H + 4;
        int listW = contentW;
        int curY  = listY + scrollY;
        for (Module mod : visibleModules()) {
            curY += MODULE_ROW_H + MODULE_ROW_GAP;
            if (isExpanded(mod)) {
                curY += SETTING_ROW_H + MODULE_ROW_GAP;
                if (mod instanceof ActivatableModule) curY += SETTING_ROW_H + MODULE_ROW_GAP;
                for (Setting<?> s : mod.getSettings()) {
                    Object val = s.getValue();
                    if ((val instanceof Float || val instanceof Double || val instanceof Integer)
                            && mx >= listX + 8 && mx <= listX + listW - 20
                            && my >= curY && my <= curY + SETTING_ROW_H) {
                        updateSlider(s, mx, listX + SETTING_PAD_X, listX + listW - 20 - SETTING_PAD_X + 8);
                    }
                    curY += SETTING_ROW_H + MODULE_ROW_GAP;
                }
            }
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.gui.Click click) {
        dragging = false;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        scrollY = Math.min(0, scrollY + (int)(vAmount * 20));
        return true;
    }

    @Override
    public boolean charTyped(CharInput input) {
        String c = input.asString();
        if (c == null || c.isEmpty()) return super.charTyped(input);
        if (searchFocused) { searchQuery += c; return true; }
        return super.charTyped(input);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.getKeycode();
        if (searchFocused) {
            if (key == GLFW.GLFW_KEY_BACKSPACE && !searchQuery.isEmpty()) {
                searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
                return true;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER) {
                searchFocused = false;
                return true;
            }
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void renderBackground(DrawContext ctx, int mx, int my, float delta) { /* no dim */ }

    @Override
    public boolean shouldPause() { return false; }

    // ── Slider update ─────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private void updateSlider(Setting<?> s, int mx, int barX, int barRight) {
        float pct = Math.max(0, Math.min(1, (float)(mx - barX) / (barRight - barX)));
        Object val = s.getValue();
        if (val instanceof Float) {
            float mn = (Float) s.getMin(), mx2 = (Float) s.getMax();
            ((Setting<Float>)s).setValue(mn + (mx2 - mn) * pct);
        } else if (val instanceof Double) {
            double mn = (Double) s.getMin(), mx2 = (Double) s.getMax();
            ((Setting<Double>)s).setValue(mn + (mx2 - mn) * pct);
        } else if (val instanceof Integer) {
            int mn = (Integer) s.getMin(), mx2 = (Integer) s.getMax();
            ((Setting<Integer>)s).setValue(Math.round(mn + (mx2 - mn) * pct));
        }
    }

    // ── Key name ──────────────────────────────────────────────────────────────
    public static String getKeyName(int code) {
        if (code == 0) return "None";
        String n = GLFW.glfwGetKeyName(code, 0);
        if (n != null && !n.isBlank()) return n.toUpperCase();
        return switch (code) {
            case GLFW.GLFW_KEY_LEFT_SHIFT  -> "LShift";
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> "RShift";
            case GLFW.GLFW_KEY_LEFT_CONTROL-> "LCtrl";
            case GLFW.GLFW_KEY_RIGHT_CONTROL->"RCtrl";
            case GLFW.GLFW_KEY_LEFT_ALT    -> "LAlt";
            case GLFW.GLFW_KEY_RIGHT_ALT   -> "RAlt";
            case GLFW.GLFW_KEY_SPACE       -> "Space";
            case GLFW.GLFW_KEY_ENTER       -> "Enter";
            case GLFW.GLFW_KEY_ESCAPE      -> "Esc";
            case GLFW.GLFW_KEY_TAB         -> "Tab";
            case GLFW.GLFW_KEY_BACKSPACE   -> "Bksp";
            default -> "Key" + code;
        };
    }
}

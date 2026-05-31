package com.xenon.gui;

import com.xenon.module.ActivatableModule;
import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.module.ModuleManager;
import com.xenon.setting.BooleanSetting;
import com.xenon.setting.NumberSetting;
import com.xenon.setting.ModeSetting;
import com.xenon.setting.Setting;
import com.xenon.setting.BlocksSetting;
import com.xenon.utils.renderer.RenderUtil;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.*;

public class MenuScreen extends Screen {

    public static MenuScreen INSTANCE;

    // ── Layout ────────────────────────────────────────────────────────────────
    private static final int SB_W         = 56;   // sidebar width
    private static final int SB_PAD       = 8;
    private static final int ICON_S       = 32;   // category icon size
    private static final int ICON_GAP     = 6;
    private static final int AVATAR_S     = 36;
    private static final int LOGO_S       = 32;

    private static final int HDR_H        = 44;   // header height
    private static final int HDR_GAP      = 6;

    private static final int MOD_H        = 36;   // module row height
    private static final int MOD_GAP      = 3;
    private static final int MOD_PAD      = 14;

    private static final int SET_H        = 30;   // setting row height
    private static final int SET_GAP      = 3;
    private static final int SET_PAD      = 18;

    private static final int CP_X         = 10;   // content padding x
    private static final int CP_Y         = 8;    // content padding y

    private static final int R_BOX        = 12;
    private static final int R_ROW        = 7;
    private static final int R_BADGE      = 5;
    private static final int R_SB         = 12;

    // Block picker
    private static final int BP_ROW_H     = 18;
    private static final int BP_VISIBLE   = 5;
    private static final int BP_GAP       = 6;
    private static final int BP_SEARCH_H  = 16;
    private static final int BP_CLEAR_W   = 30;

    // ── Colors ────────────────────────────────────────────────────────────────
    private static final int C_BG         = 0xF2111620;
    private static final int C_SB         = 0xFF0D1019;
    private static final int C_HDR        = 0xFF141926;
    private static final int C_ROW        = 0xFF141926;
    private static final int C_ROW_HOV    = 0xFF1A2030;
    private static final int C_ROW_ACT    = 0xFF17261F;
    private static final int C_OUTLINE    = 0xFF1C2535;
    private static final int C_TEXT       = 0xFFDDE3EC;
    private static final int C_MUTED      = 0xFF6B7891;
    private static final int C_ACCENT     = 0xFF8B7CF8;
    private static final int C_ACCENT_DIM = 0xFF5B4ECC;
    private static final int C_ICON_OFF   = 0xFF353D55;
    private static final int C_BADGE      = 0xFF1A2030;
    private static final int C_SEARCH_BG  = 0xFF141926;
    private static final int C_BLOCK_BG   = 0xFF0D1019;

    // ── State ─────────────────────────────────────────────────────────────────
    private Category          selCat      = Category.COMBAT;
    private String            searchQ     = "";
    private boolean           searchFocus = false;

    private Module            listenBind  = null;
    private ActivatableModule listenAct   = null;

    private final Set<String> expanded    = new HashSet<>();
    private final Set<String> favorites   = new HashSet<>();

    // Block picker state
    private BlocksSetting     bpSetting   = null;
    private String            bpSearch    = "";
    private boolean           bpFocus     = false;
    private int               bpScroll    = 0;

    // Drag
    private boolean dragging;
    private int     dragOX, dragOY;
    private int     boxX, boxY, boxW, boxH;

    // Scroll
    private int     scrollY     = 0;
    private int     maxScrollY  = 0;

    // Animations
    private final Map<String, Float> anim = new HashMap<>();
    private long lastNanos = 0L;
    private float dt = 1f / 60f;

    // Slider drag
    private Setting<?> dragSlider      = null;
    private int        dragSliderBarX  = 0;
    private int        dragSliderBarW  = 0;

    // ── Helpers ───────────────────────────────────────────────────────────────

    public MenuScreen() {
        super(Text.literal("Xenon Menu"));
        INSTANCE = this;
    }

    private void tickDt() {
        long now = System.nanoTime();
        if (lastNanos != 0) dt = Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
    }

    private float a(String key, float target, float spd) {
        float cur = anim.getOrDefault(key, target);
        float next = cur + (target - cur) * (1f - (float) Math.exp(-spd * dt));
        anim.put(key, next);
        return next;
    }

    private static int lerp(int a, int b, float t) {
        if (t <= 0) return a; if (t >= 1) return b;
        int aa=(a>>24)&0xFF,ar=(a>>16)&0xFF,ag=(a>>8)&0xFF,ab=a&0xFF;
        int ba=(b>>24)&0xFF,br=(b>>16)&0xFF,bg=(b>>8)&0xFF,bb=b&0xFF;
        return (((int)(aa+(ba-aa)*t))<<24)|(((int)(ar+(br-ar)*t))<<16)|
               (((int)(ag+(bg-ag)*t))<<8)|((int)(ab+(bb-ab)*t));
    }

    private static int mul(int c, float f) {
        int a = Math.max(0, Math.min(255, Math.round(((c>>24)&0xFF)*f)));
        return (c&0x00FFFFFF)|(a<<24);
    }

    private boolean isExp(Module m)  { return expanded.contains(m.getName()); }
    private boolean isFav(Module m)  { return favorites.contains(m.getName()); }
    private void togExp(Module m)    { if (!expanded.remove(m.getName()))  expanded.add(m.getName()); }
    private void togFav(Module m)    { if (!favorites.remove(m.getName())) favorites.add(m.getName()); }

    private List<Module> visMods() {
        List<Module> base = ModuleManager.INSTANCE.getModulesInCategory(selCat);
        List<Module> favs = new ArrayList<>(), rest = new ArrayList<>();
        for (Module m : base) {
            if (!searchQ.isBlank() && !m.getName().toLowerCase().contains(searchQ.toLowerCase())) continue;
            if (isFav(m)) favs.add(m); else rest.add(m);
        }
        List<Module> out = new ArrayList<>();
        out.addAll(favs); out.addAll(rest);
        return out;
    }

    private int getBlockPickerExtraH(BlocksSetting bs) {
        List<Block> filtered = bs.filter(bpSearch);
        int rows = Math.min(BP_VISIBLE, Math.max(1, filtered.size()));
        return BP_GAP + BP_SEARCH_H + BP_GAP + rows * BP_ROW_H + BP_GAP;
    }

    private int getExpandedH(Module m) {
        int h = SET_H + SET_GAP; // bind
        if (m instanceof ActivatableModule) h += SET_H + SET_GAP;
        for (Setting<?> s : m.getSettings()) {
            h += SET_H + SET_GAP;
            if (s instanceof BlocksSetting bs && bpSetting == bs) h += getBlockPickerExtraH(bs);
        }
        return h;
    }

    private int totalContentH() {
        int h = 0;
        for (Module m : visMods()) {
            h += MOD_H + MOD_GAP;
            if (isExp(m)) h += getExpandedH(m);
        }
        return h;
    }

    // ── Init ─────────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        boxW = Math.min(1100, this.width  - 20);
        boxH = Math.min(660,  this.height - 20);
        boxX = (this.width  - boxW) / 2;
        boxY = (this.height - boxH) / 2;
        scrollY = 0;
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        tickDt();
        int mx = mouseX, my = mouseY;

        // clamp scroll
        int contentArea = boxH - CP_Y * 2 - HDR_H - 10;
        maxScrollY = Math.max(0, totalContentH() - contentArea);
        scrollY = Math.max(-maxScrollY, Math.min(0, scrollY));

        // ── outer box ────────────────────────────────────────────────────────
        RenderUtil.drawRoundedRect(ctx, boxX, boxY, boxW, boxH, R_BOX, C_BG, false);
        RenderUtil.drawOutline    (ctx, boxX, boxY, boxW, boxH, R_BOX, 1f, C_OUTLINE, false);

        // ── sidebar ───────────────────────────────────────────────────────────
        RenderUtil.drawRoundedRect(ctx, boxX, boxY, SB_W, boxH,
                R_SB, R_SB, 0, 0, false, C_SB);
        RenderUtil.drawOutline    (ctx, boxX, boxY, SB_W, boxH,
                R_SB, R_SB, 0, 0, 1f, false, C_OUTLINE);

        // Logo "X"
        int lgX = boxX + (SB_W - LOGO_S) / 2;
        int lgY = boxY + SB_PAD + 4;
        RenderUtil.drawRoundedRect(ctx, lgX, lgY, LOGO_S, LOGO_S, 9f, C_ACCENT, false);
        ctx.drawCenteredTextWithShadow(this.textRenderer, "X",
                lgX + LOGO_S / 2, lgY + (LOGO_S - 8) / 2, 0xFFFFFFFF);

        // Category icons
        Category[] cats = Category.values();
        int icStartY = lgY + LOGO_S + 14;
        for (int ci = 0; ci < cats.length; ci++) {
            Category cat = cats[ci];
            boolean sel = cat == selCat;
            float selA = a("cat/" + cat.name(), sel ? 1f : 0f, 14f);
            int icX = boxX + (SB_W - ICON_S) / 2;
            int icY = icStartY + ci * (ICON_S + ICON_GAP);

            if (selA > 0.01f) {
                RenderUtil.drawRoundedRect(ctx, icX, icY, ICON_S, ICON_S, 8f, mul(C_ACCENT, selA * 0.2f), false);
                RenderUtil.drawOutline    (ctx, icX, icY, ICON_S, ICON_S, 8f, 1f, mul(C_ACCENT, selA * 0.6f), false);
            }
            boolean catHov = mx >= icX && mx <= icX + ICON_S && my >= icY && my <= icY + ICON_S;
            if (catHov && !sel)
                RenderUtil.drawRoundedRect(ctx, icX, icY, ICON_S, ICON_S, 8f, mul(0xFFFFFFFF, 0.05f), false);

            float isc = 1.3f;
            ctx.getMatrices().pushMatrix();
            ctx.getMatrices().translate(icX + (ICON_S - 16 * isc) / 2f, icY + (ICON_S - 16 * isc) / 2f);
            ctx.getMatrices().scale(isc, isc);
            ctx.drawItem(cat.getIcon(), 0, 0);
            ctx.getMatrices().popMatrix();
        }

        // Avatar
        int avX = boxX + (SB_W - AVATAR_S) / 2;
        int avY = boxY + boxH - AVATAR_S - SB_PAD - 4;
        RenderUtil.drawRoundedRect(ctx, avX, avY, AVATAR_S, AVATAR_S, 10f, C_ICON_OFF, false);
        RenderUtil.drawOutline    (ctx, avX, avY, AVATAR_S, AVATAR_S, 10f, 1f, mul(C_ACCENT, 0.5f), false);
        ctx.drawCenteredTextWithShadow(this.textRenderer, "?",
                avX + AVATAR_S / 2, avY + (AVATAR_S - 8) / 2, C_MUTED);

        // ── content area ──────────────────────────────────────────────────────
        int cX = boxX + SB_W + CP_X;
        int cY = boxY + CP_Y;
        int cW = boxW - SB_W - CP_X * 2;

        // ── header ────────────────────────────────────────────────────────────
        int hY = cY;
        int hH = HDR_H - 2;

        // breadcrumb
        int brdW = this.textRenderer.getWidth(selCat.getName()) + 40;
        RenderUtil.drawRoundedRect(ctx, cX, hY, brdW, hH, R_BOX, C_HDR, false);
        RenderUtil.drawOutline    (ctx, cX, hY, brdW, hH, R_BOX, 1f, C_OUTLINE, false);
        float bsc = 0.85f;
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(cX + 8, hY + (hH - 16 * bsc) / 2f);
        ctx.getMatrices().scale(bsc, bsc);
        ctx.drawItem(selCat.getIcon(), 0, 0);
        ctx.getMatrices().popMatrix();
        ctx.drawTextWithShadow(this.textRenderer, selCat.getName(),
                cX + 8 + (int)(16 * bsc) + 6, hY + (hH - 8) / 2, C_TEXT);

        // stats
        List<Module> allCat = ModuleManager.INSTANCE.getModulesInCategory(selCat);
        int ena = (int) allCat.stream().filter(Module::isEnabled).count();
        int tot = allCat.size();
        String st = "✔ " + ena + "  ✔ " + tot;
        int stW = this.textRenderer.getWidth(st) + 18;
        int stX = cX + brdW + HDR_GAP;
        RenderUtil.drawRoundedRect(ctx, stX, hY, stW, hH, R_BOX, C_HDR, false);
        RenderUtil.drawOutline    (ctx, stX, hY, stW, hH, R_BOX, 1f, C_OUTLINE, false);
        ctx.drawTextWithShadow(this.textRenderer, st, stX + 9, hY + (hH - 8) / 2, C_ACCENT);

        // theme button (☽)
        int thX = stX + stW + HDR_GAP;
        RenderUtil.drawRoundedRect(ctx, thX, hY, hH, hH, R_BOX, C_HDR, false);
        RenderUtil.drawOutline    (ctx, thX, hY, hH, hH, R_BOX, 1f, C_OUTLINE, false);
        ctx.drawCenteredTextWithShadow(this.textRenderer, "☽", thX + hH / 2, hY + (hH - 8) / 2, C_MUTED);

        // layout button (≡)
        int lyX = thX + hH + HDR_GAP;
        RenderUtil.drawRoundedRect(ctx, lyX, hY, hH, hH, R_BOX, C_HDR, false);
        RenderUtil.drawOutline    (ctx, lyX, hY, hH, hH, R_BOX, 1f, C_OUTLINE, false);
        ctx.drawCenteredTextWithShadow(this.textRenderer, "≡", lyX + hH / 2, hY + (hH - 8) / 2, C_MUTED);

        // search bar
        int srchW = 200;
        int srchX = cX + cW - srchW;
        RenderUtil.drawRoundedRect(ctx, srchX, hY, srchW, hH, R_BOX, C_SEARCH_BG, false);
        RenderUtil.drawOutline    (ctx, srchX, hY, srchW, hH, R_BOX, 1f,
                searchFocus ? C_ACCENT : C_OUTLINE, false);
        String sd = searchQ.isEmpty() ? (searchFocus ? "_" : "Search")
                : searchQ + (searchFocus && (System.currentTimeMillis()/500L)%2==0 ? "_" : "");
        RenderUtil.setScissor(srchX + 8, hY, srchW - 16, hH, false);
        ctx.drawTextWithShadow(this.textRenderer, sd, srchX + 10, hY + (hH - 8) / 2,
                searchQ.isEmpty() && !searchFocus ? C_MUTED : C_TEXT);
        RenderUtil.clearScissor(false);

        // ── module list ───────────────────────────────────────────────────────
        int listX = cX;
        int listY = cY + HDR_H + 4;
        int listW = cW;
        int listH = boxH - CP_Y * 2 - HDR_H - 8;

        // scissor
        RenderUtil.setScissor(listX, listY, listW, listH, false);

        int curY = listY + scrollY;
        List<Module> mods = visMods();

        if (mods.isEmpty()) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, "No results",
                    listX + listW / 2, listY + 20, C_MUTED);
        }

        for (Module mod : mods) {
            String mk = "m/" + mod.getName();
            boolean hov = mx >= listX && mx <= listX + listW - 4 && my >= curY && my <= curY + MOD_H
                    && my >= listY && my <= listY + listH;
            float hA = a(mk + "/h", hov ? 1f : 0f, 14f);
            float eA = a(mk + "/e", mod.isEnabled() ? 1f : 0f, 12f);

            int rbg  = lerp(C_ROW, C_ROW_HOV, hA);
            rbg      = lerp(rbg,   C_ROW_ACT,  eA);
            int rtxt = lerp(C_MUTED, C_ACCENT, eA);

            RenderUtil.drawRoundedRect(ctx, listX, curY, listW - 4, MOD_H, R_ROW, rbg, false);
            RenderUtil.drawOutline    (ctx, listX, curY, listW - 4, MOD_H, R_ROW, 1f,
                    eA > 0.5f ? mul(C_ACCENT, 0.3f) : C_OUTLINE, false);

            // enabled bar
            if (eA > 0.01f)
                RenderUtil.drawRoundedRect(ctx, listX + 3, curY + 7, 3, MOD_H - 14, 2f,
                        mul(C_ACCENT, eA), false);

            // icon
            float isc2 = 0.75f;
            ctx.getMatrices().pushMatrix();
            ctx.getMatrices().translate(listX + MOD_PAD, curY + (MOD_H - 16 * isc2) / 2f);
            ctx.getMatrices().scale(isc2, isc2);
            ctx.drawItem(selCat.getIcon(), 0, 0);
            ctx.getMatrices().popMatrix();

            // name
            ctx.drawTextWithShadow(this.textRenderer, mod.getName(),
                    listX + MOD_PAD + 16, curY + (MOD_H - 8) / 2, rtxt);

            // favorite star
            boolean fav = isFav(mod);
            int starColor = fav ? 0xFFFFD700 : C_MUTED;
            ctx.drawTextWithShadow(this.textRenderer, fav ? "★" : "☆",
                    listX + listW - 4 - 60, curY + (MOD_H - 8) / 2, starColor);

            // bind badge
            String bind = getKeyName(mod.getBind());
            int bw = this.textRenderer.getWidth(bind) + 10;
            int bx = listX + listW - 4 - bw - 8;
            int by = curY + (MOD_H - 14) / 2;
            RenderUtil.drawRoundedRect(ctx, bx, by, bw, 14, R_BADGE, C_BADGE, false);
            RenderUtil.drawOutline    (ctx, bx, by, bw, 14, R_BADGE, 1f, C_OUTLINE, false);
            ctx.drawTextWithShadow(this.textRenderer, bind, bx + 5, by + 3, C_MUTED);

            curY += MOD_H + MOD_GAP;

            // ── expanded ─────────────────────────────────────────────────────
            if (isExp(mod)) {
                // bind row
                String bt = listenBind == mod ? "Bind: [press key]" : "Bind: " + getKeyName(mod.getBind());
                drawSettingRow(ctx, listX + 6, curY, listW - 16, SET_H, bt, C_MUTED);
                curY += SET_H + SET_GAP;

                if (mod instanceof ActivatableModule am) {
                    String at = listenAct == am ? "Activation: [press key]" : "Activation: " + getKeyName(am.getActivationKey());
                    drawSettingRow(ctx, listX + 6, curY, listW - 16, SET_H, at, C_MUTED);
                    curY += SET_H + SET_GAP;
                }

                for (Setting<?> s : mod.getSettings()) {
                    Object val = s.getValue();

                    if (val instanceof Boolean bv) {
                        float tA = a("t/" + System.identityHashCode(s), bv ? 1f : 0f, 16f);
                        drawToggleRow(ctx, listX + 6, curY, listW - 16, SET_H, s.getName(), tA);
                        curY += SET_H + SET_GAP;

                    } else if (val instanceof Float || val instanceof Double || val instanceof Integer) {
                        float fv, fmn, fmx;
                        String dv;
                        if (val instanceof Integer iv
                                && s.getMin() instanceof Integer smn
                                && s.getMax() instanceof Integer smx) {
                            fv = iv; fmn = smn; fmx = smx;
                            dv = Integer.toString(iv);
                        } else {
                            fv  = val instanceof Float ? (Float)val : (float)(double)(Double)val;
                            fmn = s.getMin() instanceof Float ? (Float)s.getMin() : (float)(double)(Double)s.getMin();
                            fmx = s.getMax() instanceof Float ? (Float)s.getMax() : (float)(double)(Double)s.getMax();
                            dv  = String.format("%.1f", fv);
                        }
                        drawSliderRow(ctx, listX + 6, curY, listW - 16, SET_H,
                                s.getName() + ": " + dv, fv, fmn, fmx);
                        curY += SET_H + SET_GAP;

                    } else if (s instanceof BlocksSetting bs) {
                        boolean bpOpen = bpSetting == bs;
                        String arrow = bpOpen ? "▲" : "▼";
                        int cnt = bs.getSelectedBlocks().size();
                        String label = s.getName() + ": " + (cnt == 0 ? "Choose" : cnt + " block(s)") + " " + arrow;
                        drawSettingRow(ctx, listX + 6, curY, listW - 16, SET_H, label,
                                bpOpen ? C_ACCENT : C_TEXT);
                        curY += SET_H + SET_GAP;

                        if (bpOpen) {
                            curY += drawBlockPicker(ctx, listX + 6, curY, listW - 16, mx, my, bs);
                        }

                    } else if (s instanceof ModeSetting ms) {
                        String label = s.getName() + ": " + ms.getValue();
                        drawSettingRow(ctx, listX + 6, curY, listW - 16, SET_H, label, C_ACCENT);
                        curY += SET_H + SET_GAP;

                    } else if (val instanceof String sv) {
                        drawSettingRow(ctx, listX + 6, curY, listW - 16, SET_H,
                                s.getName() + ": " + sv, C_TEXT);
                        curY += SET_H + SET_GAP;

                    } else {
                        drawSettingRow(ctx, listX + 6, curY, listW - 16, SET_H, s.getName(), C_MUTED);
                        curY += SET_H + SET_GAP;
                    }
                }
            }
        }

        RenderUtil.clearScissor(false);

        // scrollbar
        if (maxScrollY > 0) {
            float trackH = listH;
            float thumbH = Math.max(20f, trackH * trackH / (trackH + maxScrollY));
            float thumbY = listY + (-scrollY / (float) maxScrollY) * (trackH - thumbH);
            RenderUtil.drawRoundedRect(ctx, boxX + boxW - 5, listY, 3, listH, 1.5f, C_OUTLINE, false);
            RenderUtil.drawRoundedRect(ctx, boxX + boxW - 5, (int)thumbY, 3, (int)thumbH, 1.5f, C_ACCENT, false);
        }

        // ── bind listener ─────────────────────────────────────────────────────
        long win = MinecraftClient.getInstance().getWindow().getHandle();
        if (listenBind != null) {
            for (int i = 32; i <= 348; i++) {
                if (i == GLFW.GLFW_KEY_ESCAPE || i == GLFW.GLFW_KEY_BACKSPACE) continue;
                if (GLFW.glfwGetKey(win, i) == GLFW.GLFW_PRESS) { listenBind.setBind(i); listenBind = null; break; }
            }
            if (listenBind != null && GLFW.glfwGetKey(win, GLFW.GLFW_KEY_BACKSPACE) == GLFW.GLFW_PRESS) { listenBind.setBind(0); listenBind = null; }
            if (GLFW.glfwGetKey(win, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS) listenBind = null;
        }
        if (listenAct != null) {
            for (int i = 32; i <= 348; i++) {
                if (i == GLFW.GLFW_KEY_ESCAPE || i == GLFW.GLFW_KEY_BACKSPACE) continue;
                if (GLFW.glfwGetKey(win, i) == GLFW.GLFW_PRESS) { listenAct.setActivationKey(i); listenAct = null; break; }
            }
            if (listenAct != null && GLFW.glfwGetKey(win, GLFW.GLFW_KEY_BACKSPACE) == GLFW.GLFW_PRESS) { listenAct.setActivationKey(0); listenAct = null; }
            if (GLFW.glfwGetKey(win, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS) listenAct = null;
        }
    }

    // ── Draw helpers ─────────────────────────────────────────────────────────

    private void drawSettingRow(DrawContext ctx, int x, int y, int w, int h, String label, int textColor) {
        RenderUtil.drawRoundedRect(ctx, x, y, w, h, R_ROW, mul(C_ROW, 0.7f), false);
        RenderUtil.drawOutline    (ctx, x, y, w, h, R_ROW, 1f, C_OUTLINE, false);
        RenderUtil.setScissor(x + SET_PAD, y, w - SET_PAD * 2, h, false);
        ctx.drawTextWithShadow(this.textRenderer, label, x + SET_PAD, y + (h - 8) / 2, textColor);
        RenderUtil.clearScissor(false);
    }

    private void drawToggleRow(DrawContext ctx, int x, int y, int w, int h, String name, float t) {
        RenderUtil.drawRoundedRect(ctx, x, y, w, h, R_ROW, mul(C_ROW, 0.7f), false);
        RenderUtil.drawOutline    (ctx, x, y, w, h, R_ROW, 1f, C_OUTLINE, false);
        ctx.drawTextWithShadow(this.textRenderer, name, x + SET_PAD, y + (h - 8) / 2,
                lerp(C_MUTED, C_ACCENT, t));
        // toggle knob right side
        int tgX = x + w - 34;
        int tgY = y + (h - 10) / 2;
        RenderUtil.drawRoundedRect(ctx, tgX, tgY, 24, 10, 5f, lerp(0xFF2F3745, C_ACCENT_DIM, t), false);
        int kx = tgX + 2 + Math.round(12f * t);
        RenderUtil.drawRoundedRect(ctx, kx, tgY + 2, 7, 7, 3f, lerp(0xFFDDE3EC, C_ACCENT, t), false);
    }

    private void drawSliderRow(DrawContext ctx, int x, int y, int w, int h,
                               String label, float val, float mn, float mx) {
        RenderUtil.drawRoundedRect(ctx, x, y, w, h, R_ROW, mul(C_ROW, 0.7f), false);
        RenderUtil.drawOutline    (ctx, x, y, w, h, R_ROW, 1f, C_OUTLINE, false);
        ctx.drawTextWithShadow(this.textRenderer, label, x + SET_PAD, y + 4, C_TEXT);
        float pct    = Math.max(0, Math.min(1, (val - mn) / (mx - mn)));
        int   barX   = x + SET_PAD;
        int   barY   = y + h - 9;
        int   barW   = w - SET_PAD * 2;
        int   fillW  = Math.max(0, Math.round(barW * pct));
        RenderUtil.drawRoundedRect(ctx, barX, barY, barW, 3, 1.5f, mul(0xFF273141, 1f), false);
        if (fillW > 0)
            RenderUtil.drawRoundedRect(ctx, barX, barY, fillW, 3, 1.5f, C_ACCENT_DIM, false);
        float kx = barX + fillW - 3f;
        float ky = barY + 1.5f - 3f;
        RenderUtil.drawRoundedRect(ctx, kx, ky, 6, 6, 3f, C_ACCENT, false);
    }

    private int drawBlockPicker(DrawContext ctx, int x, int y, int w, int mx, int my, BlocksSetting bs) {
        List<Block> filtered = new ArrayList<>(bs.filter(bpSearch));
        filtered.sort(Comparator.comparing((Block b) -> !bs.contains(b))
                .thenComparing(bs::getDisplayName));
        bpScroll = Math.max(0, Math.min(bpScroll, Math.max(0, filtered.size() - BP_VISIBLE)));

        int rows   = Math.min(BP_VISIBLE, Math.max(1, filtered.size()));
        int totalH = BP_GAP + BP_SEARCH_H + BP_GAP + rows * BP_ROW_H + BP_GAP;

        RenderUtil.drawRoundedRect(ctx, x, y, w, totalH, R_ROW, C_BLOCK_BG, false);
        RenderUtil.drawOutline    (ctx, x, y, w, totalH, R_ROW, 1f, C_OUTLINE, false);

        // search
        int sY = y + BP_GAP;
        int sW = w - BP_CLEAR_W - 8 - 6;
        RenderUtil.drawRoundedRect(ctx, x + 4, sY, sW, BP_SEARCH_H, R_BADGE,
                bpFocus ? mul(C_SEARCH_BG, 1f) : C_SEARCH_BG, false);
        RenderUtil.drawOutline    (ctx, x + 4, sY, sW, BP_SEARCH_H, R_BADGE, 1f,
                bpFocus ? C_ACCENT : C_OUTLINE, false);
        String bpSd = bpSearch.isEmpty() ? (bpFocus ? "_" : "Search...")
                : bpSearch + (bpFocus && (System.currentTimeMillis()/500L)%2==0 ? "_" : "");
        RenderUtil.setScissor(x + 8, sY, sW - 8, BP_SEARCH_H, false);
        ctx.drawTextWithShadow(this.textRenderer, bpSd, x + 8, sY + 4,
                bpSearch.isEmpty() && !bpFocus ? C_MUTED : C_TEXT);
        RenderUtil.clearScissor(false);

        // clear button
        int clX = x + 4 + sW + 4;
        RenderUtil.drawRoundedRect(ctx, clX, sY, BP_CLEAR_W, BP_SEARCH_H, R_BADGE, mul(C_ROW, 0.8f), false);
        RenderUtil.drawOutline    (ctx, clX, sY, BP_CLEAR_W, BP_SEARCH_H, R_BADGE, 1f, C_OUTLINE, false);
        ctx.drawCenteredTextWithShadow(this.textRenderer, "Clear", clX + BP_CLEAR_W / 2, sY + 4, C_MUTED);

        // rows
        int lY = sY + BP_SEARCH_H + BP_GAP;
        RenderUtil.setScissor(x, lY, w, rows * BP_ROW_H, false);
        for (int i = 0; i < rows; i++) {
            int idx = bpScroll + i;
            if (idx >= filtered.size()) break;
            Block  blk  = filtered.get(idx);
            float  rY2  = lY + i * BP_ROW_H;
            boolean sel  = bs.contains(blk);
            boolean hov  = mx >= x && mx <= x + w && my >= rY2 && my <= rY2 + BP_ROW_H - 2;
            int    rbg2  = sel ? C_ROW_ACT : (hov ? C_ROW_HOV : mul(C_ROW, 0.6f));
            RenderUtil.drawRoundedRect(ctx, x + 2, (int)rY2, w - 4, BP_ROW_H - 2, R_BADGE, rbg2, false);
            ItemStack stk = new ItemStack(blk);
            if (!stk.isEmpty()) {
                ctx.getMatrices().pushMatrix();
                float bsc2 = 0.7f;
                ctx.getMatrices().translate(x + 4, rY2 + (BP_ROW_H - 2 - 16 * bsc2) / 2f);
                ctx.getMatrices().scale(bsc2, bsc2);
                ctx.drawItem(stk, 0, 0);
                ctx.getMatrices().popMatrix();
            }
            String dn = bs.getDisplayName(blk);
            if (dn.length() > 20) dn = dn.substring(0, 18) + "..";
            ctx.drawTextWithShadow(this.textRenderer, dn,
                    x + 4 + 14, (int)rY2 + (BP_ROW_H - 2 - 8) / 2, sel ? C_ACCENT : C_TEXT);
            // checkmark
            if (sel) ctx.drawTextWithShadow(this.textRenderer, "✔",
                    x + w - 14, (int)rY2 + (BP_ROW_H - 2 - 8) / 2, C_ACCENT);
        }
        RenderUtil.clearScissor(false);

        return totalH;
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean bl) {
        int mx = (int)click.x(), my = (int)click.y(), btn = click.button();

        // Window drag (header)
        if (btn == 0 && mx >= boxX && mx <= boxX + boxW && my >= boxY && my <= boxY + HDR_H + CP_Y + 4) {
            dragging = true; dragOX = mx - boxX; dragOY = my - boxY;
            return true;
        }

        // Category sidebar
        Category[] cats = Category.values();
        int icSY = boxY + SB_PAD + 4 + LOGO_S + 14;
        for (int ci = 0; ci < cats.length; ci++) {
            int icX = boxX + (SB_W - ICON_S) / 2;
            int icY = icSY + ci * (ICON_S + ICON_GAP);
            if (mx >= icX && mx <= icX + ICON_S && my >= icY && my <= icY + ICON_S) {
                selCat = cats[ci]; scrollY = 0; bpSetting = null; return true;
            }
        }

        // Search bar
        int cX = boxX + SB_W + CP_X;
        int cY2 = boxY + CP_Y;
        int cW = boxW - SB_W - CP_X * 2;
        int srchW = 200, srchX = cX + cW - srchW, hH = HDR_H - 2;
        if (mx >= srchX && mx <= srchX + srchW && my >= cY2 && my <= cY2 + hH) {
            searchFocus = true; bpFocus = false; return true;
        }
        searchFocus = false;

        // Module list
        int listX = cX, listY = cY2 + HDR_H + 4, listW = cW, listH = boxH - CP_Y * 2 - HDR_H - 8;
        if (mx < listX || mx > listX + listW || my < listY || my > listY + listH) return super.mouseClicked(click, bl);

        int curY = listY + scrollY;
        for (Module mod : visMods()) {
            // favorite star hit zone
            int starX = listX + listW - 4 - 60;
            if (btn == 0 && mx >= starX && mx <= starX + 14 && my >= curY && my <= curY + MOD_H) {
                togFav(mod); return true;
            }
            // module row
            if (mx >= listX && mx <= listX + listW - 4 && my >= curY && my <= curY + MOD_H) {
                if (btn == 0) mod.toggle();
                else if (btn == 1) { togExp(mod); if (!isExp(mod)) bpSetting = null; }
                return true;
            }
            curY += MOD_H + MOD_GAP;

            if (isExp(mod)) {
                // bind
                if (hit(mx, my, listX + 6, curY, listW - 16, SET_H)) {
                    if (btn == 0) { listenBind = mod; listenAct = null; }
                    else if (btn == 1) { mod.setBind(0); listenBind = null; }
                    return true;
                }
                curY += SET_H + SET_GAP;

                if (mod instanceof ActivatableModule am) {
                    if (hit(mx, my, listX + 6, curY, listW - 16, SET_H)) {
                        if (btn == 0) { listenAct = am; listenBind = null; }
                        else if (btn == 1) { am.setActivationKey(0); listenAct = null; }
                        return true;
                    }
                    curY += SET_H + SET_GAP;
                }

                for (Setting<?> s : mod.getSettings()) {
                    Object val = s.getValue();

                    if (s instanceof BlocksSetting bs) {
                        if (hit(mx, my, listX + 6, curY, listW - 16, SET_H)) {
                            if (btn == 0) {
                                if (bpSetting == bs) { bpSetting = null; bpSearch = ""; bpScroll = 0; }
                                else { bpSetting = bs; bpSearch = ""; bpScroll = 0; bpFocus = false; }
                            } else if (btn == 1) { bs.clear(); }
                            return true;
                        }
                        curY += SET_H + SET_GAP;

                        if (bpSetting == bs) {
                            int bpTotalH = getBlockPickerExtraH(bs);
                            List<Block> filtered = new ArrayList<>(bs.filter(bpSearch));
                            filtered.sort(Comparator.comparing((Block b) -> !bs.contains(b))
                                    .thenComparing(bs::getDisplayName));
                            // search area
                            int sY2 = curY + BP_GAP;
                            int sW2 = listW - 16 - BP_CLEAR_W - 8 - 6;
                            int clX2 = listX + 6 + 4 + sW2 + 4;
                            if (hit(mx, my, clX2, sY2, BP_CLEAR_W, BP_SEARCH_H)) {
                                bs.clear(); bpSearch = ""; bpScroll = 0; return true;
                            }
                            if (hit(mx, my, listX + 10, sY2, sW2, BP_SEARCH_H)) {
                                bpFocus = true; searchFocus = false; return true;
                            }
                            // block rows
                            int lY2 = sY2 + BP_SEARCH_H + BP_GAP;
                            int rows = Math.min(BP_VISIBLE, Math.max(1, filtered.size()));
                            for (int i = 0; i < rows; i++) {
                                int idx = bpScroll + i;
                                if (idx >= filtered.size()) break;
                                int rY2 = lY2 + i * BP_ROW_H;
                                if (hit(mx, my, listX + 6, rY2, listW - 16 - 4, BP_ROW_H - 2)) {
                                    bs.toggle(filtered.get(idx)); return true;
                                }
                            }
                            curY += bpTotalH;
                        }
                        continue;
                    }

                    if (hit(mx, my, listX + 6, curY, listW - 16, SET_H)) {
                        if (val instanceof Boolean) {
                            @SuppressWarnings("unchecked") Setting<Boolean> bs2 = (Setting<Boolean>) s;
                            if (btn == 0) bs2.setValue(!(Boolean) val);
                        } else if (val instanceof Float || val instanceof Double || val instanceof Integer) {
                            if (btn == 0) {
                                dragSlider = s;
                                dragSliderBarX = listX + 6 + SET_PAD;
                                dragSliderBarW = listW - 16 - SET_PAD * 2;
                                applySlider(s, mx);
                            }
                        } else if (s instanceof ModeSetting ms) {
                            if (btn == 0) ms.cycleNext();
                            else if (btn == 1) ms.cyclePrevious();
                        }
                        return true;
                    }
                    curY += SET_H + SET_GAP;
                }
            }
        }
        bpFocus = false;
        return super.mouseClicked(click, bl);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.gui.Click click, double dX, double dY) {
        int mx = (int) click.x(), my = (int) click.y();
        if (dragging && click.button() == 0) { boxX = mx - dragOX; boxY = my - dragOY; return true; }
        if (dragSlider != null && click.button() == 0) { applySlider(dragSlider, mx); return true; }
        return super.mouseDragged(click, dX, dY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.gui.Click click) {
        dragging = false; dragSlider = null; return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
        // scroll block picker if hovering it
        if (bpSetting != null) {
            bpScroll = Math.max(0, bpScroll - (int) Math.signum(vAmt));
            return true;
        }
        scrollY = Math.max(-maxScrollY, Math.min(0, scrollY + (int)(vAmt * 22)));
        return true;
    }

    @Override
    public boolean charTyped(CharInput input) {
        String c = input.asString();
        if (c == null || c.isEmpty()) return super.charTyped(input);
        if (bpFocus) { bpSearch += c; bpScroll = 0; return true; }
        if (searchFocus) { searchQ += c; return true; }
        return super.charTyped(input);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.getKeycode();
        if (bpFocus) {
            if (key == GLFW.GLFW_KEY_BACKSPACE && !bpSearch.isEmpty()) {
                bpSearch = bpSearch.substring(0, bpSearch.length() - 1); bpScroll = 0;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER) bpFocus = false;
            return true;
        }
        if (searchFocus) {
            if (key == GLFW.GLFW_KEY_BACKSPACE && !searchQ.isEmpty())
                searchQ = searchQ.substring(0, searchQ.length() - 1);
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER) searchFocus = false;
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void renderBackground(DrawContext ctx, int mx, int my, float d) {}

    @Override
    public boolean shouldPause() { return false; }

    // ── Slider ────────────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private void applySlider(Setting<?> s, int mx) {
        float pct = Math.max(0, Math.min(1, (float)(mx - dragSliderBarX) / dragSliderBarW));
        Object val = s.getValue();
        if (val instanceof Float) {
            float mn=(Float)s.getMin(), xm=(Float)s.getMax();
            ((Setting<Float>)s).setValue(mn+(xm-mn)*pct);
        } else if (val instanceof Double) {
            double mn=(Double)s.getMin(), xm=(Double)s.getMax();
            ((Setting<Double>)s).setValue(mn+(xm-mn)*pct);
        } else if (val instanceof Integer) {
            int mn=(Integer)s.getMin(), xm=(Integer)s.getMax();
            ((Setting<Integer>)s).setValue(Math.round(mn+(xm-mn)*pct));
        }
    }

    // ── Util ─────────────────────────────────────────────────────────────────
    private boolean hit(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private String getKeyName(int code) {
        if (code == 0) return "n/a";
        String n = GLFW.glfwGetKeyName(code, 0);
        if (n != null && !n.isBlank()) return n.toUpperCase();
        return switch (code) {
            case GLFW.GLFW_KEY_LEFT_SHIFT   -> "LShift";
            case GLFW.GLFW_KEY_RIGHT_SHIFT  -> "RShift";
            case GLFW.GLFW_KEY_LEFT_CONTROL -> "LCtrl";
            case GLFW.GLFW_KEY_RIGHT_CONTROL-> "RCtrl";
            case GLFW.GLFW_KEY_LEFT_ALT     -> "LAlt";
            case GLFW.GLFW_KEY_RIGHT_ALT    -> "RAlt";
            case GLFW.GLFW_KEY_SPACE        -> "Space";
            case GLFW.GLFW_KEY_ENTER        -> "Enter";
            case GLFW.GLFW_KEY_ESCAPE       -> "Esc";
            case GLFW.GLFW_KEY_TAB          -> "Tab";
            case GLFW.GLFW_KEY_BACKSPACE    -> "Bksp";
            default -> "Key" + code;
        };
    }

    // Public static helper used by Hud.java
    public static String getKeyDisplayNameStatic(int keyCode) {
        if (keyCode == 0) return "None";
        String n = GLFW.glfwGetKeyName(keyCode, 0);
        if (n != null && !n.isBlank()) return n.toUpperCase();
        return switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT_SHIFT   -> "LShift";
            case GLFW.GLFW_KEY_RIGHT_SHIFT  -> "RShift";
            case GLFW.GLFW_KEY_LEFT_CONTROL -> "LCtrl";
            case GLFW.GLFW_KEY_RIGHT_CONTROL-> "RCtrl";
            case GLFW.GLFW_KEY_LEFT_ALT     -> "LAlt";
            case GLFW.GLFW_KEY_RIGHT_ALT    -> "RAlt";
            case GLFW.GLFW_KEY_SPACE        -> "Space";
            case GLFW.GLFW_KEY_ENTER        -> "Enter";
            case GLFW.GLFW_KEY_ESCAPE       -> "Esc";
            case GLFW.GLFW_KEY_TAB          -> "Tab";
            case GLFW.GLFW_KEY_BACKSPACE    -> "Backspace";
            case GLFW.GLFW_KEY_CAPS_LOCK    -> "Caps";
            case GLFW.GLFW_KEY_UP           -> "Up";
            case GLFW.GLFW_KEY_DOWN         -> "Down";
            case GLFW.GLFW_KEY_LEFT         -> "Left";
            case GLFW.GLFW_KEY_RIGHT        -> "Right";
            default -> "Key " + keyCode;
        };
    }
}

package com.xenon.module.modules.client;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.Setting;
import com.xenon.utils.renderer.RenderUtil;
import com.xenon.gui.MenuScreen;
import com.xenon.module.modules.client.Hud;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;

public final class SpotifyHud extends Module {

    private static SpotifyHud INSTANCE;

    private final Setting<Float> scale = new Setting<>("Scale", 1.0f,  0.60f, 2.00f);

    private volatile String  title        = "";
    private volatile String  artist       = "";
    private volatile boolean isPlaying    = false;
    private volatile long    anchorPosMs  = 0;
    private volatile long    anchorTimeMs = 0;
    private volatile long    durMs        = 0;

    private ScheduledExecutorService scheduler;
    private ExecutorService          actionExec;
    private File pollScriptFile;
    private File ctrlScriptFile;
    private File artFile;

    private static final Identifier ART_ID = Identifier.of("xenon", "spotify_art");
    private NativeImageBackedTexture artTex          = null;
    private volatile long            artLastModified = 0L;
    private volatile boolean         hasArt          = false;

    private float fade        = 0f;
    private long  lastNanos   = 0L;
    private float scrollX     = 0f;
    private long  scrollNanos = 0L;

    private volatile boolean    hasPolledOnce = false;
    private final AtomicBoolean polling       = new AtomicBoolean(false);
    private final AtomicInteger fastPollCount = new AtomicInteger(0);

    // ── Layout constants ──────────────────────────────────────────────────────
    private static final int   BASE_W       = 230;   // slimmer card
    private static final int   BASE_H       = 76;    // more compact
    private static final int   BASE_ART     = 52;    // slightly smaller art
    private static final int   BASE_PAD_X   = 8;
    private static final float SCROLL_PX_S  = 28f;
    private static final int   SCROLL_GAP   = 18;

    private static final int ROW_H      = 8;
    private static final int GAP_TITLE  = 3;
    private static final int GAP_ART    = 5;
    private static final int GAP_BAR    = 4;
    private static final int GAP_TIME   = 5;
    private static final int BAR_H_BASE = 2;

    // ── PowerShell poll script ────────────────────────────────────────────────
    private static final String POLL_SCRIPT =
            "[void][System.Reflection.Assembly]::LoadFile('C:\\Windows\\Microsoft.NET\\Framework64\\v4.0.30319\\System.Runtime.WindowsRuntime.dll')\r\n" +
                    "$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]\r\n" +
                    "$g = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -like 'IAsyncOperation*' })[0]\r\n" +
                    "function Aw($op,$t){$m=$g.MakeGenericMethod($t);$task=$m.Invoke($null,@($op));$task.GetAwaiter().GetResult()}\r\n" +
                    "$asStreamForRead = [System.IO.WindowsRuntimeStreamExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsStreamForRead' -and $_.GetParameters().Count -eq 1 } | Select-Object -First 1\r\n" +
                    "try {\r\n" +
                    "  $mgr = Aw([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])\r\n" +
                    "  $s = $mgr.GetCurrentSession()\r\n" +
                    "  if ($s) {\r\n" +
                    "    $p = Aw($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])\r\n" +
                    "    $tl = $s.GetTimelineProperties()\r\n" +
                    "    $pb = $s.GetPlaybackInfo()\r\n" +
                    "    if ($p.Title) {\r\n" +
                    "      if ($p.Thumbnail -and $asStreamForRead) {\r\n" +
                    "        try {\r\n" +
                    "          $stream = Aw($p.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])\r\n" +
                    "          $netStream = $asStreamForRead.Invoke($null, @($stream))\r\n" +
                    "          $outPath = Join-Path $env:TEMP 'xenon_spotify_art.png'\r\n" +
                    "          $fs = [System.IO.File]::Create($outPath)\r\n" +
                    "          $netStream.CopyTo($fs)\r\n" +
                    "          $fs.Close()\r\n" +
                    "          $netStream.Close()\r\n" +
                    "        } catch {}\r\n" +
                    "      }\r\n" +
                    "      Write-Output ($p.Artist + '|||' + $p.Title + '|||' + [long]$tl.Position.TotalMilliseconds + '|||' + [long]$tl.EndTime.TotalMilliseconds + '|||' + ($pb.PlaybackStatus.ToString() -eq 'Playing'))\r\n" +
                    "    }\r\n" +
                    "  }\r\n" +
                    "} catch {}\r\n";

    // ── PowerShell control script ─────────────────────────────────────────────
    private static final String CTRL_SCRIPT =
            "param([string]$action)\r\n" +
                    "[void][System.Reflection.Assembly]::LoadFile('C:\\Windows\\Microsoft.NET\\Framework64\\v4.0.30319\\System.Runtime.WindowsRuntime.dll')\r\n" +
                    "$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]\r\n" +
                    "$g = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -like 'IAsyncOperation*' })[0]\r\n" +
                    "function Aw($op,$t){$m=$g.MakeGenericMethod($t);$task=$m.Invoke($null,@($op));$task.GetAwaiter().GetResult()}\r\n" +
                    "try {\r\n" +
                    "  $mgr = Aw([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])\r\n" +
                    "  $s = $mgr.GetCurrentSession()\r\n" +
                    "  if ($s) {\r\n" +
                    "    switch ($action) {\r\n" +
                    "      'next'   { Aw($s.TrySkipNextAsync()) ([bool]) | Out-Null }\r\n" +
                    "      'prev'   { Aw($s.TrySkipPreviousAsync()) ([bool]) | Out-Null }\r\n" +
                    "      'toggle' { Aw($s.TryTogglePlayPauseAsync()) ([bool]) | Out-Null }\r\n" +
                    "    }\r\n" +
                    "  }\r\n" +
                    "} catch {}\r\n";

    // ─────────────────────────────────────────────────────────────────────────

    public SpotifyHud() {
        super("Spotify HUD", Category.RENDER);
        addSetting(scale);
        INSTANCE = this;
    }

    public static boolean isActive()   { return INSTANCE != null && INSTANCE.isEnabled(); }
    public static float   getScale()   { return INSTANCE == null ? 1.0f : INSTANCE.scale.getValue(); }

    public static void setScale(float v) {
        if (INSTANCE == null) return;
        float lo = INSTANCE.scale.getMin() instanceof Float f ? f : 0.60f;
        float hi = INSTANCE.scale.getMax() instanceof Float f ? f : 2.00f;
        INSTANCE.scale.setValue(Math.max(lo, Math.min(hi, v)));
    }

    public static int   getCardW() { return Math.round(BASE_W * getScale()); }
    public static int   getCardH() { return Math.round(BASE_H * getScale()); }

    private static int   s(int base)     { return Math.round(base * getScale()); }
    private static float sf(float base)  { return base * getScale(); }

    private static int clampX(int x) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getWindow() == null) return x;
        int maxX = Math.max(0, mc.getWindow().getScaledWidth() - getCardW());
        return Math.max(0, Math.min(x, maxX));
    }

    private static int clampY(int y) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getWindow() == null) return y;
        int maxY = Math.max(0, mc.getWindow().getScaledHeight() - getCardH());
        return Math.max(0, Math.min(y, maxY));
    }

    private static int getPosX() {
        int[] pos = Hud.getElementPos(Hud.HudElement.SPOTIFY_HUD);
        return clampX(pos[0]);
    }

    private static int getPosY() {
        int[] pos = Hud.getElementPos(Hud.HudElement.SPOTIFY_HUD);
        return clampY(pos[1]);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        startBackground();
    }

    @Override
    public void onTick() {
        // Lazy-init: applyEnabled() in the config loader sets enabled=true without calling onEnable(),
        // so on game restart with SpotifyHud already active the scheduler would never start.
        if (scheduler == null || scheduler.isShutdown()) {
            startBackground();
        }
    }

    private void startBackground() {
        fade = 0f; scrollX = 0f; lastNanos = 0L; scrollNanos = 0L;
        hasPolledOnce = false;
        fastPollCount.set(0);
        writeScripts();
        artFile = new File(System.getenv("TEMP"), "xenon_spotify_art.png");

        Thread initialPoll = new Thread(this::poll, "xenon-spotify-initial-poll");
        initialPoll.setDaemon(true);
        initialPoll.start();

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "xenon-spotify");
            t.setDaemon(true);
            return t;
        });
        actionExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "xenon-spotify-ctrl");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(() -> {
            int count = fastPollCount.getAndIncrement();
            if (count >= 10) return;
            poll();
            if (!title.isEmpty()) fastPollCount.set(Integer.MAX_VALUE);
        }, 1000, 800, TimeUnit.MILLISECONDS);

        scheduler.scheduleAtFixedRate(this::poll, 2, 2, TimeUnit.SECONDS);
    }

    @Override
    public void onDisable() {
        if (scheduler  != null) scheduler.shutdownNow();
        if (actionExec != null) actionExec.shutdownNow();
        title = ""; artist = ""; isPlaying = false;
        anchorPosMs = 0; anchorTimeMs = 0; durMs = 0;
        fade = 0f; scrollX = 0f; hasArt = false;
        if (artTex != null) {
            try { MinecraftClient.getInstance().getTextureManager().destroyTexture(ART_ID); }
            catch (Exception ignored) {}
            artTex = null;
        }
    }

    // ── Script helpers ────────────────────────────────────────────────────────

    private void writeScripts() {
        try {
            pollScriptFile = File.createTempFile("xenon_smtc_poll_", ".ps1");
            pollScriptFile.deleteOnExit();
            try (Writer w = new FileWriter(pollScriptFile, StandardCharsets.UTF_8)) { w.write(POLL_SCRIPT); }
            ctrlScriptFile = File.createTempFile("xenon_smtc_ctrl_", ".ps1");
            ctrlScriptFile.deleteOnExit();
            try (Writer w = new FileWriter(ctrlScriptFile, StandardCharsets.UTF_8)) { w.write(CTRL_SCRIPT); }
        } catch (Exception e) { pollScriptFile = null; ctrlScriptFile = null; }
    }

    private void poll() {
        if (pollScriptFile == null || !pollScriptFile.exists()) writeScripts();
        if (pollScriptFile == null) { hasPolledOnce = true; return; }
        if (!polling.compareAndSet(false, true)) return;
        try {
            long beforeMs = System.currentTimeMillis();
            Process proc = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass",
                    "-File", pollScriptFile.getAbsolutePath()
            ).redirectErrorStream(true).start();

            String line = null;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = br.readLine()) != null) {
                    l = l.trim();
                    if (l.contains("|||")) { line = l; break; }
                }
            }
            proc.waitFor(8, TimeUnit.SECONDS);
            long afterMs     = System.currentTimeMillis();
            long effectiveMs = (beforeMs + afterMs) / 2;

            if (line != null) {
                String[] p = line.split("\\|\\|\\|", -1);
                if (p.length >= 2 && !p[1].trim().isEmpty()) {
                    String  newTitle     = p[1].trim();
                    String  newArtist    = p[0].trim();
                    long    newPosMs     = p.length > 2 ? parseLong(p[2]) : 0;
                    long    newDurMs     = p.length > 3 ? parseLong(p[3]) : 0;
                    boolean playing      = p.length > 4 && p[4].trim().equalsIgnoreCase("True");
                    boolean trackChanged = !newTitle.equals(title);
                    boolean stateChanged = playing != isPlaying;
                    long    predicted    = isPlaying
                            ? anchorPosMs + Math.max(0, effectiveMs - anchorTimeMs)
                            : anchorPosMs;
                    boolean bigJump = Math.abs(newPosMs - predicted) > 4000;

                    if (trackChanged) scrollX = 0f;
                    title = newTitle; artist = newArtist;
                    durMs = newDurMs; isPlaying = playing;

                    if (trackChanged || stateChanged || bigJump || anchorTimeMs == 0) {
                        anchorPosMs  = newPosMs;
                        anchorTimeMs = effectiveMs;
                    }
                    return;
                }
            }
            // No valid response: keep last known state instead of freezing the timer.
            // Only clear if poll has been failing for a while (handled implicitly: track stays
            // until SMTC reports a different one).
        } catch (Exception ignored) {
            // Transient failure — preserve state so the timer keeps ticking smoothly.
        } finally {
            hasPolledOnce = true;
            polling.set(false);
        }
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    public static void runAction(String action) {
        if (INSTANCE == null || INSTANCE.actionExec == null || INSTANCE.ctrlScriptFile == null) return;
        // Optimistically update state so the UI reacts instantly instead of waiting on PowerShell.
        long nowMs = System.currentTimeMillis();
        if ("toggle".equals(action)) {
            if (INSTANCE.isPlaying) {
                long elapsed = INSTANCE.anchorTimeMs > 0 ? Math.max(0, nowMs - INSTANCE.anchorTimeMs) : 0;
                INSTANCE.anchorPosMs = INSTANCE.anchorPosMs + elapsed;
                INSTANCE.anchorTimeMs = nowMs;
                INSTANCE.isPlaying = false;
            } else {
                INSTANCE.anchorTimeMs = nowMs;
                INSTANCE.isPlaying = true;
            }
        }
        INSTANCE.actionExec.submit(() -> {
            try {
                Process p = new ProcessBuilder(
                        "powershell", "-NoProfile", "-NonInteractive",
                        "-ExecutionPolicy", "Bypass",
                        "-File", INSTANCE.ctrlScriptFile.getAbsolutePath(),
                        "-action", action
                ).redirectErrorStream(true).start();
                p.waitFor(5, TimeUnit.SECONDS);
                if (INSTANCE.scheduler != null && !INSTANCE.scheduler.isShutdown()) {
                    INSTANCE.scheduler.submit(INSTANCE::poll);
                }
            } catch (Exception ignored) {}
        });
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    /**
     * Returns [titleY, artistY, barY, timeY, btnY] all in screen-space pixels.
     */
    private static int[] computeLayout(int by, int cardH) {
        int barH     = Math.max(2, s(BAR_H_BASE));
        int contentH = ROW_H
                + s(GAP_TITLE) + ROW_H   // artist
                + s(GAP_ART)   + barH    // progress bar
                + s(GAP_BAR)   + ROW_H   // timestamps
                + s(GAP_TIME)  + ROW_H;  // buttons
        int top     = by + (cardH - contentH) / 2;
        int titleY  = top;
        int artistY = titleY  + ROW_H  + s(GAP_TITLE);
        int barY    = artistY + ROW_H  + s(GAP_ART);
        int timeY   = barY   + barH   + s(GAP_BAR);
        int btnY    = timeY  + ROW_H  + s(GAP_TIME);
        return new int[]{ titleY, artistY, barY, timeY, btnY };
    }

    // ── Click handling ────────────────────────────────────────────────────────

    public static boolean handleClick(double mouseX, double mouseY) {
        if (INSTANCE == null || !INSTANCE.isEnabled()) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.textRenderer == null) return false;

        int bx    = getPosX(), by = getPosY();
        int cardW = getCardW(), cardH = getCardH();
        var tr    = mc.textRenderer;

        String prev = "\u25C0\u25C0";
        String togg = INSTANCE.isPlaying ? "\u2759\u2759" : "\u25B6";
        String next = "\u25B6\u25B6";

        int[] layout = computeLayout(by, cardH);
        int   btnY   = layout[4];
        int   cx     = bx + cardW / 2;
        int   gap    = s(14);
        int   togX   = cx - tr.getWidth(togg) / 2;
        int   prvX   = togX - gap - tr.getWidth(prev);
        int   nxtX   = togX + tr.getWidth(togg) + gap;
        int   padX   = s(5), padY = s(3);

        int[][] rects = {
                { prvX - padX, btnY - padY, tr.getWidth(prev) + 2 * padX, ROW_H + 2 * padY },
                { togX - padX, btnY - padY, tr.getWidth(togg) + 2 * padX, ROW_H + 2 * padY },
                { nxtX - padX, btnY - padY, tr.getWidth(next) + 2 * padX, ROW_H + 2 * padY }
        };
        String[] actions = { "prev", "toggle", "next" };
        for (int i = 0; i < rects.length; i++) {
            int[] r = rects[i];
            if (mouseX >= r[0] && mouseX <= r[0] + r[2]
                    && mouseY >= r[1] && mouseY <= r[1] + r[3]) {
                runAction(actions[i]);
                return true;
            }
        }
        return false;
    }

    // ── Album art loader ──────────────────────────────────────────────────────

    private void tryLoadArt() {
        if (artFile == null || !artFile.exists()) return;
        long mod  = artFile.lastModified();
        long size = artFile.length();
        if (size < 200) return;
        if (mod == artLastModified && artTex != null) return;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(artFile.toPath());
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                NativeImage img = NativeImage.read(in);
                if (artTex != null) {
                    try { MinecraftClient.getInstance().getTextureManager().destroyTexture(ART_ID); }
                    catch (Exception ignored) {}
                }
                artTex = new NativeImageBackedTexture(() -> "spotify_art", img);
                MinecraftClient.getInstance().getTextureManager().registerTexture(ART_ID, artTex);
                artLastModified = mod;
                hasArt = true;
            }
        } catch (Exception ignored) {}
    }

    // ── Render ────────────────────────────────────────────────────────────────

    public static void renderHud(DrawContext ctx) {
        if (INSTANCE == null || !INSTANCE.isEnabled()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return;
        if (mc.currentScreen instanceof ClickGUI) return;
        if (mc.getDebugHud().shouldShowDebugHud()) return;

        // Delta time & fade-in
        long  now = System.nanoTime();
        float dt  = INSTANCE.lastNanos == 0L ? 0.016f
                : Math.min(0.1f, (now - INSTANCE.lastNanos) / 1_000_000_000f);
        INSTANCE.lastNanos = now;
        INSTANCE.fade += (1f - INSTANCE.fade) * (1f - (float) Math.exp(-12f * dt));
        float a = INSTANCE.fade;
        if (a < 0.01f) return;

        INSTANCE.tryLoadArt();

        // ── Dimensions & position ──────────────────────────────────────────
        int cardW   = getCardW();
        int cardH   = getCardH();
        int artSize = s(BASE_ART);
        int padX    = s(BASE_PAD_X);

        // Slide in from left on fade
        int bx = getPosX() + Math.round((1f - a) * -(cardW + s(20)));
        int by = getPosY();

        var tr     = mc.textRenderer;
        int accent = XenonPlus.getAccentARGB();           // client green

        // ── Color palette — matches the ClickGUI panels ────────────────────
        //   Background:  near-black, same as the module list panels
        //   Accent:      client green for the bar fill and active state
        //   Text:        white (title) / dim grey (artist / time)
        //   No coloured outline — a single-pixel dark border like the GUI panels
        int cBg      = alpha(0x0E1117, (int)(230 * a));   // ~same darkness as panels
        int cBorder  = alpha(0x1E2430, (int)(200 * a));   // very subtle 1-px border
        int cArtBg   = alpha(0x161C26, (int)(210 * a));   // art placeholder bg
        int cTitle   = alpha(0xFFFFFF, (int)(255 * a));   // bright white
        int cArtist  = alpha(0x8A9BAA, (int)(200 * a));   // muted grey
        int cBarBg   = alpha(0x1E2430, (int)(210 * a));   // bar track
        int cBarFill = accent & (((int)(200 * a)) << 24 | 0x00FFFFFF); // green fill
        int cTime    = alpha(0x5A6878, (int)(180 * a));   // dim timestamps
        int cBtn     = alpha(0xCCCCCC, (int)(220 * a));   // slightly dimmed buttons
        int cNote    = alpha(accent & 0x00FFFFFF, (int)(160 * a)); // green music note

        // ── Card background — sharp corners matching the GUI panels ────────
        //   radius = 2 px → just barely rounded, matching the panel corners
        float radius = sf(2f);
        RenderUtil.drawRoundedRect(ctx, bx, by, cardW, cardH, radius, cBg, false);
        RenderUtil.drawOutline(ctx, bx, by, cardW, cardH, radius, 1f, cBorder, false);

        // ── Album art / placeholder ────────────────────────────────────────
        int ax = bx + padX;
        int ay = by + (cardH - artSize) / 2;

        if (INSTANCE.hasArt) {
            int tint = (Math.max(0, Math.min(255, (int)(255 * a))) << 24) | 0x00FFFFFF;
            RenderUtil.drawTexture(ctx, ax, ay, artSize, ART_ID, tint, sf(2f), false);
        } else {
            // Dark placeholder with centred music note — no coloured border
            RenderUtil.drawRoundedRect(ctx, ax, ay, artSize, artSize, sf(2f), cArtBg, false);
            String noteChar = "\u266B";
            int nw = tr.getWidth(noteChar);
            ctx.drawText(tr, noteChar, ax + (artSize - nw) / 2, ay + (artSize - 9) / 2, cNote, false);
        }

        // ── Text area ─────────────────────────────────────────────────────
        int tx    = ax + artSize + padX;
        int maxTw = Math.max(20, bx + cardW - padX - tx);

        int[] layout = computeLayout(by, cardH);
        int titleY  = layout[0];
        int artistY = layout[1];
        int barY    = layout[2];
        int timeY   = layout[3];
        int btnY    = layout[4];
        int barH    = Math.max(2, s(BAR_H_BASE));

        // Title (with horizontal scroll when too wide)
        String song = !INSTANCE.hasPolledOnce ? ""
                : (INSTANCE.title.isEmpty() ? "No track" : INSTANCE.title);

        if (!song.isEmpty()) {
            int songW = tr.getWidth(song);
            if (songW > maxTw) {
                long  sNow = System.nanoTime();
                float sdt  = INSTANCE.scrollNanos == 0L ? 0f
                        : Math.min(0.1f, (sNow - INSTANCE.scrollNanos) / 1_000_000_000f);
                INSTANCE.scrollNanos = sNow;
                INSTANCE.scrollX += SCROLL_PX_S * sdt;
                if (INSTANCE.scrollX > songW + SCROLL_GAP) INSTANCE.scrollX = 0f;
                int off = (int) INSTANCE.scrollX;
                ctx.enableScissor(tx, titleY - 1, tx + maxTw, titleY + 10);
                ctx.drawText(tr, song, tx - off,                       titleY, cTitle, false);
                ctx.drawText(tr, song, tx - off + songW + SCROLL_GAP,  titleY, cTitle, false);
                ctx.disableScissor();
            } else {
                INSTANCE.scrollX = 0f;
                ctx.drawText(tr, song, tx, titleY, cTitle, false);
            }
        }

        // Artist
        if (!INSTANCE.artist.isEmpty()) {
            ctx.drawText(tr, tr.trimToWidth(INSTANCE.artist, maxTw), tx, artistY, cArtist, false);
        }

        // Progress bar track
        RenderUtil.drawRoundedRect(ctx, tx, barY, maxTw, barH, barH / 2f, cBarBg, false);

        // Progress bar fill — accent colour
        long nowMs = System.currentTimeMillis();
        long posMsNow;
        if (INSTANCE.isPlaying && INSTANCE.anchorTimeMs > 0) {
            long elapsed = Math.max(0, nowMs - INSTANCE.anchorTimeMs);
            posMsNow = INSTANCE.anchorPosMs + elapsed;
            if (INSTANCE.durMs > 0) posMsNow = Math.min(INSTANCE.durMs, posMsNow);
        } else {
            posMsNow = INSTANCE.anchorPosMs;
        }

        if (INSTANCE.durMs > 0 && posMsNow > 0) {
            float pct  = Math.min(1f, (float) posMsNow / INSTANCE.durMs);
            int   fill = Math.max(barH, Math.round(maxTw * pct));
            // Accent-coloured fill — the one pop of colour matching the GUI highlights
            int fillCol = (Math.max(0, Math.min(255, (int)(200 * a))) << 24)
                    | (accent & 0x00FFFFFF);
            RenderUtil.drawRoundedRect(ctx, tx, barY, fill, barH, barH / 2f, fillCol, false);
        }

        // Timestamps
        long   pos  = posMsNow / 1000;
        long   dur  = INSTANCE.durMs / 1000;
        String tPos = fmt(pos);
        String tDur = dur > 0 ? fmt(dur) : "--:--";
        ctx.drawText(tr, tPos, tx,                              timeY, cTime, false);
        ctx.drawText(tr, tDur, tx + maxTw - tr.getWidth(tDur), timeY, cTime, false);

        // Playback controls — centred, same dimmed white as inactive module text
        String prev = "\u25C0\u25C0";
        String togg = INSTANCE.isPlaying ? "\u2759\u2759" : "\u25B6";
        String next = "\u25B6\u25B6";
        int cx   = bx + cardW / 2;
        int gap  = s(14);
        int togX = cx  - tr.getWidth(togg) / 2;
        int prvX = togX - gap - tr.getWidth(prev);
        int nxtX = togX + tr.getWidth(togg) + gap;

        // Highlight the toggle button with the accent colour when playing
        int cToggle = INSTANCE.isPlaying
                ? (Math.max(0, Math.min(255, (int)(230 * a))) << 24) | (accent & 0x00FFFFFF)
                : cBtn;

        ctx.drawText(tr, prev, prvX, btnY, cBtn,    false);
        ctx.drawText(tr, togg, togX, btnY, cToggle, false);
        ctx.drawText(tr, next, nxtX, btnY, cBtn,    false);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String fmt(long sec) {
        return String.format("%d:%02d", sec / 60, sec % 60);
    }

    private static int alpha(int rgb, int a) {
        return (Math.max(0, Math.min(255, a)) << 24) | (rgb & 0x00FFFFFF);
    }
}

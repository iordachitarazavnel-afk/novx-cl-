package com.xenon.module.modules.donut;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.Setting;
import net.minecraft.scoreboard.ScoreAccess;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.scoreboard.number.BlankNumberFormat;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;

public final class FakeStats extends Module {

    private static final String OBJECTIVE_NAME = "xenon_fake_stats";
    private static final int MAX_SIDEBAR_LINES = 15;

    private final Setting<String> money = new Setting<>("Money", "0");
    private final Setting<String> shards = new Setting<>("Shards", "0");
    private final Setting<String> kills = new Setting<>("Kills", "0");
    private final Setting<String> deaths = new Setting<>("Deaths", "0");
    private final Setting<String> playtime = new Setting<>("Playtime", "0m");

    private final Random randomSource = new Random();

    private ScoreboardObjective originalObjective;
    private String originalObjectiveName;
    private ScoreboardObjective customObjective;
    private AppliedStats appliedStats;
    private Object lastWorld;
    private String lastSnapshotSignature = "";
    private boolean needsRefresh;

    public FakeStats() {
        super("FakeStats", Category.DONUT);
        addSetting(money);
        addSetting(shards);
        addSetting(kills);
        addSetting(deaths);
        addSetting(playtime);

        randomizeSettings();
        applyCurrentValues();
    }

    @Override
    public void onEnable() {
        lastWorld = mc.world;
        originalObjective = null;
        originalObjectiveName = null;
        customObjective = null;
        lastSnapshotSignature = "";
        randomizeSettings();
        applyCurrentValues();
        needsRefresh = true;
    }

    @Override
    public void onDisable() {
        restoreOriginalScoreboard();
        lastWorld = null;
        originalObjective = null;
        originalObjectiveName = null;
        customObjective = null;
        lastSnapshotSignature = "";
        needsRefresh = false;
    }

    @Override
    public void onTick() {
        if (mc.world == null) {
            originalObjective = null;
            originalObjectiveName = null;
            customObjective = null;
            lastWorld = null;
            lastSnapshotSignature = "";
            return;
        }

        if (mc.world != lastWorld) {
            originalObjective = null;
            originalObjectiveName = null;
            customObjective = null;
            lastSnapshotSignature = "";
            lastWorld = mc.world;
            needsRefresh = true;
        }

        captureOriginalObjective();
        if (originalObjective == null) {
            return;
        }

        Scoreboard scoreboard = mc.world.getScoreboard();
        if (!scoreboard.getObjectives().contains(originalObjective)) {
            originalObjective = null;
            captureOriginalObjective();
            if (originalObjective == null) {
                return;
            }
        }

        Snapshot snapshot = createSnapshot(scoreboard, originalObjective);
        if (snapshot == null) {
            return;
        }

        syncAppliedStatsWithSettings();

        if (!snapshot.signature().equals(lastSnapshotSignature) || customObjective == null) {
            needsRefresh = true;
        }

        rebuildIfPossible(scoreboard, snapshot);

        if (customObjective != null && scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR) != customObjective) {
            scoreboard.setObjectiveSlot(ScoreboardDisplaySlot.SIDEBAR, customObjective);
        }
    }

    private void randomizeSettings() {
        money.setValue(formatCompactNumber(randomBetweenLong(10_000L, 5_000_000_000L)));
        shards.setValue(formatCompactNumber(randomBetweenLong(0L, 2_500_000L)));
        kills.setValue(String.valueOf(randomBetweenLong(0L, 2_000L)));
        deaths.setValue(String.valueOf(randomBetweenLong(0L, 1_000L)));
        playtime.setValue(formatCompactPlaytime(randomBetweenLong(0L, 180L * 24L * 60L * 60L)));
    }

    private void applyCurrentValues() {
        appliedStats = new AppliedStats(
                sanitize(money.getValue(), "0"),
                sanitize(shards.getValue(), "0"),
                sanitize(kills.getValue(), "0"),
                sanitize(deaths.getValue(), "0"),
                sanitize(playtime.getValue(), "0m")
        );
        needsRefresh = true;
    }

    private void syncAppliedStatsWithSettings() {
        AppliedStats current = new AppliedStats(
                sanitize(money.getValue(), "0"),
                sanitize(shards.getValue(), "0"),
                sanitize(kills.getValue(), "0"),
                sanitize(deaths.getValue(), "0"),
                sanitize(playtime.getValue(), "0m")
        );
        if (appliedStats == null || !appliedStats.signature().equals(current.signature())) {
            appliedStats = current;
            needsRefresh = true;
        }
    }

    private void captureOriginalObjective() {
        if (mc.world == null) {
            return;
        }

        Scoreboard scoreboard = mc.world.getScoreboard();
        ScoreboardObjective current = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        if (current != null && !OBJECTIVE_NAME.equals(current.getName())) {
            originalObjective = current;
            originalObjectiveName = current.getName();
            return;
        }

        if (originalObjective != null && scoreboard.getObjectives().contains(originalObjective)) {
            return;
        }

        if (originalObjectiveName != null) {
            ScoreboardObjective namedObjective = scoreboard.getNullableObjective(originalObjectiveName);
            if (namedObjective != null && !OBJECTIVE_NAME.equals(namedObjective.getName())) {
                originalObjective = namedObjective;
                return;
            }
        }

        for (ScoreboardObjective objective : scoreboard.getObjectives()) {
            if (!OBJECTIVE_NAME.equals(objective.getName())) {
                originalObjective = objective;
                originalObjectiveName = objective.getName();
                return;
            }
        }
    }

    private Snapshot createSnapshot(Scoreboard scoreboard, ScoreboardObjective objective) {
        List<SourceLine> lines = new ArrayList<>();
        List<ScoreboardEntry> entries = new ArrayList<>(scoreboard.getScoreboardEntries(objective));
        entries.removeIf(ScoreboardEntry::hidden);
        entries.sort(Comparator.comparingInt(ScoreboardEntry::value).reversed());
        if (entries.size() > MAX_SIDEBAR_LINES) {
            entries = new ArrayList<>(entries.subList(0, MAX_SIDEBAR_LINES));
        }

        for (ScoreboardEntry entry : entries) {
            lines.add(new SourceLine(entry.value(), getVisibleLine(scoreboard, entry)));
        }

        Text title = objective.getDisplayName() != null ? objective.getDisplayName().copy() : Text.literal("Donut SMP");

        StringBuilder signature = new StringBuilder(title.getString());
        for (SourceLine line : lines) {
            signature.append('\n').append(line.score()).append(':').append(line.text().getString());
        }
        signature.append('\n').append(appliedStats != null ? appliedStats.signature() : "");
        return new Snapshot(title, lines, signature.toString());
    }

    private Text getVisibleLine(Scoreboard scoreboard, ScoreboardEntry entry) {
        if (entry.display() != null) {
            return entry.display().copy();
        }

        Text baseText = entry.name() != null ? entry.name().copy() : Text.literal(entry.owner());
        Team team = scoreboard.getScoreHolderTeam(entry.owner());
        return Team.decorateName(team, baseText).copy();
    }

    private void rebuildIfPossible(Scoreboard scoreboard, Snapshot snapshot) {
        if (!needsRefresh || appliedStats == null) {
            return;
        }

        ScoreboardObjective existing = scoreboard.getNullableObjective(OBJECTIVE_NAME);
        if (existing != null) {
            scoreboard.removeObjective(existing);
        }

        customObjective = scoreboard.addObjective(
                OBJECTIVE_NAME,
                ScoreboardCriterion.DUMMY,
                snapshot.title().copy(),
                ScoreboardCriterion.RenderType.INTEGER,
                true,
                BlankNumberFormat.INSTANCE
        );
        scoreboard.setObjectiveSlot(ScoreboardDisplaySlot.SIDEBAR, customObjective);

        List<SourceLine> lines = snapshot.lines();
        for (int i = 0; i < lines.size(); i++) {
            SourceLine line = lines.get(i);
            ScoreHolder holder = ScoreHolder.fromName("fake_stats_line_" + i);
            ScoreAccess score = scoreboard.getOrCreateScore(holder, customObjective);
            score.setScore(lines.size() - i);
            score.setDisplayText(replaceTrackedStat(line.text()));
            score.setNumberFormat(BlankNumberFormat.INSTANCE);
        }

        lastSnapshotSignature = snapshot.signature();
        needsRefresh = false;
    }

    private Text replaceTrackedStat(Text originalText) {
        Text replaced = tryReplaceValue(originalText, "money", appliedStats.money());
        if (replaced != null) {
            return replaced;
        }

        replaced = tryReplaceValue(originalText, "shards", appliedStats.shards());
        if (replaced != null) {
            return replaced;
        }

        replaced = tryReplaceValue(originalText, "kills", appliedStats.kills());
        if (replaced != null) {
            return replaced;
        }

        replaced = tryReplaceValue(originalText, "deaths", appliedStats.deaths());
        if (replaced != null) {
            return replaced;
        }

        replaced = tryReplaceValue(originalText, "playtime", appliedStats.playtime());
        if (replaced != null) {
            return replaced;
        }

        return originalText.copy();
    }

    private Text tryReplaceValue(Text originalText, String label, String newValue) {
        String rendered = originalText.getString();
        int valueStart = findValueStart(rendered, label);
        if (valueStart < 0) {
            return null;
        }

        List<TextSegment> segments = collectSegments(originalText);
        MutableText result = Text.empty();
        appendTextRange(result, segments, valueStart);
        result.append(Text.literal(newValue).setStyle(findStyleAt(segments, valueStart)));
        return result;
    }

    private int findValueStart(String rendered, String label) {
        String lower = rendered.toLowerCase(Locale.ROOT);
        int labelIndex = lower.indexOf(label);
        if (labelIndex < 0) {
            return -1;
        }

        int index = labelIndex + label.length();
        while (index < rendered.length() && Character.isWhitespace(rendered.charAt(index))) {
            index++;
        }
        return index < rendered.length() ? index : -1;
    }

    private List<TextSegment> collectSegments(Text text) {
        List<TextSegment> segments = new ArrayList<>();
        text.visit((style, string) -> {
            if (!string.isEmpty()) {
                segments.add(new TextSegment(string, style));
            }
            return Optional.empty();
        }, Style.EMPTY);
        return segments;
    }

    private void appendTextRange(MutableText result, List<TextSegment> segments, int endExclusive) {
        int remaining = Math.max(0, endExclusive);
        for (TextSegment segment : segments) {
            if (remaining <= 0) {
                return;
            }

            String value = segment.value();
            int length = Math.min(value.length(), remaining);
            result.append(Text.literal(value.substring(0, length)).setStyle(segment.style()));
            remaining -= length;
        }
    }

    private Style findStyleAt(List<TextSegment> segments, int charIndex) {
        int index = Math.max(0, charIndex);
        Style fallback = Style.EMPTY;
        for (TextSegment segment : segments) {
            if (!segment.value().isEmpty()) {
                fallback = segment.style();
            }
            if (index < segment.value().length()) {
                return segment.style();
            }
            index -= segment.value().length();
        }
        return fallback;
    }

    private void restoreOriginalScoreboard() {
        if (mc.world == null) {
            return;
        }

        Scoreboard scoreboard = mc.world.getScoreboard();
        ScoreboardObjective existing = scoreboard.getNullableObjective(OBJECTIVE_NAME);
        if (existing != null) {
            scoreboard.removeObjective(existing);
        }

        if (originalObjective != null && scoreboard.getObjectives().contains(originalObjective)) {
            scoreboard.setObjectiveSlot(ScoreboardDisplaySlot.SIDEBAR, originalObjective);
        }
    }

    private long randomBetweenLong(long min, long max) {
        if (min >= max) {
            return min;
        }
        return min + (long) Math.floor(randomSource.nextDouble() * (double) (max - min + 1L));
    }

    private String formatCompactNumber(long value) {
        long absolute = Math.abs(value);
        if (absolute < 1_000L) {
            return Long.toString(value);
        }
        if (absolute < 1_000_000L) {
            return formatCompactValue(value / 1_000.0, "K");
        }
        if (absolute < 1_000_000_000L) {
            return formatCompactValue(value / 1_000_000.0, "M");
        }
        return formatCompactValue(value / 1_000_000_000.0, "B");
    }

    private String formatCompactValue(double value, String suffix) {
        String pattern = value >= 100.0 ? "%.0f%s" : value >= 10.0 ? "%.1f%s" : "%.2f%s";
        return String.format(Locale.US, pattern, value, suffix);
    }

    private String formatCompactPlaytime(long totalSeconds) {
        long totalHours = totalSeconds / 3600L;
        long totalDays = totalHours / 24L;
        long hours = totalHours % 24L;
        long minutes = (totalSeconds % 3600L) / 60L;

        if (totalDays > 0L) {
            return String.format(Locale.US, "%dd %dh", totalDays, hours);
        }
        if (totalHours > 0L) {
            return String.format(Locale.US, "%dh %dm", totalHours, minutes);
        }
        return String.format(Locale.US, "%dm", minutes);
    }

    private String sanitize(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private record TextSegment(String value, Style style) {
    }

    private record SourceLine(int score, Text text) {
    }

    private record Snapshot(Text title, List<SourceLine> lines, String signature) {
    }

    private record AppliedStats(String money, String shards, String kills, String deaths, String playtime) {
        private String signature() {
            return money + "|" + shards + "|" + kills + "|" + deaths + "|" + playtime;
        }
    }
}

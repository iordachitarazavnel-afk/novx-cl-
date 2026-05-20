package com.xenon.utils;

import com.xenon.module.modules.misc.NameProtect;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class NameProtectUtil {

    private NameProtectUtil() {
    }

    public static String replace(String text) {
        if (text == null || !isActive()) {
            return text;
        }

        String realName = getRealName();
        String fakeName = getFakeName();
        if (realName == null || fakeName == null || realName.isBlank() || realName.equals(fakeName)) {
            return text;
        }

        return text.contains(realName) ? text.replace(realName, fakeName) : text;
    }

    public static StringVisitable replace(StringVisitable visitable) {
        if (visitable == null || !isActive()) {
            return visitable;
        }

        List<StyledChunk> replaced = replaceChunks(collect(visitable));
        if (replaced == null) {
            return visitable;
        }

        if (replaced.isEmpty()) {
            return StringVisitable.EMPTY;
        }

        List<StringVisitable> parts = new ArrayList<>(replaced.size());
        for (StyledChunk chunk : replaced) {
            parts.add(StringVisitable.styled(chunk.text(), chunk.style()));
        }
        return StringVisitable.concat(parts);
    }

    public static OrderedText replace(OrderedText orderedText) {
        if (orderedText == null || !isActive()) {
            return orderedText;
        }

        List<StyledChunk> replaced = replaceChunks(collect(orderedText));
        if (replaced == null) {
            return orderedText;
        }

        if (replaced.isEmpty()) {
            return OrderedText.empty();
        }

        List<OrderedText> parts = new ArrayList<>(replaced.size());
        for (StyledChunk chunk : replaced) {
            parts.add(OrderedText.styledForwardsVisitedString(chunk.text(), chunk.style()));
        }
        return OrderedText.concat(parts);
    }

    private static boolean isActive() {
        return NameProtect.instance != null
                && NameProtect.instance.isEnabled()
                && getRealName() != null
                && !getRealName().isBlank();
    }

    private static String getRealName() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getSession() == null) {
            return null;
        }
        return client.getSession().getUsername();
    }

    private static String getFakeName() {
        if (NameProtect.instance == null) {
            return null;
        }
        return NameProtect.instance.getFakeName();
    }

    private static List<StyledChunk> collect(StringVisitable visitable) {
        List<StyledChunk> chunks = new ArrayList<>();
        visitable.visit((style, text) -> {
            appendCodePoints(chunks, text, style);
            return Optional.empty();
        }, Style.EMPTY);
        return chunks;
    }

    private static List<StyledChunk> collect(OrderedText orderedText) {
        List<StyledChunk> chunks = new ArrayList<>();
        orderedText.accept((index, style, codePoint) -> {
            chunks.add(new StyledChunk(new String(Character.toChars(codePoint)), style));
            return true;
        });
        return chunks;
    }

    private static void appendCodePoints(List<StyledChunk> chunks, String text, Style style) {
        if (text == null || text.isEmpty()) {
            return;
        }

        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            chunks.add(new StyledChunk(new String(Character.toChars(codePoint)), style));
            offset += Character.charCount(codePoint);
        }
    }

    private static List<StyledChunk> replaceChunks(List<StyledChunk> chunks) {
        String realName = getRealName();
        String fakeName = getFakeName();
        if (realName == null || fakeName == null || realName.isBlank() || realName.equals(fakeName)) {
            return null;
        }

        StringBuilder plainBuilder = new StringBuilder();
        List<Integer> chunkStarts = new ArrayList<>(chunks.size());
        for (StyledChunk chunk : chunks) {
            chunkStarts.add(plainBuilder.length());
            plainBuilder.append(chunk.text());
        }

        String plain = plainBuilder.toString();
        if (!plain.contains(realName)) {
            return null;
        }

        List<StyledChunk> replaced = new ArrayList<>();
        int chunkIndex = 0;
        int searchFrom = 0;
        int matchIndex;
        while ((matchIndex = plain.indexOf(realName, searchFrom)) >= 0) {
            while (chunkIndex < chunks.size() && chunkStarts.get(chunkIndex) < matchIndex) {
                replaced.add(chunks.get(chunkIndex++));
            }

            Style style = chunkIndex < chunks.size() ? chunks.get(chunkIndex).style() : Style.EMPTY;
            replaced.add(new StyledChunk(fakeName, style));

            int matchEnd = matchIndex + realName.length();
            while (chunkIndex < chunks.size() && chunkStarts.get(chunkIndex) < matchEnd) {
                chunkIndex++;
            }
            searchFrom = matchEnd;
        }

        while (chunkIndex < chunks.size()) {
            replaced.add(chunks.get(chunkIndex++));
        }

        return merge(replaced);
    }

    private static List<StyledChunk> merge(List<StyledChunk> chunks) {
        if (chunks.isEmpty()) {
            return chunks;
        }

        List<StyledChunk> merged = new ArrayList<>(chunks.size());
        StyledChunk current = chunks.getFirst();
        for (int i = 1; i < chunks.size(); i++) {
            StyledChunk next = chunks.get(i);
            if (Objects.equals(current.style(), next.style())) {
                current = new StyledChunk(current.text() + next.text(), current.style());
                continue;
            }
            merged.add(current);
            current = next;
        }
        merged.add(current);
        return merged;
    }

    private record StyledChunk(String text, Style style) {
        private StyledChunk {
            style = style == null ? Style.EMPTY : style;
        }
    }
}

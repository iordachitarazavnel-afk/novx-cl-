package com.xenon.utils;

import com.xenon.module.modules.donut.FakeRoles;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Style;

public final class FakeRolesUtil {
   private FakeRolesUtil() {
   }

   public static String replace(String text) {
      if (text != null && FakeRoles.isActive()) {
         String realName = getRealName();
         if (realName == null || realName.isBlank()) {
            return text;
         } else if (!text.contains(realName)) {
            return text;
         } else {
            String prefixed = FakeRoles.getPrefixedNameString();
            return prefixed != null && !prefixed.equals(realName) ? text.replace(realName, prefixed) : text;
         }
      } else {
         return text;
      }
   }

   public static OrderedText replace(OrderedText orderedText) {
      if (orderedText != null && FakeRoles.isActive()) {
         List<FakeRolesUtil.StyledChar> replaced = replaceChunks(collectOrdered(orderedText));
         if (replaced == null) {
            return orderedText;
         } else if (replaced.isEmpty()) {
            return OrderedText.empty();
         } else {
            List<OrderedText> parts = new ArrayList<>(replaced.size());

            for (FakeRolesUtil.StyledChar sc : replaced) {
               parts.add(OrderedText.styledForwardsVisitedString(sc.text(), sc.style()));
            }

            return OrderedText.concat(parts);
         }
      } else {
         return orderedText;
      }
   }

   public static StringVisitable replace(StringVisitable visitable) {
      if (visitable != null && FakeRoles.isActive()) {
         List<FakeRolesUtil.StyledChar> replaced = replaceChunks(collectVisitable(visitable));
         if (replaced == null) {
            return visitable;
         } else if (replaced.isEmpty()) {
            return StringVisitable.EMPTY;
         } else {
            List<StringVisitable> parts = new ArrayList<>(replaced.size());

            for (FakeRolesUtil.StyledChar sc : replaced) {
               parts.add(StringVisitable.styled(sc.text(), sc.style()));
            }

            return StringVisitable.concat(parts);
         }
      } else {
         return visitable;
      }
   }

   private static String getRealName() {
      MinecraftClient client = MinecraftClient.getInstance();
      return client != null && client.getSession() != null ? client.getSession().getUsername() : null;
   }

   private static List<FakeRolesUtil.StyledChar> collectOrdered(OrderedText orderedText) {
      List<FakeRolesUtil.StyledChar> chars = new ArrayList<>();
      orderedText.accept((index, style, codePoint) -> {
         chars.add(new FakeRolesUtil.StyledChar(new String(Character.toChars(codePoint)), style));
         return true;
      });
      return chars;
   }

   private static List<FakeRolesUtil.StyledChar> collectVisitable(StringVisitable visitable) {
      List<FakeRolesUtil.StyledChar> chars = new ArrayList<>();
      visitable.visit((style, text) -> {
         int i = 0;

         while (i < text.length()) {
            int cp = text.codePointAt(i);
            chars.add(new FakeRolesUtil.StyledChar(new String(Character.toChars(cp)), style));
            i += Character.charCount(cp);
         }

         return Optional.empty();
      }, Style.EMPTY);
      return chars;
   }

   private static List<FakeRolesUtil.StyledChar> replaceChunks(List<FakeRolesUtil.StyledChar> chars) {
      String realName = getRealName();
      if (realName != null && !realName.isBlank()) {
         String prefixString = FakeRoles.getRolePrefixString();
         if (prefixString == null) {
            return null;
         } else {
            StringBuilder plain = new StringBuilder();
            List<Integer> charStarts = new ArrayList<>(chars.size());

            for (FakeRolesUtil.StyledChar sc : chars) {
               charStarts.add(plain.length());
               plain.append(sc.text());
            }

            String plainStr = plain.toString();
            if (!plainStr.contains(realName)) {
               return null;
            } else {
               Style nameStyle = FakeRoles.getRoleNameStyle();
               List<FakeRolesUtil.StyledChar> result = new ArrayList<>();
               int chunkIndex = 0;
               int searchFrom = 0;

               int matchIndex;
               while ((matchIndex = plainStr.indexOf(realName, searchFrom)) >= 0) {
                  while (chunkIndex < chars.size() && charStarts.get(chunkIndex) < matchIndex) {
                     result.add(chars.get(chunkIndex++));
                  }

                  int i = 0;

                  while (i < prefixString.length()) {
                     int cp = prefixString.codePointAt(i);
                     result.add(new FakeRolesUtil.StyledChar(new String(Character.toChars(cp)), FakeRoles.getRolePrefixStyleForChar(cp)));
                     i += Character.charCount(cp);
                  }

                  i = 0;

                  while (i < realName.length()) {
                     int cp = realName.codePointAt(i);
                     result.add(new FakeRolesUtil.StyledChar(new String(Character.toChars(cp)), nameStyle));
                     i += Character.charCount(cp);
                  }

                  i = matchIndex + realName.length();

                  while (chunkIndex < chars.size() && charStarts.get(chunkIndex) < i) {
                     chunkIndex++;
                  }

                  searchFrom = i;
               }

               while (chunkIndex < chars.size()) {
                  result.add(chars.get(chunkIndex++));
               }

               return merge(result);
            }
         }
      } else {
         return null;
      }
   }

   private static List<FakeRolesUtil.StyledChar> merge(List<FakeRolesUtil.StyledChar> chars) {
      if (chars.isEmpty()) {
         return chars;
      } else {
         List<FakeRolesUtil.StyledChar> merged = new ArrayList<>(chars.size());
         FakeRolesUtil.StyledChar current = chars.getFirst();

         for (int i = 1; i < chars.size(); i++) {
            FakeRolesUtil.StyledChar next = chars.get(i);
            if (Objects.equals(current.style(), next.style())) {
               current = new FakeRolesUtil.StyledChar(current.text() + next.text(), current.style());
            } else {
               merged.add(current);
               current = next;
            }
         }

         merged.add(current);
         return merged;
      }
   }

   private static record StyledChar(String text, Style style) {
      private StyledChar(String text, Style style) {
         style = style == null ? Style.EMPTY : style;
         this.text = text;
         this.style = style;
      }
   }
}

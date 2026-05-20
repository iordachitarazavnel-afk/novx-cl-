package com.xenon.module.modules.donut;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.ModeSetting;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

public final class FakeRoles extends Module {
   public static FakeRoles instance;
   private static final String MODE_NONE = "None";
   private static final String MODE_SRMOD = "SRMOD";
   private static final String MODE_MEDIA = "MEDIA";
   private static final String MODE_SRADMIN = "SRADMIN";
   private static final int TAG_BRACKET = 8355711;
   private static final int TAG_SRMOD = 5635925;
   private static final int TAG_MEDIA = 16733695;
   private static final int TAG_SRADMIN = 16733525;
   private static final int TAG_WHITE = 16777215;
   private final ModeSetting role = new ModeSetting("Role", "None", new String[]{"None", "SRMOD", "MEDIA", "SRADMIN"});

   public FakeRoles() {
      super("FakeRoles", Category.DONUT);
      instance = this;
      this.addSetting(this.role);
   }

   public static boolean isActive() {
      return instance != null && instance.isEnabled() && !instance.role.is("None") && mc != null && mc.player != null;
   }

   public static String getActiveRole() {
      return !isActive() ? null : (String)instance.role.getValue();
   }

   public static String getPlayerName() {
      return mc != null && mc.getSession() != null ? mc.getSession().getUsername() : null;
   }

   public static Text modifyChatText(Text original) {
      if (isActive() && original != null) {
         String playerName = getPlayerName();
         if (playerName != null && !playerName.isBlank()) {
            String plain = original.getString();
            if (!plain.contains(playerName)) {
               return original;
            } else {
               Text prefixed = buildPrefixedName(playerName);
               int idx = plain.indexOf(playerName);
               MutableText result = Text.empty();
               if (idx > 0) {
                  result.append(Text.literal(plain.substring(0, idx)));
               }

               result.append(prefixed);
               int afterIdx = idx + playerName.length();
               if (afterIdx < plain.length()) {
                  result.append(Text.literal(plain.substring(afterIdx)));
               }

               return result;
            }
         } else {
            return original;
         }
      } else {
         return original;
      }
   }

   public static Text buildPrefixedDisplayName(String playerName) {
      return isActive() && playerName != null ? buildPrefixedName(playerName) : null;
   }

   public static String getPrefixedNameString() {
      if (!isActive()) {
         return null;
      } else {
         String playerName = getPlayerName();
         if (playerName == null) {
            return null;
         } else {
            Text prefixed = buildPrefixedName(playerName);
            return prefixed.getString();
         }
      }
   }

   public static Style getRolePrefixStyle() {
      if (!isActive()) {
         return Style.EMPTY;
      } else {
         String activeRole = (String)instance.role.getValue();

         return switch (activeRole) {
            case "SRMOD" -> roleStyle(5635925);
            case "MEDIA" -> roleStyle(16733695);
            case "SRADMIN" -> roleStyle(16733525);
            default -> Style.EMPTY;
         };
      }
   }

   public static Style getRoleBracketStyle() {
      return isActive() ? Style.EMPTY.withColor(TextColor.fromRgb(8355711)).withBold(false) : Style.EMPTY;
   }

   public static Style getRolePrefixStyleForChar(int codePoint) {
      return codePoint != 91 && codePoint != 93 && !Character.isWhitespace(codePoint) ? getRolePrefixStyle() : getRoleBracketStyle();
   }

   public static Style getRoleNameStyle() {
      if (!isActive()) {
         return Style.EMPTY;
      } else {
         String activeRole = (String)instance.role.getValue();

         return switch (activeRole) {
            case "SRMOD" -> roleStyle(5635925);
            case "MEDIA" -> Style.EMPTY.withColor(TextColor.fromRgb(16777215)).withBold(false);
            case "SRADMIN" -> roleStyle(16733525);
            default -> Style.EMPTY;
         };
      }
   }

   public static String getRolePrefixString() {
      if (!isActive()) {
         return null;
      } else {
         String activeRole = (String)instance.role.getValue();

         return switch (activeRole) {
            case "SRMOD" -> "[SR.MOD] ";
            case "MEDIA" -> "[MEDIA] ";
            case "SRADMIN" -> "[SR.ADMIN] ";
            default -> null;
         };
      }
   }

   private static Text buildPrefixedName(String playerName) {
      String activeRole = (String)instance.role.getValue();

      return (Text)(switch (activeRole) {
         case "SRMOD" -> buildSrmodName(playerName);
         case "MEDIA" -> buildMediaName(playerName);
         case "SRADMIN" -> buildSradminName(playerName);
         default -> Text.literal(playerName);
      });
   }

   private static Text buildSrmodName(String playerName) {
      MutableText result = Text.empty();
      appendTag(result, "SR.MOD", roleStyle(5635925));
      result.append(Text.literal(playerName).setStyle(getRoleNameStyle()));
      return result;
   }

   private static Text buildMediaName(String playerName) {
      MutableText result = Text.empty();
      appendTag(result, "MEDIA", roleStyle(16733695));
      result.append(Text.literal(playerName).setStyle(getRoleNameStyle()));
      return result;
   }

   private static Text buildSradminName(String playerName) {
      MutableText result = Text.empty();
      appendTag(result, "SR.ADMIN", roleStyle(16733525));
      result.append(Text.literal(playerName).setStyle(getRoleNameStyle()));
      return result;
   }

   private static void appendTag(MutableText result, String roleText, Style roleStyle) {
      Style bracketStyle = Style.EMPTY.withColor(TextColor.fromRgb(8355711)).withBold(false);
      result.append(Text.literal("[").setStyle(bracketStyle));
      result.append(Text.literal(roleText).setStyle(roleStyle));
      result.append(Text.literal("] ").setStyle(bracketStyle));
   }

   private static Style roleStyle(int rgb) {
      return Style.EMPTY.withColor(TextColor.fromRgb(rgb)).withBold(true);
   }
}

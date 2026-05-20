package com.xenon.mixin;

import com.xenon.module.modules.donut.FakeRoles;
import com.xenon.module.modules.misc.NameProtect;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ChatHud.class)
public class ChatHudMixin {

    @ModifyVariable(method = "addMessage", 
                    at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private Text modifyChatMessage(Text message) {
        if (message != null && NameProtect.instance != null && NameProtect.instance.isEnabled()) {
            if (MinecraftClient.getInstance().getSession() != null) {
                String name = MinecraftClient.getInstance().getSession().getUsername();
                if (name != null) {
                    String str = message.getString();
                    if (str.contains(name)) {
                        // We replace the entire text with a literal string to replace the name
                        // A more advanced approach would walk the Text tree, but this works reliably for NameProtect in chat
                        message = Text.literal(str.replace(name, NameProtect.instance.getFakeName()));
                    }
                }
            }
        }
        return FakeRoles.modifyChatText(message);
    }
}

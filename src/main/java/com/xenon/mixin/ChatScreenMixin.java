package com.xenon.mixin;

import com.xenon.gui.HudEditor;
import com.xenon.module.modules.client.SpotifyHud;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatScreen.class)
public class ChatScreenMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void xenon$mouseClicked(Click click, boolean bl, CallbackInfoReturnable<Boolean> cir) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();
        if (button == 0) {
            if (SpotifyHud.handleClick(mouseX, mouseY)) {
                cir.setReturnValue(true);
                return;
            }
            if (HudEditor.INSTANCE.onMouseClick(mouseX, mouseY, button)) {
                cir.setReturnValue(true);
                return;
            }
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void xenon$render(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        HudEditor.INSTANCE.render(context, mouseX, mouseY);
    }
}

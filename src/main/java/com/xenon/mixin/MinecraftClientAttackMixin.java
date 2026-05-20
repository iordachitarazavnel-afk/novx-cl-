package com.xenon.mixin;

import com.xenon.module.modules.combat.SpearSwap;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MinecraftClientAttackMixin {

    @Inject(method = "handleInputEvents", at = @At("HEAD"), require = 0)
    private void xenon$preInput(CallbackInfo ci) {
        SpearSwap m = SpearSwap.INSTANCE;
        if (m == null || !m.isEnabled()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.options == null) return;
        if (mc.currentScreen != null) return;
        if (mc.options.attackKey.isPressed()) {
            m.preAttack();
        } else {
            m.noAttack();
        }
    }
}

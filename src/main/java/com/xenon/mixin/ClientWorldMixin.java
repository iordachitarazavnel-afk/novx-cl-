package com.xenon.mixin;

import com.xenon.module.modules.render.NoRender;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientWorld.class)
public class ClientWorldMixin {

    @Inject(method = "getLightningTicksLeft", at = @At("HEAD"), cancellable = true)
    private void xenon$hideLightningFlash(CallbackInfoReturnable<Integer> cir) {
        if (NoRender.hideThunder()) {
            cir.setReturnValue(0);
        }
    }
}

package com.xenon.mixin;

import com.xenon.module.modules.render.Freecam;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public class ClientPlayerEntityMixin {

    @Inject(method = "tickMovement", at = @At("HEAD"))
    private void onTickMovement(CallbackInfo ci) {
        if (Freecam.instance != null && Freecam.instance.isEnabled()) {
            ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
            self.setSneaking(false);
            self.setSprinting(false);
        }
    }
}

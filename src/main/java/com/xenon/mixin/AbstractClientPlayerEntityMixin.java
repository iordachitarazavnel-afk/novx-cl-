package com.xenon.mixin;

import com.xenon.module.modules.misc.SkinChanger;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.player.SkinTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayerEntity.class)
public abstract class AbstractClientPlayerEntityMixin {

    @Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
    private void xenon$overrideOwnSkin(CallbackInfoReturnable<SkinTextures> cir) {
        AbstractClientPlayerEntity self = (AbstractClientPlayerEntity) (Object) this;
        SkinTextures override = SkinChanger.getOverrideSkin(self.getUuid());
        if (override != null) {
            cir.setReturnValue(override);
        }
    }
}

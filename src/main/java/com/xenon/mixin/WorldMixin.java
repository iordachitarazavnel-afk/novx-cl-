package com.xenon.mixin;

import com.xenon.module.modules.render.NoRender;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
public class WorldMixin {

    @Inject(method = "getRainGradient", at = @At("HEAD"), cancellable = true)
    private void xenon$hideRainGradient(float delta, CallbackInfoReturnable<Float> cir) {
        if (NoRender.hideRainGradient()) {
            cir.setReturnValue(0.0f);
        }
    }

    @Inject(method = "getThunderGradient", at = @At("HEAD"), cancellable = true)
    private void xenon$hideThunderGradient(float delta, CallbackInfoReturnable<Float> cir) {
        if (NoRender.hideThunder()) {
            cir.setReturnValue(0.0f);
        }
    }

    @Inject(
            method = "playSoundClient(DDDLnet/minecraft/sound/SoundEvent;Lnet/minecraft/sound/SoundCategory;FFZ)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void xenon$cancelWeatherPointSound(
            double x,
            double y,
            double z,
            SoundEvent sound,
            SoundCategory category,
            float volume,
            float pitch,
            boolean useDistance,
            CallbackInfo ci
    ) {
        if (NoRender.shouldCancelWeatherSound(sound)) {
            ci.cancel();
        }
    }

    @Inject(
            method = "playSoundAtBlockCenterClient(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/sound/SoundEvent;Lnet/minecraft/sound/SoundCategory;FFZ)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void xenon$cancelWeatherBlockSound(
            net.minecraft.util.math.BlockPos pos,
            SoundEvent sound,
            SoundCategory category,
            float volume,
            float pitch,
            boolean useDistance,
            CallbackInfo ci
    ) {
        if (NoRender.shouldCancelWeatherSound(sound)) {
            ci.cancel();
        }
    }
}

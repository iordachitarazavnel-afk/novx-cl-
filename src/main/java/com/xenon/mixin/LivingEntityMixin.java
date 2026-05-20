package com.xenon.mixin;

import com.xenon.module.modules.misc.SwingSpeed;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectUtil;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public class LivingEntityMixin {

    @Inject(method = "getHandSwingDuration", at = @At("HEAD"), cancellable = true)
    private void onGetHandSwingDuration(CallbackInfoReturnable<Integer> cir) {
        SwingSpeed module = SwingSpeed.instance;
        if (module == null || !module.isEnabled()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || (Object) this != client.player) {
            return;
        }

        LivingEntity self = (LivingEntity) (Object) this;
        ItemStack stack = self.getStackInHand(Hand.MAIN_HAND);
        int baseDuration = stack.getSwingAnimation().duration();
        if (StatusEffectUtil.hasHaste(self)) {
            baseDuration -= 1 + StatusEffectUtil.getHasteAmplifier(self);
        } else if (self.hasStatusEffect(StatusEffects.MINING_FATIGUE)) {
            baseDuration += (1 + self.getStatusEffect(StatusEffects.MINING_FATIGUE).getAmplifier()) * 2;
        }

        float speed = module.getSwingSpeed();
        int adjustedDuration = Math.max(1, Math.round(baseDuration / speed));
        cir.setReturnValue(adjustedDuration);
    }
}

package com.xenon.mixin;

import com.xenon.module.modules.misc.NameTags;
import com.xenon.module.modules.combat.Hitbox;
import com.xenon.utils.NametagRenderState;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public class EntityRendererMixin {

    @Inject(method = "updateRenderState", at = @At("TAIL"))
    private void xenon$updateNametagState(Entity entity, EntityRenderState state, float tickDelta, CallbackInfo ci) {
        if (!(entity instanceof LivingEntity living) || !NameTags.isActive()) {
            NametagRenderState.clear(state);
            return;
        }

        NameTags module = NameTags.instance;
        if (module == null || !module.shouldRenderForState(living, state.squaredDistanceToCamera) || state.invisible) {
            NametagRenderState.clear(state);
            return;
        }

        NametagRenderState.mark(state);
    }

    @Inject(method = "renderLabelIfPresent", at = @At("HEAD"), cancellable = true)
    private void xenon$renderCustomNametag(
            EntityRenderState state,
            MatrixStack matrices,
            OrderedRenderCommandQueue queue,
            CameraRenderState cameraRenderState,
            CallbackInfo ci
    ) {
        if (!NametagRenderState.hasEntry(state)) {
            return;
        }

        ci.cancel();
    }

    @Inject(method = "getShadowRadius", at = @At("RETURN"), cancellable = true)
    private void xenon$hitbox(EntityRenderState state, CallbackInfoReturnable<Float> cir) {
        Hitbox module = Hitbox.INSTANCE;
        if (module != null && module.isEnabled()) {
            cir.setReturnValue(cir.getReturnValue() + module.size.getValue());
        }
    }
}

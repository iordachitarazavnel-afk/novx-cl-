package com.xenon.mixin;

import com.xenon.module.modules.misc.Freelook;
import com.xenon.module.modules.render.Freecam;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow protected abstract void setPos(double x, double y, double z);
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Shadow protected abstract float clipToSpace(float distance);
    @Shadow protected abstract void moveBy(float x, float y, float z);

    @Inject(method = "update", at = @At("TAIL"))
    private void onUpdate(World area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo ci) {
        if (Freecam.instance != null && Freecam.instance.isEnabled()) {
            Freecam.instance.updateCameraMovement();
            double x = Freecam.instance.getInterpolatedX(tickDelta);
            double y = Freecam.instance.getInterpolatedY(tickDelta);
            double z = Freecam.instance.getInterpolatedZ(tickDelta);
            float yaw = Freecam.instance.getInterpolatedYaw(tickDelta);
            float pitch = Freecam.instance.getInterpolatedPitch(tickDelta);
            
            this.setPos(x, y, z);
            this.setRotation(yaw, pitch);
            return;
        }

        Freelook module = Freelook.instance;
        if (module != null && module.isCameraActive() && focusedEntity != null) {
            Vec3d eyePos = focusedEntity.getCameraPosVec(tickDelta);
            this.setRotation(module.getCameraYaw(), module.getCameraPitch());
            this.setPos(eyePos.x, eyePos.y, eyePos.z);

            float distance = module.getDistance();
            if (!module.shouldWallClip()) {
                distance = this.clipToSpace(distance);
            }

            this.moveBy(-distance, 0.0f, 0.0f);
        }
    }

    @Inject(method = "isThirdPerson", at = @At("HEAD"), cancellable = true)
    private void onIsThirdPerson(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (Freecam.instance != null && Freecam.instance.isEnabled()) {
            cir.setReturnValue(true);
            return;
        }

        if (Freelook.instance != null && Freelook.instance.isCameraActive()) {
            cir.setReturnValue(true);
        }
    }
}

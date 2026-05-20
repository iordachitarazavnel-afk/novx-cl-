package com.xenon.mixin;

import com.xenon.module.modules.render.Freecam;
import com.xenon.module.modules.misc.Freelook;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public class EntityMixin {

    @Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
    private void onChangeLookDirection(double cursorDeltaX, double cursorDeltaY, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self != MinecraftClient.getInstance().player) {
            return;
        }

        if (Freecam.instance != null && Freecam.instance.isEnabled()) {
            Freecam.instance.updateRotation(cursorDeltaX * 0.15D * Freecam.instance.getLookSensitivity(), cursorDeltaY * 0.15D * Freecam.instance.getLookSensitivity());
            ci.cancel();
            return;
        }

        if (Freelook.instance != null && Freelook.instance.isCameraActive()) {
            Freelook.instance.consumeMouseDelta(cursorDeltaX, cursorDeltaY);
            ci.cancel();
        }
    }

    @Inject(method = "isSneaking", at = @At("HEAD"), cancellable = true)
    private void onIsSneaking(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (Freecam.instance != null && Freecam.instance.isEnabled()) {
            if ((Object) this == MinecraftClient.getInstance().player) {
                cir.setReturnValue(false);
            }
        }
    }

    @Inject(method = "shouldRender(D)Z", at = @At("HEAD"), cancellable = true)
    private void onShouldRender(double distance, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (Freecam.instance != null && Freecam.instance.isEnabled()) {
            // 10 chunks = 160 blocks. Squared: 25600.
            if (distance < 25600.0D) {
                cir.setReturnValue(true);
            }
        }
    }

    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void onGetDisplayName(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.text.Text> cir) {
        if (com.xenon.module.modules.donut.FakeRoles.isActive()) {
            net.minecraft.text.Text modified = com.xenon.module.modules.donut.FakeRoles.modifyChatText(cir.getReturnValue());
            if (modified != cir.getReturnValue()) {
                cir.setReturnValue(modified);
                return;
            }
        }

        if (com.xenon.module.modules.misc.NameProtect.instance != null && com.xenon.module.modules.misc.NameProtect.instance.isEnabled()) {
            if (MinecraftClient.getInstance().getSession() != null) {
                String name = MinecraftClient.getInstance().getSession().getUsername();
                if (name != null) {
                    net.minecraft.text.Text returned = cir.getReturnValue();
                    if (returned != null) {
                        String str = returned.getString();
                        if (str.contains(name)) {
                            cir.setReturnValue(net.minecraft.text.Text.literal(str.replace(name, com.xenon.module.modules.misc.NameProtect.instance.getFakeName())));
                        }
                    }
                }
            }
        }
    }

    @Inject(method = "getName", at = @At("RETURN"), cancellable = true)
    private void onGetName(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.text.Text> cir) {
        if (com.xenon.module.modules.donut.FakeRoles.isActive()) {
            net.minecraft.text.Text modified = com.xenon.module.modules.donut.FakeRoles.modifyChatText(cir.getReturnValue());
            if (modified != cir.getReturnValue()) {
                cir.setReturnValue(modified);
                return;
            }
        }

        if (com.xenon.module.modules.misc.NameProtect.instance != null && com.xenon.module.modules.misc.NameProtect.instance.isEnabled()) {
            if (MinecraftClient.getInstance().getSession() != null) {
                String name = MinecraftClient.getInstance().getSession().getUsername();
                if (name != null) {
                    net.minecraft.text.Text returned = cir.getReturnValue();
                    if (returned != null) {
                        String str = returned.getString();
                        if (str.contains(name)) {
                            cir.setReturnValue(net.minecraft.text.Text.literal(str.replace(name, com.xenon.module.modules.misc.NameProtect.instance.getFakeName())));
                        }
                    }
                }
            }
        }
    }
}

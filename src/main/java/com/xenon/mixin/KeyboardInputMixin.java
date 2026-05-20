package com.xenon.mixin;

import com.xenon.module.modules.render.Freecam;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public class KeyboardInputMixin {

    @Inject(method = "tick()V", at = @At("RETURN"))
    private void onTickReturn(CallbackInfo ci) {
        if (Freecam.instance != null && Freecam.instance.isEnabled()) {
            InputAccessor input = (InputAccessor) this;
            input.xenon$setPlayerInput(new PlayerInput(false, false, false, false, false, false, false));
            input.xenon$setMovementVector(Vec2f.ZERO);
        }
    }
}

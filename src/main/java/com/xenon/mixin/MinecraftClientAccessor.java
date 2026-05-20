package com.xenon.mixin;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MinecraftClient.class)
public interface MinecraftClientAccessor {
    @Accessor("itemUseCooldown")
    int xenon$getItemUseCooldown();

    @Accessor("itemUseCooldown")
    void xenon$setItemUseCooldown(int cooldown);
}

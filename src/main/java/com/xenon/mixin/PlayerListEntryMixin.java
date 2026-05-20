package com.xenon.mixin;

import com.mojang.authlib.GameProfile;
import com.xenon.module.modules.donut.FakeRoles;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerListEntry.class)
public abstract class PlayerListEntryMixin {

    @Shadow
    public abstract GameProfile getProfile();

    @Inject(method = "getSkinTextures", at = @At("HEAD"), cancellable = true)
    private void xenon$overrideListEntrySkin(CallbackInfoReturnable<SkinTextures> cir) {
        GameProfile profile = getProfile();
        if (profile == null || profile.id() == null) {
            return;
        }
    } // <-- Această acoladă lipsea pentru a închide prima metodă!

    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void xenon$fakeRoleDisplayName(CallbackInfoReturnable<Text> cir) {
        if (!FakeRoles.isActive()) {
            return;
        }

        GameProfile profile = getProfile();
        MinecraftClient client = MinecraftClient.getInstance();
        if (profile == null || profile.id() == null || client == null || client.player == null) {
            return;
        }

        if (!profile.id().equals(client.player.getUuid())) {
            return;
        }

        Text prefixed = FakeRoles.buildPrefixedDisplayName(profile.name());
        if (prefixed != null) {
            cir.setReturnValue(prefixed);
        }
    }
}

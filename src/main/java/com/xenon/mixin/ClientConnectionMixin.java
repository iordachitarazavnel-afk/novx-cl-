package com.xenon.mixin;

import com.xenon.module.ModuleManager;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.listener.PacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConnection.class)
public class ClientConnectionMixin {
    @Inject(method = "handlePacket", at = @At("HEAD"))
    private static void onHandlePacket(Packet<?> packet, PacketListener listener, CallbackInfo ci) {
        try {
            ModuleManager.INSTANCE.onPacketReceive(packet);
        } catch (Exception e) {}
    }

    @Inject(
            method = "send(Lnet/minecraft/network/packet/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onSend(Packet<?> packet, ChannelFutureListener callbacks, boolean flush, CallbackInfo ci) {
        try {
            if (ModuleManager.INSTANCE.onPacketSend(packet)) {
                ci.cancel();
            }
        } catch (Exception ignored) {
        }
    }
}

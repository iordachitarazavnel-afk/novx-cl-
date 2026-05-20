package com.xenon.mixin;

import com.xenon.utils.FakeRolesUtil;
import com.xenon.utils.NameProtectUtil;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(TextRenderer.class)
public class TextRendererMixin {

    @ModifyVariable(
            method = "prepare(Ljava/lang/String;FFIZI)Lnet/minecraft/client/font/TextRenderer$GlyphDrawable;",
            at = @At("HEAD"),
            ordinal = 0,
            argsOnly = true
    )
    private String xenon$replacePreparedString(String text) {
        return FakeRolesUtil.replace(NameProtectUtil.replace(text));
    }

    @ModifyVariable(
            method = "prepare(Lnet/minecraft/text/OrderedText;FFIZZI)Lnet/minecraft/client/font/TextRenderer$GlyphDrawable;",
            at = @At("HEAD"),
            ordinal = 0,
            argsOnly = true
    )
    private OrderedText xenon$replacePreparedOrderedText(OrderedText orderedText) {
        return FakeRolesUtil.replace(NameProtectUtil.replace(orderedText));
    }

    @ModifyVariable(
            method = "drawWithOutline(Lnet/minecraft/text/OrderedText;FFIILorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At("HEAD"),
            ordinal = 0,
            argsOnly = true
    )
    private OrderedText xenon$replaceOutlinedOrderedText(OrderedText orderedText) {
        return FakeRolesUtil.replace(NameProtectUtil.replace(orderedText));
    }

    @ModifyVariable(
            method = "getWidth(Ljava/lang/String;)I",
            at = @At("HEAD"),
            ordinal = 0,
            argsOnly = true
    )
    private String xenon$replaceWidthString(String text) {
        return FakeRolesUtil.replace(NameProtectUtil.replace(text));
    }

    @ModifyVariable(
            method = "getWidth(Lnet/minecraft/text/StringVisitable;)I",
            at = @At("HEAD"),
            ordinal = 0,
            argsOnly = true
    )
    private StringVisitable xenon$replaceWidthVisitable(StringVisitable visitable) {
        return FakeRolesUtil.replace(NameProtectUtil.replace(visitable));
    }

    @ModifyVariable(
            method = "getWidth(Lnet/minecraft/text/OrderedText;)I",
            at = @At("HEAD"),
            ordinal = 0,
            argsOnly = true
    )
    private OrderedText xenon$replaceWidthOrderedText(OrderedText orderedText) {
        return FakeRolesUtil.replace(NameProtectUtil.replace(orderedText));
    }
}

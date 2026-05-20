package com.xenon.mixin;

import com.xenon.utils.NametagRenderState;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.command.LabelCommandRenderer;
import net.minecraft.text.Text;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LabelCommandRenderer.class)
public class LabelCommandRendererMixin {

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/font/TextRenderer;draw(Lnet/minecraft/text/Text;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/font/TextRenderer$TextLayerType;II)V",
                    ordinal = 1
            )
    )
    private void xenon$drawHealthLabelsWithOutline(
            TextRenderer textRenderer,
            Text text,
            float x,
            float y,
            int color,
            boolean shadow,
            Matrix4f matrix,
            VertexConsumerProvider consumers,
            TextRenderer.TextLayerType layerType,
            int backgroundColor,
            int light
    ) {
        if (!NametagRenderState.isOutlinedLabel(text)) {
            textRenderer.draw(text, x, y, color, shadow, matrix, consumers, layerType, backgroundColor, light);
            return;
        }

        textRenderer.drawWithOutline(text.asOrderedText(), x, y, color, 0xFF000000, matrix, consumers, light);
    }
}

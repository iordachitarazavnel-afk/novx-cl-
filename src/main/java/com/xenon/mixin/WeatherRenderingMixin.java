package com.xenon.mixin;

import com.xenon.module.modules.render.NoRender;
import net.minecraft.client.render.WeatherRendering;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(WeatherRendering.class)
public abstract class WeatherRenderingMixin {

    @Invoker("getPrecipitationAt")
    protected abstract Biome.Precipitation xenon$getPrecipitationAt(World world, BlockPos pos);

    @Redirect(
            method = "buildPrecipitationPieces",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/WeatherRendering;getPrecipitationAt(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/world/biome/Biome$Precipitation;"
            )
    )
    private Biome.Precipitation xenon$filterRenderedPrecipitation(WeatherRendering instance, World world, BlockPos pos) {
        return NoRender.filterPrecipitation(xenon$getPrecipitationAt(world, pos));
    }

    @Redirect(
            method = "addParticlesAndSound",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/WeatherRendering;getPrecipitationAt(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/world/biome/Biome$Precipitation;"
            )
    )
    private Biome.Precipitation xenon$filterWeatherParticlesAndSounds(WeatherRendering instance, World world, BlockPos pos) {
        return NoRender.filterPrecipitation(xenon$getPrecipitationAt(world, pos));
    }
}

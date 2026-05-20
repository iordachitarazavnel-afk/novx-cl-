package com.xenon.module.modules.render;

import com.xenon.module.Category;
import com.xenon.module.Module;
import com.xenon.setting.Setting;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.biome.Biome;

public final class NoRender extends Module {

    public static NoRender instance;

    private final Setting<Boolean> rain = new Setting<>("Rain", true);
    private final Setting<Boolean> snow = new Setting<>("Snow", true);
    private final Setting<Boolean> thunder = new Setting<>("Thunder", true);

    public NoRender() {
        super("NoRender", Category.RENDER);
        instance = this;
        addSetting(rain);
        addSetting(snow);
        addSetting(thunder);
    }

    public static boolean isActive() {
        return instance != null && instance.isEnabled() && mc != null && mc.world != null;
    }

    public static boolean hideRain() {
        return isActive() && instance.rain.getValue();
    }

    public static boolean hideSnow() {
        return isActive() && instance.snow.getValue();
    }

    public static boolean hideThunder() {
        return isActive() && instance.thunder.getValue();
    }

    public static boolean hideAllPrecipitation() {
        return hideRain() && hideSnow();
    }

    public static boolean hideRainGradient() {
        return hideAllPrecipitation();
    }

    public static Biome.Precipitation filterPrecipitation(Biome.Precipitation precipitation) {
        if (!isActive() || precipitation == null) {
            return precipitation;
        }

        if (precipitation == Biome.Precipitation.RAIN && hideRain()) {
            return Biome.Precipitation.NONE;
        }

        if (precipitation == Biome.Precipitation.SNOW && hideSnow()) {
            return Biome.Precipitation.NONE;
        }

        return precipitation;
    }

    public static boolean shouldCancelWeatherSound(SoundEvent soundEvent) {
        if (!isActive() || soundEvent == null) {
            return false;
        }

        if (hideRain() && (soundEvent == SoundEvents.WEATHER_RAIN || soundEvent == SoundEvents.WEATHER_RAIN_ABOVE)) {
            return true;
        }

        return hideThunder() && (soundEvent == SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER
                || soundEvent == SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT);
    }
}

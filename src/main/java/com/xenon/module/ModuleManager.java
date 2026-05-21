package com.xenon.module;

import com.xenon.module.modules.StubModule;
import com.xenon.module.modules.client.CloudConfigs;
import com.xenon.module.modules.client.Friends;
import com.xenon.module.modules.client.Hud;
import com.xenon.module.modules.client.SpotifyHud;
import com.xenon.module.modules.client.Themes;
import com.xenon.module.modules.client.XenonPlus;
import com.xenon.module.modules.combat.AnchorMacro;
import com.xenon.module.modules.combat.AutoCrystal;
import com.xenon.module.modules.combat.AutoHitCrystal;
import com.xenon.module.modules.combat.AutoDoubleHand;
import com.xenon.module.modules.combat.AutoInvTotem;
import com.xenon.module.modules.combat.AutoTotem;
import com.xenon.module.modules.combat.DoubleAnchor;
import com.xenon.module.modules.combat.Hitbox;
import com.xenon.module.modules.combat.HoverTotem;
import com.xenon.module.modules.combat.ShieldBreaker;
import com.xenon.module.modules.combat.SpearSwap;
import com.xenon.module.modules.combat.Triggerbot;
import com.xenon.module.modules.donut.ActivityDebug;
import com.xenon.module.modules.donut.ChunkFinderV2;
import com.xenon.module.modules.donut.PrimeChunkFinder;
import com.xenon.module.modules.donut.SuspiciousChunkFinder;
import com.xenon.module.modules.donut.BoneDropperBot;
import com.xenon.module.modules.donut.ChunkFinder;
import com.xenon.module.modules.donut.AntiTrap;
import com.xenon.module.modules.donut.FakeRoles;
import com.xenon.module.modules.donut.GrowthFinder;
import com.xenon.module.modules.donut.FakeStats;
import com.xenon.module.modules.donut.SpawnerProtect;
import com.xenon.module.modules.misc.AutoLog;
import com.xenon.module.modules.misc.AutoTool;
import com.xenon.module.modules.misc.CoordSnapper;
import com.xenon.module.modules.misc.FastPlace;
import com.xenon.module.modules.misc.Freelook;
import com.xenon.module.modules.misc.HomeSetter;
import com.xenon.module.modules.misc.NameProtect;
import com.xenon.module.modules.misc.NameTags;
import com.xenon.module.modules.misc.Sprint;
import com.xenon.module.modules.misc.SwingSpeed;
import com.xenon.module.modules.misc.TabDetector;
import com.xenon.module.modules.misc.WeatherNotifier;
import com.xenon.module.modules.render.AmethystESP;
import com.xenon.module.modules.render.BlockESP;
import com.xenon.module.modules.render.Freecam;
import com.xenon.module.modules.render.FullBright;
import com.xenon.module.modules.render.HoleESP;
import com.xenon.module.modules.render.JumpCircles;
import com.xenon.module.modules.render.LightDebug;
import com.xenon.module.modules.render.MobESP;
import com.xenon.module.modules.render.NoRender;
import com.xenon.module.modules.render.PlayerESP;
import com.xenon.module.modules.render.StorageESP;
import com.xenon.setting.BlocksSetting;
import com.xenon.setting.Setting;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.awt.Color;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ModuleManager {
    private static final String CONFIG_HEADER = "XENON_CONFIG_V2";
    private static final String MODULE_PREFIX = "MODULE";
    private static final String SETTING_PREFIX = "SETTING";
    private static final String HUDPOS_PREFIX = "HUDPOS";

    public static final ModuleManager INSTANCE = new ModuleManager();

    private final List<Module> modules = new ArrayList<>();
    private boolean initialized = false;
    private boolean loadingConfig = false;

    public void init() {
        if (initialized) {
            return;
        }

        modules.add(new XenonPlus());
        modules.add(new Themes());
        modules.add(new Hud());
        modules.add(new SpotifyHud());
        modules.add(new Friends());
        modules.add(new CloudConfigs());

        modules.add(new PlayerESP());
        modules.add(new BlockESP());
        modules.add(new AmethystESP());
        modules.add(new MobESP());
        modules.add(new StorageESP());
        modules.add(new Freecam());
        modules.add(new LightDebug());
        modules.add(new HoleESP());
        modules.add(new FullBright());
        modules.add(new NoRender());
        modules.add(new JumpCircles());
        modules.add(new StubModule("Elytra Swap", Category.COMBAT));
        modules.add(new StubModule("Mace Swap", Category.COMBAT));
        modules.add(new AutoTotem());
        modules.add(new HoverTotem());
        modules.add(new AutoInvTotem());
        modules.add(new Hitbox());
        modules.add(new AnchorMacro());
        modules.add(new AutoCrystal());
        // modules.add(new AutoHitCrystal());
        modules.add(new DoubleAnchor());
        modules.add(new Triggerbot());
        modules.add(new ShieldBreaker());
        modules.add(new AutoDoubleHand());
        modules.add(new SpearSwap());

        modules.add(new NameProtect());
        modules.add(new NameTags());
        modules.add(new CoordSnapper());
        modules.add(new HomeSetter());
        modules.add(new SwingSpeed());
        modules.add(new Freelook());
        modules.add(new FastPlace());
        modules.add(new AutoTool());
        modules.add(new AutoLog());
        modules.add(new Sprint());
        modules.add(new TabDetector());
        modules.add(new WeatherNotifier());

        modules.add(new ActivityDebug());
        modules.add(new ChunkFinderV2());
        modules.add(new PrimeChunkFinder());
        modules.add(new SuspiciousChunkFinder());
        modules.add(new FakeStats());
        modules.add(new FakeRoles());
        modules.add(new ChunkFinder());
        modules.add(new GrowthFinder());
        modules.add(new AntiTrap());
        modules.add(new BoneDropperBot());
        modules.add(new SpawnerProtect());

        initialized = true;
        loadConfig();
    }

    public void onSettingChanged() {
        if (!initialized || loadingConfig) {
            return;
        }
        saveConfig();
    }

    public void saveConfig() {
        if (!initialized || loadingConfig) {
            return;
        }

        Path configFile = getConfigPath();
        try {
            Files.createDirectories(configFile.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(configFile, StandardCharsets.UTF_8)) {
                writer.write(CONFIG_HEADER);
                writer.newLine();

                for (Module module : modules) {
                    writer.write(MODULE_PREFIX);
                    writer.write('\t');
                    writer.write(encode(module.getName()));
                    writer.write('\t');
                    writer.write(Integer.toString(module.getBind()));
                    writer.write('\t');
                    int activationKey = module instanceof ActivatableModule activatableModule
                            ? activatableModule.getActivationKey()
                            : 0;
                    writer.write(Integer.toString(activationKey));
                    writer.write('\t');
                    writer.write(Boolean.toString(module.isEnabled()));
                    writer.newLine();

                    for (Setting<?> setting : module.getSettings()) {
                        String serialized = serializeSettingValue(setting);
                        if (serialized == null) {
                            continue;
                        }

                        writer.write(SETTING_PREFIX);
                        writer.write('\t');
                        writer.write(encode(module.getName()));
                        writer.write('\t');
                        writer.write(encode(setting.getName()));
                        writer.write('\t');
                        writer.write(encode(serialized));
                        writer.newLine();
                    }
                }

                for (com.xenon.module.modules.client.Hud.HudElement el : com.xenon.module.modules.client.Hud.HudElement.values()) {
                    int[] pos = com.xenon.module.modules.client.Hud.getElementPos(el);
                    writer.write(HUDPOS_PREFIX);
                    writer.write('\t');
                    writer.write(el.name());
                    writer.write('\t');
                    writer.write(Integer.toString(pos[0]));
                    writer.write('\t');
                    writer.write(Integer.toString(pos[1]));
                    writer.newLine();
                }
            }
        } catch (IOException ignored) {
        }
    }

    public void loadConfig() {
        Path configFile = getConfigPath();
        if (!Files.exists(configFile)) {
            return;
        }

        loadingConfig = true;
        try {
            List<String> lines = Files.readAllLines(configFile, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line == null || line.isBlank() || CONFIG_HEADER.equals(line)) {
                    continue;
                }

                if (line.startsWith(MODULE_PREFIX + "\t")) {
                    loadModuleLine(line);
                    continue;
                }

                if (line.startsWith(SETTING_PREFIX + "\t")) {
                    loadSettingLine(line);
                    continue;
                }

                if (line.startsWith(HUDPOS_PREFIX + "\t")) {
                    loadHudPosLine(line);
                    continue;
                }

                loadLegacyModuleLine(line);
            }
        } catch (IOException ignored) {
        } finally {
            loadingConfig = false;
        }
    }

    private void loadHudPosLine(String line) {
        try {
            String[] parts = line.split("\t");
            if (parts.length < 4) return;
            com.xenon.module.modules.client.Hud.HudElement el = com.xenon.module.modules.client.Hud.HudElement.valueOf(parts[1]);
            int x = Integer.parseInt(parts[2]);
            int y = Integer.parseInt(parts[3]);
            com.xenon.module.modules.client.Hud.setElementPos(el, x, y);
        } catch (Exception ignored) {
        }
    }

    public boolean isLoadingConfig() {
        return loadingConfig;
    }

    public List<Module> getModules() {
        return modules;
    }

    public List<Module> getModulesInCategory(Category category) {
        List<Module> categoryModules = new ArrayList<>();
        for (Module module : modules) {
            if (module.getCategory() == category) {
                categoryModules.add(module);
            }
        }
        return categoryModules;
    }

    public Module getModuleByName(String name) {
        for (Module module : modules) {
            if (module.getName().equalsIgnoreCase(name)) {
                return module;
            }
        }
        return null;
    }

    public void onTick() {
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.onTick();
            }
        }
    }

    public void onRender(MatrixStack matrices, float tickDelta) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.onRender(matrices, tickDelta);
            }
        }
    }

    public void onPacketReceive(net.minecraft.network.packet.Packet<?> packet) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.onPacketReceive(packet);
            }
        }
    }

    public boolean onPacketSend(net.minecraft.network.packet.Packet<?> packet) {
        boolean cancel = false;
        for (Module module : modules) {
            if (!module.isEnabled()) {
                continue;
            }
            try {
                cancel |= module.onPacketSend(packet);
            } catch (Exception ignored) {
            }
        }
        return cancel;
    }

    private Path getConfigPath() {
        return MinecraftClient.getInstance().runDirectory.toPath().resolve("xenon_config.txt");
    }

    private void loadModuleLine(String line) {
        String[] parts = line.split("\t", 5);
        if (parts.length < 5) {
            return;
        }

        Module module = getModuleByName(decode(parts[1]));
        if (module == null) {
            return;
        }

        try {
            module.applyBind(Integer.parseInt(parts[2]));
            if (module instanceof ActivatableModule activatableModule) {
                activatableModule.applyActivationKey(Integer.parseInt(parts[3]));
            }
            module.applyEnabled(Boolean.parseBoolean(parts[4]));
        } catch (Exception ignored) {
        }
    }

    private void loadSettingLine(String line) {
        String[] parts = line.split("\t", 4);
        if (parts.length < 4) {
            return;
        }

        Module module = getModuleByName(decode(parts[1]));
        if (module == null) {
            return;
        }

        Setting<?> setting = getSettingByName(module, decode(parts[2]));
        if (setting == null) {
            return;
        }

        applySettingValue(setting, decode(parts[3]));
    }

    private void loadLegacyModuleLine(String line) {
        String[] parts = line.split(":", 4);
        if (parts.length < 2) {
            return;
        }

        Module module = getModuleByName(parts[0]);
        if (module == null) {
            return;
        }

        try {
            if (parts.length >= 2) {
                module.applyBind(Integer.parseInt(parts[1]));
            }
            if (parts.length >= 3) {
                if (module instanceof ActivatableModule activatableModule) {
                    activatableModule.applyActivationKey(Integer.parseInt(parts[2]));
                }
            }
            if (parts.length >= 4) {
                module.applyEnabled(Boolean.parseBoolean(parts[3]));
            }
        } catch (Exception ignored) {
        }
    }

    private Setting<?> getSettingByName(Module module, String settingName) {
        for (Setting<?> setting : module.getSettings()) {
            if (setting.matchesName(settingName)) {
                return setting;
            }
        }
        return null;
    }

    private String serializeSettingValue(Setting<?> setting) {
        Object value = setting.getValue();
        if (setting instanceof BlocksSetting blocksSetting) {
            return serializeBlocks(blocksSetting);
        }
        if (value instanceof Boolean boolValue) {
            return Boolean.toString(boolValue);
        }
        if (value instanceof Float floatValue) {
            return Float.toString(floatValue);
        }
        if (value instanceof Integer intValue) {
            return Integer.toString(intValue);
        }
        if (value instanceof Double doubleValue) {
            return Double.toString(doubleValue);
        }
        if (value instanceof String stringValue) {
            return stringValue;
        }
        if (value instanceof Color colorValue) {
            return colorValue.getRed() + "," + colorValue.getGreen() + "," + colorValue.getBlue() + "," + colorValue.getAlpha();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void applySettingValue(Setting<?> setting, String serialized) {
        Object value = setting.getValue();

        try {
            if (setting instanceof BlocksSetting blocksSetting) {
                blocksSetting.setValue(deserializeBlocks(serialized));
                return;
            }
            if (value instanceof Boolean) {
                ((Setting<Boolean>) setting).setValue(Boolean.parseBoolean(serialized));
                return;
            }
            if (value instanceof Float) {
                ((Setting<Float>) setting).setValue(Float.parseFloat(serialized));
                return;
            }
            if (value instanceof Integer) {
                int parsed = Math.round(Float.parseFloat(serialized));
                ((Setting<Integer>) setting).setValue(parsed);
                return;
            }
            if (value instanceof Double) {
                ((Setting<Double>) setting).setValue(Double.parseDouble(serialized));
                return;
            }
            if (value instanceof String) {
                ((Setting<String>) setting).setValue(serialized);
                return;
            }
            if (value instanceof Color) {
                String[] colorParts = serialized.split(",", 4);
                if (colorParts.length == 4) {
                    Color color = new Color(
                            Integer.parseInt(colorParts[0]),
                            Integer.parseInt(colorParts[1]),
                            Integer.parseInt(colorParts[2]),
                            Integer.parseInt(colorParts[3])
                    );
                    ((Setting<Color>) setting).setValue(color);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private String serializeBlocks(BlocksSetting setting) {
        StringBuilder builder = new StringBuilder();
        for (Block block : setting.getSelectedBlocks()) {
            Identifier id = Registries.BLOCK.getId(block);
            if (id == null) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(',');
            }
            builder.append(id);
        }
        return builder.toString();
    }

    private Set<Block> deserializeBlocks(String serialized) {
        LinkedHashSet<Block> blocks = new LinkedHashSet<>();
        if (serialized == null || serialized.isBlank()) {
            return blocks;
        }

        for (String rawId : serialized.split(",")) {
            String blockId = rawId.trim();
            if (blockId.isEmpty()) {
                continue;
            }

            Identifier identifier = Identifier.tryParse(blockId);
            if (identifier == null) {
                continue;
            }

            Block block = Registries.BLOCK.get(identifier);
            if (block != null) {
                blocks.add(block);
            }
        }

        return blocks;
    }

    private String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decode(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return value;
        }
    }
}

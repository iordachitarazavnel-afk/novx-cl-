package com.xenon.module.modules.misc;

import com.xenon.module.Category;
import com.xenon.module.ActivatableModule;
import com.xenon.setting.ModeSetting;
import com.xenon.setting.Setting;
import net.minecraft.client.option.Perspective;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

public final class Freelook extends ActivatableModule {
    private static final float MIN_DISTANCE = 1.0f;
    private static final float MAX_DISTANCE = 15.0f;
    private static final float MIN_SENSITIVITY = 0.1f;
    private static final float MAX_SENSITIVITY = 3.0f;

    public static Freelook instance;

    private final ModeSetting activationMode = new ModeSetting("Mode", "Hold", new String[]{"Activation Mode"}, "Hold", "Toggle");
    private final Setting<Float> distance = new Setting<>("Distance", 4.0f, MIN_DISTANCE, MAX_DISTANCE);
    private final Setting<Boolean> wallClip = new Setting<>("Wall Clip", true);
    private final Setting<Float> sensitivity = new Setting<>("Sensitivity", 1.0f, MIN_SENSITIVITY, MAX_SENSITIVITY);
    private final Setting<Boolean> invertY = new Setting<>("Invert Y", false);

    private boolean active;
    private Perspective savedPerspective;
    private boolean savedChunkCullingEnabled;
    private float cameraYaw;
    private float cameraPitch;


    public Freelook() {
        super("FreeLook", Category.MISC);
        instance = this;
        addSetting(activationMode);
        addSetting(distance);
        addSetting(wallClip);
        addSetting(sensitivity);
        addSetting(invertY);
    }

    @Override
    public void onEnable() {
        active = false;
        savedPerspective = null;
    }

    @Override
    public void onDisable() {
        deactivateCamera();
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.options == null || mc.getWindow() == null) {
            deactivateCamera();
            return;
        }

        if (isHoldMode()) {
            int key = getActivationKey();
            boolean pressed = key != 0 && GLFW.glfwGetKey(mc.getWindow().getHandle(), key) == GLFW.GLFW_PRESS;
            if (pressed) {
                activateCamera();
            } else {
                deactivateCamera();
            }
        } else if (active) {
            ensureCameraState();
        }
    }

    @Override
    public void onActivationKeyPressed() {
        if (!isEnabled() || isHoldMode()) {
            return;
        }

        if (active) {
            deactivateCamera();
        } else {
            activateCamera();
        }
    }

    public boolean isCameraActive() {
        return isEnabled() && active && mc.player != null;
    }

    public void consumeMouseDelta(double deltaX, double deltaY) {
        if (!isCameraActive()) {
            return;
        }

        double pitchSign = invertY.getValue() ? -1.0D : 1.0D;
        double multiplier = 0.15D * getSensitivity();
        cameraYaw = MathHelper.wrapDegrees(cameraYaw + (float) (deltaX * multiplier));
        cameraPitch = MathHelper.clamp(cameraPitch + (float) (deltaY * multiplier * pitchSign), -90.0f, 90.0f);
    }

    public float getCameraYaw() {
        return cameraYaw;
    }

    public float getCameraPitch() {
        return cameraPitch;
    }

    public float getDistance() {
        return MathHelper.clamp(distance.getValue(), MIN_DISTANCE, MAX_DISTANCE);
    }

    public boolean shouldWallClip() {
        return wallClip.getValue();
    }

    public float getSensitivity() {
        return MathHelper.clamp(sensitivity.getValue(), MIN_SENSITIVITY, MAX_SENSITIVITY);
    }

    private boolean isHoldMode() {
        return activationMode.is("Hold");
    }

    private void activateCamera() {
        if (active || mc.player == null || mc.options == null) {
            ensureCameraState();
            return;
        }

        savedPerspective = mc.options.getPerspective();
        savedChunkCullingEnabled = mc.chunkCullingEnabled;
        mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);
        mc.chunkCullingEnabled = false;
        cameraYaw = mc.player.getYaw();
        cameraPitch = mc.player.getPitch();
        active = true;
    }

    private void ensureCameraState() {
        if (!active || mc.options == null) {
            return;
        }

        if (!mc.options.getPerspective().isFrontView() && !mc.options.getPerspective().isFirstPerson()) {
            return;
        }

        mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);
        mc.chunkCullingEnabled = false;
    }

    private void deactivateCamera() {
        if (!active) {
            return;
        }

        active = false;
        if (mc.options != null) {
            mc.options.setPerspective(savedPerspective == null ? Perspective.FIRST_PERSON : savedPerspective);
        }
        mc.chunkCullingEnabled = savedChunkCullingEnabled;
    }
}

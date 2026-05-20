package com.xenon.module.modules.misc;

import com.xenon.module.Category;
import com.xenon.module.Module;

/**
 * Automatically holds the sprint key. Also disables the vanilla "Sprint Toggle" option while enabled
 * (via reflection for mapping/version compatibility).
 */
public final class Sprint extends Module {

    private boolean hadSprintToggled = false;

    public Sprint() {
        super("Sprint", Category.MISC);
    }

    @Override
    public void onEnable() {
        if (mc == null || mc.options == null) {
            return;
        }
        hadSprintToggled = getSprintToggledOption();
        setSprintToggledOption(false);
    }

    @Override
    public void onDisable() {
        if (mc == null || mc.options == null) {
            return;
        }
        setSprintToggledOption(hadSprintToggled);
        try {
            mc.options.sprintKey.setPressed(false);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onTick() {
        if (mc == null || mc.player == null || mc.options == null) {
            return;
        }

        // Ensure vanilla sprint-toggled doesn't fight us.
        setSprintToggledOption(false);
        try {
            mc.options.sprintKey.setPressed(true);
        } catch (Throwable ignored) {}
    }

    private boolean getSprintToggledOption() {
        try {
            Object opt = mc.options.getClass().getMethod("getSprintToggled").invoke(mc.options);
            if (opt == null) return false;
            Object v = opt.getClass().getMethod("getValue").invoke(opt);
            return v instanceof Boolean b && b;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void setSprintToggledOption(boolean value) {
        try {
            Object opt = mc.options.getClass().getMethod("getSprintToggled").invoke(mc.options);
            if (opt == null) return;
            opt.getClass().getMethod("setValue", Object.class).invoke(opt, Boolean.valueOf(value));
        } catch (Throwable ignored) {
            // Some versions/mappings may not expose this option; holding sprintKey still works.
        }
    }
}


package com.moulberry.flashback.utils;

import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Helpers for running on Android/iOS launchers (PojavLauncher, Zalith, FCL, ...) where GLFW is a stub and
 * OpenGL is provided by a translation layer such as MobileGlues, gl4es or ANGLE.
 * <p>
 * Force on/off with {@code -Dflashback.mobile=true|false}.
 */
public final class MobileCompat {

    private static final boolean MOBILE = detect();

    // Cached cursor state so we don't spam the launcher's GLFW stub. Some stubs recenter the cursor every time
    // glfwSetInputMode/glfwSetCursor is called, even when the value doesn't change.
    private static final Map<Long, Integer> cursorModes = new HashMap<>();
    private static final Map<Long, Long> cursorShapes = new HashMap<>();

    private MobileCompat() {}

    public static boolean isMobile() {
        return MOBILE;
    }

    private static boolean detect() {
        String override = System.getProperty("flashback.mobile");
        if (override != null) {
            return Boolean.parseBoolean(override);
        }

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String vendor = System.getProperty("java.vendor", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", "");
        String gameDir = System.getProperty("user.dir", "");
        if (os.contains("android") || vendor.contains("android")) return true;
        if (home.startsWith("/data/") || gameDir.startsWith("/storage/emulated/") || gameDir.startsWith("/sdcard/")) return true;

        String[] envKeys = {
                "POJAV_ENVIRON", "POJAV_RENDERER", "POJAV_NATIVEDIR", "POJAV_GAME_DIR",
                "FCL_NATIVEDIR", "FCL_RENDERER", "ZALITH_RENDERER", "MG_DIR_PATH", "MOBILEGLUES_PATH"
        };
        for (String key : envKeys) {
            if (System.getenv(key) != null) return true;
        }

        String renderer = System.getenv("LIBGL_NAME");
        return renderer != null && renderer.toLowerCase(Locale.ROOT).contains("mobileglues");
    }

    /**
     * glfwSetCursorPos wrapper. On mobile launchers the stub warps the pointer to the screen center (or ignores
     * the call), which makes the cursor "stick" to the middle of the screen, so we skip it there.
     */
    public static void setCursorPos(long window, double x, double y) {
        if (MOBILE) return;
        GLFW.glfwSetCursorPos(window, x, y);
    }

    /** Always sets the cursor mode and remembers it. */
    public static void setCursorMode(long window, int mode) {
        cursorModes.put(window, mode);
        GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, mode);
    }

    /** Sets the cursor mode only if it changed. On desktop this is a plain glfwSetInputMode call. */
    public static void setCursorModeIfChanged(long window, int mode) {
        if (MOBILE) {
            Integer last = cursorModes.get(window);
            if (last != null && last == mode) {
                // Something else (e.g. vanilla's MouseHandler) may have changed the mode behind our back.
                // Only trust the cache if the stub reports the same value, or reports nothing useful at all.
                int actual = GLFW.glfwGetInputMode(window, GLFW.GLFW_CURSOR);
                boolean actualIsValidMode = actual == GLFW.GLFW_CURSOR_NORMAL || actual == GLFW.GLFW_CURSOR_HIDDEN
                        || actual == GLFW.GLFW_CURSOR_DISABLED;
                if (actual == mode || !actualIsValidMode) return;
            }
        }
        setCursorMode(window, mode);
    }

    /** Sets the cursor shape only if it changed on mobile. */
    public static void setCursorShape(long window, long cursor) {
        if (MOBILE) {
            Long last = cursorShapes.get(window);
            if (last != null && last == cursor) return;
        }
        cursorShapes.put(window, cursor);
        GLFW.glfwSetCursor(window, cursor);
    }
}

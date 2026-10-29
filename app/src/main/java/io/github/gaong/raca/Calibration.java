package io.github.gaong.raca;

import android.content.Context;
import android.content.SharedPreferences;

/** Normalized positions; defaults are NOT verified against RIVALS layouts. */
public final class Calibration {
    private static final String PREFS = "raca_controls";
    public enum Control {
        JOYSTICK("Joystick", .18f, .77f),
        CAMERA("Camera start", .67f, .60f),
        FIRE("Fire", .91f, .75f),
        JUMP("Jump", .86f, .53f),
        KNIFE("Melee / switch", .94f, .26f);
        public final String label;
        public final float defaultX, defaultY;
        Control(String label, float x, float y) { this.label = label; defaultX = x; defaultY = y; }
    }

    public static float x(Context context, Control c) { return value(context, c, "x", c.defaultX); }
    public static float y(Context context, Control c) { return value(context, c, "y", c.defaultY); }

    private static float value(Context context, Control c, String axis, float fallback) {
        return context.getSharedPreferences(PREFS, 0).getFloat(c.name() + "_" + axis, fallback);
    }

    public static void set(Context context, Control c, float x, float y) {
        SharedPreferences.Editor e = context.getSharedPreferences(PREFS, 0).edit();
        e.putFloat(c.name() + "_x", (float) GameState.clamp(x, .02, .98));
        e.putFloat(c.name() + "_y", (float) GameState.clamp(y, .02, .98));
        e.apply();
    }

    public static float sensitivity(Context c) {
        return c.getSharedPreferences(PREFS, 0).getFloat("sensitivity", .45f);
    }

    public static void sensitivity(Context c, float value) {
        c.getSharedPreferences(PREFS, 0).edit().putFloat(
            "sensitivity", (float) GameState.clamp(value, .1, 1.5)).apply();
    }

    public static boolean auto(Context c) {
        return c.getSharedPreferences(PREFS, 0).getBoolean("auto", false);
    }
    public static void auto(Context c, boolean value) {
        c.getSharedPreferences(PREFS, 0).edit().putBoolean("auto", value).apply();
    }
}

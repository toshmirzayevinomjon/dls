package com.example.dlsbot;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.dlsbot.net.BotSettings;
import com.example.dlsbot.net.ButtonCoord;

import java.util.List;

/**
 * Kalibrlash va sozlamalarni telefon ichida (SharedPreferences) saqlaydi.
 * Server kerak emas.
 */
public final class LocalConfig {

    private static final String PREFS = "dls_bot";

    private LocalConfig() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void saveButtons(Context c, List<ButtonCoord> buttons) {
        SharedPreferences.Editor e = prefs(c).edit();
        for (ButtonCoord b : buttons) {
            e.putFloat("cal_" + b.name + "_x", b.x);
            e.putFloat("cal_" + b.name + "_y", b.y);
        }
        e.putBoolean("calibrated", true);
        e.apply();
    }

    public static boolean isCalibrated(Context c) {
        return prefs(c).getBoolean("calibrated", false);
    }

    public static void applyCalibration(Context c, BotSettings s) {
        if (s.buttons == null) return;
        SharedPreferences p = prefs(c);
        for (ButtonCoord b : s.buttons) {
            String kx = "cal_" + b.name + "_x";
            String ky = "cal_" + b.name + "_y";
            if (p.contains(kx)) {
                b.x = p.getFloat(kx, b.x);
                b.y = p.getFloat(ky, b.y);
            }
        }
    }
}

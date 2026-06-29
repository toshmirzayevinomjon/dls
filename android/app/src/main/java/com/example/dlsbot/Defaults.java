package com.example.dlsbot;

import android.content.Context;

import com.example.dlsbot.net.BotSettings;
import com.example.dlsbot.net.ButtonCoord;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Ilova ichidagi default sozlamalar — server kerak emas (standalone).
 * Kalibrlash bo'lsa, LocalConfig ustidan qo'llanadi.
 */
public final class Defaults {

    private Defaults() {}

    public static BotSettings buildSettings(Context ctx) {
        BotSettings s = new BotSettings();
        s.version = 1;
        s.processWidth = 640;
        s.processHeight = 360;
        s.matchThreshold = 0.62f;
        s.loopIntervalMs = 90;
        s.attackRight = true;
        s.joystickRadius = 0.10f;
        s.gestureDurationMs = 90;
        s.scales = new float[]{0.8f, 1.0f, 1.2f};

        BotSettings.Roi roi = new BotSettings.Roi();
        roi.x = 0.10f; roi.y = 0.12f; roi.w = 0.80f; roi.h = 0.76f;
        s.roi = roi;

        s.packageNames = Arrays.asList(
                "com.firsttouchgames.dls7",
                "com.firsttouchgames.dls8",
                "com.firsttouchgames.dls");
        s.navThreshold = 0.78f;
        s.relaunchAfterMs = 12000;
        s.maxBackTries = 4;
        s.heatThrottleC = 42.0f;

        s.buttons = new ArrayList<>();
        s.buttons.add(button("joystick", 0.14f, 0.80f));
        s.buttons.add(button("A_shoot", 0.90f, 0.78f));
        s.buttons.add(button("B_pass", 0.82f, 0.88f));
        s.buttons.add(button("C_thru", 0.74f, 0.70f));

        // Saqlangan kalibrlash bo'lsa, default koordinatalarni almashtiramiz
        LocalConfig.applyCalibration(ctx, s);
        return s;
    }

    private static ButtonCoord button(String name, float x, float y) {
        ButtonCoord b = new ButtonCoord();
        b.name = name; b.x = x; b.y = y;
        return b;
    }

    // Ilova ichида kerak bo'ladigan shablon nomlari va izohlari
    public static final String[] TEMPLATE_NAMES = {
            "ball", "control_indicator", "play_button",
            "continue_button", "ok_button", "goal_banner", "concede_banner"
    };

    public static List<String> templateNamesList() {
        return Arrays.asList(TEMPLATE_NAMES);
    }
}

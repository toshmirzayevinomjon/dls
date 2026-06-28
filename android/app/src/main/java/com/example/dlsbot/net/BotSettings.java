package com.example.dlsbot.net;

import java.util.List;

public class BotSettings {
    public int version;
    public int processWidth;
    public int processHeight;
    public float matchThreshold;
    public long loopIntervalMs;
    public boolean attackRight;
    public float joystickRadius;
    public long gestureDurationMs;
    public Roi roi;            // #10 ROI
    public float[] scales;     // #3 multi-scale
    public List<ButtonCoord> buttons;

    // Avtonom navigatsiya
    public List<String> packageNames; // DLS paket nomlari
    public float navThreshold;        // menyu tugmalari uchun ishonch chegarasi
    public long relaunchAfterMs;      // o'yin ochiq bo'lmasa, qancha kutib qayta ochish
    public int maxBackTries;          // noma'lum ekranda necha marta BACK
    public float heatThrottleC;       // shu haroratdan oshsa sekinlashtirish (Celsius)

    public static class Roi {
        public float x, y, w, h; // normallashtirilgan 0..1
    }

    public ButtonCoord findButton(String name) {
        if (buttons == null) return null;
        for (ButtonCoord b : buttons) {
            if (name.equals(b.name)) return b;
        }
        return null;
    }
}

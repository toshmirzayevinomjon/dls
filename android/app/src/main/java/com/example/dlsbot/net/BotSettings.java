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

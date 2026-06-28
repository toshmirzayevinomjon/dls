package com.example.dlsbot;

import android.graphics.Path;

import java.util.Random;

public final class Humanizer {

    private static final Random RND = new Random();

    private Humanizer() {}

    public static long randomDelay(long minMs, long maxMs) {
        if (maxMs <= minMs) return minMs;
        return minMs + (long) (RND.nextDouble() * (maxMs - minMs));
    }

    public static long microTapDuration() {
        return randomDelay(10, 20);
    }

    public static float jitter(float value, int jitterPx) {
        int delta = RND.nextInt(jitterPx * 2 + 1) - jitterPx;
        return value + delta;
    }

    public static float clamp(float v, float min, float max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    public static Path humanTapPath(float x, float y, int jitterPx) {
        float startX = jitter(x, jitterPx);
        float startY = jitter(y, jitterPx);
        float driftX = (RND.nextFloat() - 0.5f) * 12f;
        float driftY = (RND.nextFloat() - 0.5f) * 12f;
        float endX = startX + driftX;
        float endY = startY + driftY;

        Path path = new Path();
        path.moveTo(startX, startY);
        float ctrlX = (startX + endX) / 2f + (RND.nextFloat() - 0.5f) * 6f;
        float ctrlY = (startY + endY) / 2f + (RND.nextFloat() - 0.5f) * 6f;
        path.quadTo(ctrlX, ctrlY, endX, endY);
        return path;
    }

    public static Path humanSwipePath(float startX, float startY,
                                      float endX, float endY, int jitterPx) {
        float sx = jitter(startX, jitterPx);
        float sy = jitter(startY, jitterPx);
        float ex = jitter(endX, jitterPx);
        float ey = jitter(endY, jitterPx);

        float midX = (sx + ex) / 2f;
        float midY = (sy + ey) / 2f;
        float curve = (RND.nextFloat() - 0.5f) * 30f;

        Path path = new Path();
        path.moveTo(sx, sy);
        path.quadTo(midX + curve, midY - curve, ex, ey);
        return path;
    }

    public static boolean shouldHesitate(double chance) {
        return RND.nextDouble() < chance;
    }

    public static long hesitationPause() {
        return randomDelay(120, 380);
    }
}

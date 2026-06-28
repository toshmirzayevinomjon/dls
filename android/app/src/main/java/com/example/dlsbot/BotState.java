package com.example.dlsbot;

import android.content.Intent;
import android.graphics.Bitmap;
import android.media.projection.MediaProjection;

import com.example.dlsbot.net.BotSettings;

import org.opencv.core.Mat;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BotState {

    private static final BotState INSTANCE = new BotState();
    public static BotState get() { return INSTANCE; }
    private BotState() {}

    public final AtomicBoolean running = new AtomicBoolean(false);

    public int projectionResultCode = 0;
    public Intent projectionResultData = null;
    public volatile MediaProjection mediaProjection = null;

    public volatile int screenWidth = 0;
    public volatile int screenHeight = 0;
    public volatile int screenDensity = 0;

    private volatile Bitmap latestFrame = null;

    // #9 tanlangan taktika profili
    public volatile String profile = "balanced";

    public synchronized void setLatestFrame(Bitmap bmp) {
        this.latestFrame = bmp;
    }

    public synchronized Bitmap getLatestFrameCopy() {
        if (latestFrame == null) return null;
        return latestFrame.copy(Bitmap.Config.ARGB_8888, false);
    }

    public volatile BotSettings settings = null;
    public final Map<String, Mat> templates = new HashMap<>();

    public synchronized boolean isReady() {
        return settings != null && !templates.isEmpty()
                && mediaProjection != null
                && screenWidth > 0 && screenHeight > 0;
    }
}

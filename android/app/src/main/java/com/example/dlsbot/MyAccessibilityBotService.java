package com.example.dlsbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Path;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.example.dlsbot.net.BotSettings;
import com.example.dlsbot.net.ButtonCoord;
import com.example.dlsbot.net.DecisionResponse;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.util.List;

/**
 * Standalone DLS bot — SERVER KERAK EMAS.
 * Sozlama: Defaults, Qaror: LocalStrategy, Shablon: telefon ichidan (filesDir/templates).
 */
public class MyAccessibilityBotService extends AccessibilityService {

    private static final String TAG = "DLSBot";
    private static final int JITTER_PX = 10;
    private static final long GOAL_DEBOUNCE_MS = 8000;

    private static MyAccessibilityBotService instance;
    public static MyAccessibilityBotService getInstance() { return instance; }

    private HandlerThread botThread;
    private Handler botHandler;
    private volatile boolean loopActive = false;

    private volatile DecisionResponse strategy;
    private long lastActionTime = 0;

    private int myGoals = 0, oppGoals = 0;
    private long lastGoalTime = 0, lastConcedeTime = 0;

    private String gameState = "NOMA'LUM";
    private volatile String currentPackage = "";
    private long lastInMatchTime = 0;
    private long lastLaunchTime = 0;
    private long lastBackTime = 0;
    private int backPressCount = 0;

    private long lastFpsTime = 0;
    private int frameCount = 0;
    private float fps = 0f;
    private long heatExtraDelay = 0;
    private long lastHeatCheck = 0;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "OpenCV initDebug MUVAFFAQIYATSIZ");
        } else {
            Log.i(TAG, "OpenCV tayyor: " + Core.VERSION);
        }
        botThread = new HandlerThread("BotLogicThread");
        botThread.start();
        botHandler = new Handler(botThread.getLooper());

        // #1 Sozlama va o'rnatilgan DLS paketini oldindan tayyorlaymiz (auto-start uchun ham kerak)
        BotSettings s = Defaults.buildSettings(this);
        List<String> dls = AppUtil.detectInstalledDls(this, s.packageNames);
        if (!dls.isEmpty()) s.packageNames = dls;
        BotState.get().settings = s;
        Log.i(TAG, "Aniqlangan DLS paketlari: " + dls);
        Log.i(TAG, "Accessibility ulandi");
    }

    public void startBot() {
        if (loopActive) return;
        // Sozlama va shablonlarni telefon ichidan yuklaymiz (server yo'q)
        BotSettings s = Defaults.buildSettings(this);
        List<String> dls = AppUtil.detectInstalledDls(this, s.packageNames);
        if (!dls.isEmpty()) s.packageNames = dls;
        BotState.get().settings = s;
        loadLocalTemplates(s);
        // To'p shabloni bo'lmasa ham — rang/shakl bo'yicha topiladi (#9), to'xtatmaymiz

        loopActive = true;
        BotState.get().running.set(true);
        myGoals = 0; oppGoals = 0;
        backPressCount = 0;
        lastInMatchTime = System.currentTimeMillis();
        botHandler.post(this::tick);
    }

    public void stopBot() {
        loopActive = false;
        BotState.get().running.set(false);
        Log.i(TAG, "Bot to'xtatildi");
    }

    // Shablonlarni telefon ichidan o'qiymiz va ishlov o'lchamiga (640x360) moslaymiz
    private void loadLocalTemplates(BotSettings s) {
        BotState.get().templates.clear();
        File dir = new File(getFilesDir(), "templates");
        if (!dir.exists()) {
            Log.e(TAG, "Shablon papkasi yo'q — 'Shablon ol' qiling");
            return;
        }
        int sw = BotState.get().screenWidth, sh = BotState.get().screenHeight;
        if (sw <= 0 || sh <= 0) return;
        float sx = (float) s.processWidth / sw;
        float sy = (float) s.processHeight / sh;

        for (String name : Defaults.TEMPLATE_NAMES) {
            File f = new File(dir, name + ".png");
            if (!f.exists()) continue;
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (bmp == null) continue;
            Mat color = new Mat();
            Utils.bitmapToMat(bmp, color);
            bmp.recycle();
            Mat gray = new Mat();
            Imgproc.cvtColor(color, gray, Imgproc.COLOR_RGBA2GRAY);
            color.release();
            int tw = Math.max(1, Math.round(gray.cols() * sx));
            int th = Math.max(1, Math.round(gray.rows() * sy));
            Mat scaled = new Mat();
            Imgproc.resize(gray, scaled, new Size(tw, th));
            gray.release();
            BotState.get().templates.put(name, scaled);
            Log.i(TAG, "Shablon yuklandi: " + name + " " + tw + "x" + th);
        }
    }

    // ===================== ASOSIY SIKL =====================
    private void tick() {
        if (!loopActive) return;
        BotState state = BotState.get();
        BotSettings s = state.settings;
        try {
            Bitmap frame = state.getLatestFrameCopy();
            if (frame != null && state.isReady()) {
                Mat small = prepare(frame, s);
                frame.recycle();
                navigateAndPlay(small, s);
                small.release();
                updateFps();
            }
            heatThrottle(s);
        } catch (Throwable t) {
            Log.e(TAG, "Sikl xato: " + t.getMessage());
        }
        long base = (s != null) ? s.loopIntervalMs : 130;
        long next = Humanizer.randomDelay(base, base + 70);
        next = (long) (next * BotState.get().speedFactor) + heatExtraDelay;
        botHandler.postDelayed(this::tick, next);
    }

    private Mat prepare(Bitmap frame, BotSettings s) {
        Mat full = new Mat();
        Utils.bitmapToMat(frame, full);
        Mat gray = new Mat();
        Imgproc.cvtColor(full, gray, Imgproc.COLOR_RGBA2GRAY);
        full.release();
        Mat small = new Mat();
        Imgproc.resize(gray, small, new Size(s.processWidth, s.processHeight));
        gray.release();
        return small;
    }

    // ===================== AVTONOM NAVIGATSIYA =====================
    private void navigateAndPlay(Mat small, BotSettings s) {
        long now = System.currentTimeMillis();

        if (!isTargetGame(currentPackage)) {
            gameState = "O'YIN OCHIQ EMAS";
            if (now - lastLaunchTime > s.relaunchAfterMs) {
                lastLaunchTime = now;
                launchGame(s);
            }
            pushDebug(null, s);
            return;
        }

        if (tapIfFound(small, "continue_button", s)) { gameState = "MATCH TUGADI"; lastInMatchTime = now; return; }
        if (tapIfFound(small, "ok_button", s)) { gameState = "OYNA"; lastInMatchTime = now; return; }
        if (tapIfFound(small, "play_button", s)) { gameState = "MENYU"; lastInMatchTime = now; backPressCount = 0; return; }

        detectGoals(small, s);

        Rect roi = Vision.roiFromSettings(s);
        Mat ballTmpl = BotState.get().templates.get("ball");
        Vision.Match ball = (ballTmpl != null)
                ? Vision.matchMultiScale(small, ballTmpl, s.matchThreshold, s.scales, roi)
                : null;
        // #9 Shablon bo'lmasa yoki topilmasa -> rang/shakl bo'yicha qidiramiz
        if (ball == null) ball = Vision.detectBallByColor(small, roi);

        if (ball != null) {
            gameState = "O'YINDA";
            lastInMatchTime = now;
            backPressCount = 0;
            playFootball(small, ball, s, roi);
            pushDebug(ball, s);
            return;
        }

        gameState = "NOMA'LUM EKRAN";
        handleUnknownScreen(now, s);
        pushDebug(null, s);
    }

    private void handleUnknownScreen(long now, BotSettings s) {
        if (now - lastBackTime < 1600) return;
        lastBackTime = now;
        if (backPressCount < s.maxBackTries) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            backPressCount++;
            Log.i(TAG, "Noma'lum ekran -> BACK (" + backPressCount + ")");
        } else {
            backPressCount = 0;
            Log.i(TAG, "BACK yordam bermadi -> o'yin qayta ochiladi");
            launchGame(s);
        }
    }

    private void launchGame(BotSettings s) {
        List<String> pkgs = s.packageNames;
        if (pkgs == null) return;
        for (String pkg : pkgs) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    Log.i(TAG, "O'yin ochilmoqda: " + pkg);
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "Launch xato: " + e.getMessage());
            }
        }
        Log.e(TAG, "DLS topilmadi (paket o'rnatilmagan?)");
    }

    private boolean isTargetGame(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        BotSettings s = BotState.get().settings;
        if (s == null || s.packageNames == null) return false;
        for (String p : s.packageNames) {
            if (pkg.equals(p)) return true;
        }
        return false;
    }

    private boolean tapIfFound(Mat small, String name, BotSettings s) {
        Mat t = BotState.get().templates.get(name);
        if (t == null) return false;
        Vision.Match m = Vision.matchSingle(small, t, s.navThreshold, null);
        if (m == null) return false;
        int sw = BotState.get().screenWidth, sh = BotState.get().screenHeight;
        humanTap(m.cx / s.processWidth * sw, m.cy / s.processHeight * sh, sw, sh);
        Log.i(TAG, "Navigatsiya: " + name + " bosildi");
        return true;
    }

    // ===================== FUTBOL O'YINI (lokal qaror) =====================
    private void playFootball(Mat small, Vision.Match ball, BotSettings s, Rect roi) {
        boolean hasBall;
        Vision.Match ctrl = Vision.matchMultiScale(
                small, BotState.get().templates.get("control_indicator"), s.matchThreshold, s.scales, roi);
        if (ctrl != null) {
            float d = Vision.distance(ball.cx, ball.cy, ctrl.cx, ctrl.cy);
            hasBall = d < (s.processWidth * 0.08f);
        } else {
            hasBall = ball.score > (s.matchThreshold + 0.08f);
        }
        float bx = ball.cx / s.processWidth;
        strategy = LocalStrategy.decide(bx, hasBall, s.attackRight, myGoals);
        actByStrategy(s);
    }

    private void detectGoals(Mat small, BotSettings s) {
        long now = System.currentTimeMillis();
        float th = Math.max(0.75f, s.matchThreshold + 0.13f);
        if (now - lastGoalTime >= GOAL_DEBOUNCE_MS) {
            Vision.Match g = Vision.matchSingle(small, BotState.get().templates.get("goal_banner"), th, null);
            if (g != null) { lastGoalTime = now; myGoals++; Log.i(TAG, "BIZNING GOL! " + myGoals); }
        }
        if (now - lastConcedeTime >= GOAL_DEBOUNCE_MS) {
            Vision.Match c = Vision.matchSingle(small, BotState.get().templates.get("concede_banner"), th, null);
            if (c != null) { lastConcedeTime = now; oppGoals++; Log.i(TAG, "RAQIB GOL! " + oppGoals); }
        }
    }

    private void actByStrategy(BotSettings s) {
        long now = System.currentTimeMillis();
        long minGap = Humanizer.randomDelay(180, 420);
        if (now - lastActionTime < minGap) return;
        if (Humanizer.shouldHesitate(0.07)) { lastActionTime = now + Humanizer.hesitationPause(); return; }
        lastActionTime = now;

        int sw = BotState.get().screenWidth, sh = BotState.get().screenHeight;
        boolean attackRight = s.attackRight;
        String action = (strategy != null) ? strategy.action : "SURISH";
        ButtonCoord shoot = s.findButton("A_shoot");
        ButtonCoord pass = s.findButton("B_pass");
        ButtonCoord joy = s.findButton("joystick");

        switch (action) {
            case "ZARBA":
                if (shoot != null) humanTap(shoot.x * sw, shoot.y * sh, sw, sh);
                break;
            case "PAS":
                if (pass != null) humanTap(pass.x * sw, pass.y * sh, sw, sh);
                break;
            case "HIMOYA":
                if (joy != null) joystickSwipe(joy, !attackRight, s, sw, sh);
                if (pass != null) humanTap(pass.x * sw, pass.y * sh, sw, sh);
                break;
            case "SURISH":
            default:
                if (joy != null) joystickSwipe(joy, attackRight, s, sw, sh);
                break;
        }
    }

    private void joystickSwipe(ButtonCoord joy, boolean toRight, BotSettings s, int sw, int sh) {
        float jx = joy.x * sw, jy = joy.y * sh;
        float radius = s.joystickRadius * sw;
        float endX = jx + (toRight ? 1f : -1f) * radius;
        humanSwipe(jx, jy, endX, jy, sw, sh, s.gestureDurationMs);
    }

    private void humanTap(float x, float y, int sw, int sh) {
        Path path = Humanizer.humanTapPath(Humanizer.clamp(x, 1, sw - 1), Humanizer.clamp(y, 1, sh - 1), JITTER_PX);
        dispatchPath(path, Humanizer.microTapDuration());
    }

    private void humanSwipe(float sx, float sy, float ex, float ey, int sw, int sh, long baseDur) {
        Path path = Humanizer.humanSwipePath(
                Humanizer.clamp(sx, 1, sw - 1), Humanizer.clamp(sy, 1, sh - 1),
                Humanizer.clamp(ex, 1, sw - 1), Humanizer.clamp(ey, 1, sh - 1), JITTER_PX);
        dispatchPath(path, Humanizer.randomDelay(baseDur, baseDur + 60));
    }

    private void dispatchPath(Path path, long durationMs) {
        try {
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, Math.max(10, durationMs));
            dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, botHandler);
        } catch (Exception e) {
            Log.e(TAG, "dispatchGesture xato: " + e.getMessage());
        }
    }

    private void heatThrottle(BotSettings s) {
        long now = System.currentTimeMillis();
        if (now - lastHeatCheck < 5000) return;
        lastHeatCheck = now;
        try {
            Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bat != null) {
                int t = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                float celsius = t / 10.0f;
                heatExtraDelay = (s != null && celsius >= s.heatThrottleC) ? 250 : 0;
                if (heatExtraDelay > 0) Log.w(TAG, "Qizigan (" + celsius + "C) -> sekinlashtirildi");

                // #38 Batareya juda kam va quvvatlanmayotgan bo'lsa -> to'xtatamiz
                int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int status = bat.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
                if (level >= 0 && scale > 0) {
                    float pct = level * 100f / scale;
                    if (pct < 10 && !charging) {
                        Log.w(TAG, "Batareya kam (" + (int) pct + "%) -> bot to'xtatildi");
                        stopBot();
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void updateFps() {
        frameCount++;
        long now = System.currentTimeMillis();
        if (now - lastFpsTime >= 1000) {
            fps = frameCount * 1000f / (now - lastFpsTime);
            frameCount = 0;
            lastFpsTime = now;
        }
    }

    private void pushDebug(Vision.Match ball, BotSettings s) {
        DebugOverlay dbg = DebugOverlay.getInstance();
        if (dbg == null) return;
        int sw = BotState.get().screenWidth, sh = BotState.get().screenHeight;
        float bx = -1, by = -1;
        boolean found = ball != null;
        if (found) { bx = ball.cx / s.processWidth * sw; by = ball.cy / s.processHeight * sh; }
        String act = (strategy != null) ? strategy.action : "-";
        String md = (strategy != null && strategy.mode != null) ? strategy.mode : "-";
        float conf = (strategy != null) ? strategy.confidence : 0f;
        dbg.update(bx, by, found, act, md, gameState, conf, myGoals, oppGoals, fps);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        if (!pkg.equals(getPackageName())) {
            currentPackage = pkg;
            // #8 Avtomatik rejim: DLS oldinga chiqsa botni o'zi ishga tushiramiz
            if (BotState.get().autoMode && !loopActive && isTargetGame(pkg)) {
                Log.i(TAG, "AUTO: DLS aniqlandi -> bot ishga tushmoqda");
                startBot();
            }
        }
    }

    @Override public void onInterrupt() { stopBot(); }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        stopBot();
        if (botThread != null) botThread.quitSafely();
        instance = null;
        return super.onUnbind(intent);
    }
}

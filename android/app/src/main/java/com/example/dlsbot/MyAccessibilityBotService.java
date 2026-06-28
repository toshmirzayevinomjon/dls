package com.example.dlsbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.example.dlsbot.net.ApiClient;
import com.example.dlsbot.net.BotSettings;
import com.example.dlsbot.net.ButtonCoord;
import com.example.dlsbot.net.DecisionRequest;
import com.example.dlsbot.net.DecisionResponse;
import com.example.dlsbot.net.LogRequest;
import com.example.dlsbot.net.ScoreRequest;
import com.example.dlsbot.net.ScoreResponse;
import com.example.dlsbot.net.StatsRequest;
import com.example.dlsbot.net.TemplateItem;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.InputStream;
import java.util.List;

import okhttp3.Request;
import okhttp3.Response;
import retrofit2.Call;
import retrofit2.Callback;

public class MyAccessibilityBotService extends AccessibilityService {

    private static final String TAG = "DLSBot";
    private static final String MATCH_ID = "default";
    private static final int JITTER_PX = 10;
    private static final long GOAL_DEBOUNCE_MS = 8000;

    private static MyAccessibilityBotService instance;
    public static MyAccessibilityBotService getInstance() { return instance; }

    private HandlerThread botThread;
    private Handler botHandler;
    private volatile boolean loopActive = false;

    private volatile DecisionResponse strategy;
    private long lastStrategyTime = 0;
    private long lastActionTime = 0;

    private int myGoals = 0, oppGoals = 0;
    private long lastGoalTime = 0, lastConcedeTime = 0;

    // Navigatsiya holati
    private String gameState = "NOMA'LUM";
    private volatile String currentPackage = "";
    private long lastInMatchTime = 0;
    private long lastLaunchTime = 0;
    private long lastBackTime = 0;
    private int backPressCount = 0;

    // FPS va qizish
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
        Log.i(TAG, "Accessibility ulandi");
    }

    public void startBot() {
        if (loopActive) return;
        loopActive = true;
        BotState.get().running.set(true);
        myGoals = 0; oppGoals = 0;
        backPressCount = 0;
        lastInMatchTime = System.currentTimeMillis();
        reportScore("RESET");
        botHandler.post(this::initThenLoop);
    }

    public void stopBot() {
        loopActive = false;
        BotState.get().running.set(false);
        Log.i(TAG, "Bot to'xtatildi");
    }

    private void initThenLoop() {
        try {
            loadSettings();
            loadTemplates();
        } catch (Exception e) {
            Log.e(TAG, "Server yuklash: " + e.getMessage());
        }
        if (BotState.get().settings == null) {
            Log.e(TAG, "Sozlama yo'q -> to'xtaymiz");
            stopBot();
            return;
        }
        tick();
    }

    private void loadSettings() throws Exception {
        retrofit2.Response<BotSettings> r = ApiClient.getService().getSettings().execute();
        if (r.isSuccessful() && r.body() != null) BotState.get().settings = r.body();
    }

    private void loadTemplates() throws Exception {
        retrofit2.Response<TemplateItem.Response> r = ApiClient.getService().getTemplates().execute();
        if (!r.isSuccessful() || r.body() == null || r.body().templates == null) return;
        for (TemplateItem item : r.body().templates) {
            Bitmap bmp = downloadBitmap(item.url);
            if (bmp == null) continue;
            Mat color = new Mat();
            Utils.bitmapToMat(bmp, color);
            bmp.recycle();
            Mat gray = new Mat();
            Imgproc.cvtColor(color, gray, Imgproc.COLOR_RGBA2GRAY);
            color.release();
            BotState.get().templates.put(item.name, gray);
            Log.i(TAG, "Shablon: " + item.name);
        }
    }

    private Bitmap downloadBitmap(String url) {
        try {
            Request req = new Request.Builder().url(url).build();
            try (Response resp = ApiClient.rawHttpClient().newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                InputStream is = resp.body().byteStream();
                return android.graphics.BitmapFactory.decodeStream(is);
            }
        } catch (Exception e) {
            Log.e(TAG, "Rasm yuklash xato: " + e.getMessage());
            return null;
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
            reportLog("ERROR", "tick: " + t);
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

        // #49: O'yin oldinda emasmi? -> ochishga harakat qilamiz
        if (!isTargetGame(currentPackage)) {
            gameState = "O'YIN OCHIQ EMAS";
            if (now - lastLaunchTime > s.relaunchAfterMs) {
                lastLaunchTime = now;
                launchGame(s);
            }
            pushDebug(null, s);
            return;
        }

        // Match tugadi / popup -> davom ettirish tugmasini bosamiz
        if (tapIfFound(small, "continue_button", s)) { gameState = "MATCH TUGADI"; lastInMatchTime = now; return; }
        if (tapIfFound(small, "ok_button", s)) { gameState = "OYNA"; lastInMatchTime = now; return; }

        // Menyu -> PLAY bosib matchga kiramiz
        if (tapIfFound(small, "play_button", s)) { gameState = "MENYU"; lastInMatchTime = now; backPressCount = 0; return; }

        // Gollarni tekshiramiz
        detectGoals(small, s);

        // To'p bormi? -> o'yin ichidamiz
        Rect roi = Vision.roiFromSettings(s);
        Vision.Match ball = Vision.matchMultiScale(
                small, BotState.get().templates.get("ball"), s.matchThreshold, s.scales, roi);

        if (ball != null) {
            gameState = "O'YINDA";
            lastInMatchTime = now;
            backPressCount = 0;
            playFootball(small, ball, s, roi);
            pushDebug(ball, s);
            return;
        }

        // Hech narsa tanilmadi -> noto'g'ri/noma'lum ekran -> chiqishga harakat
        gameState = "NOMA'LUM EKRAN";
        handleUnknownScreen(now, s);
        pushDebug(null, s);
    }

    // #46: Noma'lum ekrandan chiqish (Back -> bo'lmasa qayta ishga tushirish)
    private void handleUnknownScreen(long now, BotSettings s) {
        long stuck = now - lastInMatchTime;
        if (now - lastBackTime < 1600) return;
        lastBackTime = now;

        if (backPressCount < s.maxBackTries) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            backPressCount++;
            Log.i(TAG, "Noma'lum ekran -> BACK (" + backPressCount + ")");
        } else {
            // Back yordam bermadi -> o'yinni qaytadan ochamiz
            backPressCount = 0;
            Log.i(TAG, "Ko'p marta BACK -> o'yin qayta ochiladi");
            launchGame(s);
        }
        if (stuck > 60000) reportLog("WARN", "60s o'yin ichiga kira olmadi");
    }

    // O'yinni ishga tushirish (paket nomlari ro'yxati bo'yicha)
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

    // Shablon topilsa, uni bosadi (navigatsiya tugmalari uchun)
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

    // ===================== FUTBOL O'YINI =====================
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
        float bx = ball.cx / s.processWidth, by = ball.cy / s.processHeight;
        maybeUpdateStrategy(bx, by, hasBall, s.attackRight);
        actByStrategy(s);
    }

    private void detectGoals(Mat small, BotSettings s) {
        long now = System.currentTimeMillis();
        float th = Math.max(0.75f, s.matchThreshold + 0.13f);
        if (now - lastGoalTime >= GOAL_DEBOUNCE_MS) {
            Vision.Match g = Vision.matchSingle(small, BotState.get().templates.get("goal_banner"), th, null);
            if (g != null) { lastGoalTime = now; myGoals++; Log.i(TAG, "BIZNING GOL! " + myGoals); reportScore("MY_GOAL"); }
        }
        if (now - lastConcedeTime >= GOAL_DEBOUNCE_MS) {
            Vision.Match c = Vision.matchSingle(small, BotState.get().templates.get("concede_banner"), th, null);
            if (c != null) { lastConcedeTime = now; oppGoals++; Log.i(TAG, "RAQIB GOL! " + oppGoals); reportScore("OPP_GOAL"); }
        }
    }

    private void reportScore(String event) {
        ApiClient.getService().reportScore(new ScoreRequest(MATCH_ID, event))
                .enqueue(new Callback<ScoreResponse>() {
                    @Override public void onResponse(Call<ScoreResponse> c, retrofit2.Response<ScoreResponse> r) {
                        if (r.isSuccessful() && r.body() != null) { myGoals = r.body().myGoals; oppGoals = r.body().oppGoals; }
                    }
                    @Override public void onFailure(Call<ScoreResponse> c, Throwable t) { }
                });
    }

    private void maybeUpdateStrategy(float bx, float by, boolean hasBall, boolean attackRight) {
        long now = System.currentTimeMillis();
        if (now - lastStrategyTime < 1500) return;
        lastStrategyTime = now;
        DecisionRequest req = new DecisionRequest();
        req.matchId = MATCH_ID;
        req.profile = BotState.get().profile;
        req.ball = new DecisionRequest.Coord(bx, by);
        req.oppGoal = new DecisionRequest.Coord(attackRight ? 0.95f : 0.05f, 0.5f);
        req.ownGoal = new DecisionRequest.Coord(attackRight ? 0.05f : 0.95f, 0.5f);
        req.hasBall = hasBall;
        req.attackRight = attackRight;
        req.myGoals = myGoals;
        req.oppGoals = oppGoals;
        ApiClient.getService().getDecision(req).enqueue(new Callback<DecisionResponse>() {
            @Override public void onResponse(Call<DecisionResponse> c, retrofit2.Response<DecisionResponse> r) {
                if (r.isSuccessful() && r.body() != null) strategy = r.body();
            }
            @Override public void onFailure(Call<DecisionResponse> c, Throwable t) { }
        });
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
                if (shoot != null) { humanTap(shoot.x * sw, shoot.y * sh, sw, sh); reportStats("ZARBA"); }
                break;
            case "PAS":
                if (pass != null) { humanTap(pass.x * sw, pass.y * sh, sw, sh); reportStats("PAS"); }
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

    private void reportStats(String action) {
        ApiClient.getService().reportStats(new StatsRequest(MATCH_ID, action))
                .enqueue(new Callback<Void>() {
                    @Override public void onResponse(Call<Void> c, retrofit2.Response<Void> r) { }
                    @Override public void onFailure(Call<Void> c, Throwable t) { }
                });
    }

    private void reportLog(String level, String message) {
        try {
            ApiClient.getService().reportLog(new LogRequest(MATCH_ID, level, message))
                    .enqueue(new Callback<Void>() {
                        @Override public void onResponse(Call<Void> c, retrofit2.Response<Void> r) { }
                        @Override public void onFailure(Call<Void> c, Throwable t) { }
                    });
        } catch (Exception ignored) {}
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

    // #47: Qizish nazorati — telefon qizib ketsa, sekinlashtiramiz
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
                if (heatExtraDelay > 0) Log.w(TAG, "Telefon qizigan (" + celsius + "C) -> sekinlashtirildi");
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
        // #49: Oldindagi ilova paketini kuzatamiz (o'zimizni hisobga olmaymiz)
        if (event == null || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        if (!pkg.equals(getPackageName())) {
            currentPackage = pkg;
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

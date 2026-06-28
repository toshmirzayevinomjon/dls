package com.example.dlsbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.example.dlsbot.net.ApiClient;
import com.example.dlsbot.net.BotSettings;
import com.example.dlsbot.net.ButtonCoord;
import com.example.dlsbot.net.DecisionRequest;
import com.example.dlsbot.net.DecisionResponse;
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

    // Hisob (#6 raqib goli ham)
    private int myGoals = 0, oppGoals = 0;
    private long lastGoalTime = 0, lastConcedeTime = 0;

    // #7 o'yin holati
    private String gameState = "NOMA'LUM";

    // FPS hisoblash
    private long lastFpsTime = 0;
    private int frameCount = 0;
    private float fps = 0f;

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
        loopOnce();
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
            Log.i(TAG, "Shablon: " + item.name + " " + gray.cols() + "x" + gray.rows());
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
    private void loopOnce() {
        if (!loopActive) return;
        BotState state = BotState.get();
        BotSettings s = state.settings;
        try {
            Bitmap frame = state.getLatestFrameCopy();
            if (frame != null && state.isReady()) {
                processFrame(frame, s);
                frame.recycle();
                updateFps();
            }
        } catch (Exception e) {
            Log.e(TAG, "Sikl xato: " + e.getMessage());
        }
        long base = (s != null) ? s.loopIntervalMs : 130;
        long next = Humanizer.randomDelay(base, base + 70);
        botHandler.postDelayed(this::loopOnce, next);
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

    private void processFrame(Bitmap frame, BotSettings s) {
        int pw = s.processWidth, ph = s.processHeight;

        Mat full = new Mat();
        Utils.bitmapToMat(frame, full);
        Mat gray = new Mat();
        Imgproc.cvtColor(full, gray, Imgproc.COLOR_RGBA2GRAY);
        full.release();
        Mat small = new Mat();
        Imgproc.resize(gray, small, new Size(pw, ph));
        gray.release();

        // #7 O'yin holati: PLAY tugmasi ko'rinsa -> menyudamiz, uni bosamiz
        if (handleGameState(small, s, pw, ph)) {
            small.release();
            return;
        }

        // Gollarni tekshiramiz (#6 ham)
        detectGoals(small, s);

        // #3 + #10: to'pni ROI ichida, multi-scale bilan qidiramiz
        Rect roi = Vision.roiFromSettings(s);
        Vision.Match ball = Vision.matchMultiScale(
                small, BotState.get().templates.get("ball"), s.matchThreshold, s.scales, roi);

        // #2 To'p bizdami: boshqaruv belgisi to'pga yaqinmi?
        boolean hasBall = false;
        if (ball != null) {
            Vision.Match ctrl = Vision.matchMultiScale(
                    small, BotState.get().templates.get("control_indicator"),
                    s.matchThreshold, s.scales, roi);
            if (ctrl != null) {
                float d = Vision.distance(ball.cx, ball.cy, ctrl.cx, ctrl.cy);
                hasBall = d < (pw * 0.08f); // ~8% ekran kengligi ichida bo'lsa bizniki
            } else {
                hasBall = ball.score > (s.matchThreshold + 0.08f);
            }
        }

        if (ball != null) {
            gameState = "O'YINDA";
            float bx = ball.cx / pw, by = ball.cy / ph;
            maybeUpdateStrategy(bx, by, hasBall, s.attackRight);
            actByStrategy(s);
        } else {
            gameState = "TOP YO'Q";
        }

        pushDebug(ball, pw, ph, s);
        small.release();
    }

    // #7 PLAY tugmasini topib bosadi
    private boolean handleGameState(Mat small, BotSettings s, int pw, int ph) {
        Mat playTmpl = BotState.get().templates.get("play_button");
        if (playTmpl == null) return false;
        float th = Math.max(0.78f, s.matchThreshold + 0.16f);
        Vision.Match play = Vision.matchSingle(small, playTmpl, th, null);
        if (play != null) {
            gameState = "MENYU";
            int sw = BotState.get().screenWidth, sh = BotState.get().screenHeight;
            float rx = play.cx / pw * sw, ry = play.cy / ph * sh;
            humanTap(rx, ry, sw, sh);
            Log.i(TAG, "MENYU: PLAY bosildi");
            pushDebug(null, pw, ph, s);
            return true;
        }
        return false;
    }

    // Bizning va raqib gollarini aniqlaydi (#6)
    private void detectGoals(Mat small, BotSettings s) {
        long now = System.currentTimeMillis();
        float th = Math.max(0.75f, s.matchThreshold + 0.13f);

        if (now - lastGoalTime >= GOAL_DEBOUNCE_MS) {
            Vision.Match g = Vision.matchSingle(small, BotState.get().templates.get("goal_banner"), th, null);
            if (g != null) {
                lastGoalTime = now;
                myGoals++;
                Log.i(TAG, "BIZNING GOL! " + myGoals);
                reportScore("MY_GOAL");
            }
        }
        if (now - lastConcedeTime >= GOAL_DEBOUNCE_MS) {
            Vision.Match c = Vision.matchSingle(small, BotState.get().templates.get("concede_banner"), th, null);
            if (c != null) {
                lastConcedeTime = now;
                oppGoals++;
                Log.i(TAG, "RAQIB GOL! " + oppGoals);
                reportScore("OPP_GOAL");
            }
        }
    }

    private void reportScore(String event) {
        ApiClient.getService().reportScore(new ScoreRequest(MATCH_ID, event))
                .enqueue(new Callback<ScoreResponse>() {
                    @Override public void onResponse(Call<ScoreResponse> c, retrofit2.Response<ScoreResponse> r) {
                        if (r.isSuccessful() && r.body() != null) {
                            myGoals = r.body().myGoals;
                            oppGoals = r.body().oppGoals;
                            Log.i(TAG, "Server rejim: " + r.body().mode);
                        }
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

    // ===================== HARAKAT (anti-mexanik) =====================
    private void actByStrategy(BotSettings s) {
        long now = System.currentTimeMillis();
        long minGap = Humanizer.randomDelay(180, 420);
        if (now - lastActionTime < minGap) return;
        if (Humanizer.shouldHesitate(0.07)) {
            lastActionTime = now + Humanizer.hesitationPause();
            return;
        }
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

    private void joystickSwipe(ButtonCoord joy, boolean toRight, BotSettings s, int sw, int sh) {
        float jx = joy.x * sw, jy = joy.y * sh;
        float radius = s.joystickRadius * sw;
        float endX = jx + (toRight ? 1f : -1f) * radius;
        humanSwipe(jx, jy, endX, jy, sw, sh, s.gestureDurationMs);
    }

    private void humanTap(float x, float y, int sw, int sh) {
        Path path = Humanizer.humanTapPath(
                Humanizer.clamp(x, 1, sw - 1), Humanizer.clamp(y, 1, sh - 1), JITTER_PX);
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
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            dispatchGesture(gesture, null, botHandler);
        } catch (Exception e) {
            Log.e(TAG, "dispatchGesture xato: " + e.getMessage());
        }
    }

    private void pushDebug(Vision.Match ball, int pw, int ph, BotSettings s) {
        DebugOverlay dbg = DebugOverlay.getInstance();
        if (dbg == null) return;
        int sw = BotState.get().screenWidth, sh = BotState.get().screenHeight;
        float bx = -1, by = -1;
        boolean found = ball != null;
        if (found) { bx = ball.cx / pw * sw; by = ball.cy / ph * sh; }
        String act = (strategy != null) ? strategy.action : "-";
        String md = (strategy != null && strategy.mode != null) ? strategy.mode : "-";
        float conf = (strategy != null) ? strategy.confidence : 0f;
        dbg.update(bx, by, found, act, md, gameState, conf, myGoals, oppGoals, fps);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { stopBot(); }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        stopBot();
        if (botThread != null) botThread.quitSafely();
        instance = null;
        return super.onUnbind(intent);
    }
}

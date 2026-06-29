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
    private static final long MATCH_ENTRY_TAP_GAP_MS = 1400;
    private static final long MATCH_ENTRY_RESET_MS = 9000;

    private static MyAccessibilityBotService instance;
    public static MyAccessibilityBotService getInstance() { return instance; }

    private HandlerThread botThread;
    private Handler botHandler;
    private volatile boolean loopActive = false;

    private volatile DecisionResponse strategy;
    private long lastActionTime = 0;

    private int myGoals = 0, oppGoals = 0;
    private long lastGoalTime = 0, lastConcedeTime = 0;

    // #46/#48 To'p tezligini kuzatish (bashorat uchun)
    private float lastBallX = -1, lastBallY = -1;
    private long lastBallT = 0;

    // Aniqlik: silliqlash (#46), miss-streak, dinamik threshold (#22)
    private float smoothBallX = -1, smoothBallY = -1;
    private int ballMissStreak = 0;
    private float dynThreshold = -1;

    // #21 Rangli kadr (oq-to'p aniqlash uchun)
    private Mat colorSmall;

    // Tashxis: bosish (gesture) natijasi
    private int gestureOk = 0, gestureCancel = 0;
    private volatile boolean gestureInFlight = false;

    private String gameState = "NOMA'LUM";
    private volatile String currentPackage = "";
    private long lastInMatchTime = 0;
    private long lastLaunchTime = 0;
    private long lastBackTime = 0;
    private int backPressCount = 0;
    private boolean matchEntryActive = false;
    private long lastMatchEntryTapTime = 0;
    private int matchEntryStep = 0;

    private long lastFpsTime = 0;
    private int frameCount = 0;
    private float fps = 0f;
    private long heatExtraDelay = 0;
    private long lastHeatCheck = 0;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        boolean cvOk = OpenCVLoader.initLocal();
        if (!cvOk) cvOk = OpenCVLoader.initDebug();
        if (!cvOk) {
            Log.e(TAG, "OpenCV ishga tushmadi");
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
        lastInMatchTime = 0;
        matchEntryActive = true;
        lastMatchEntryTapTime = 0;
        matchEntryStep = 0;
        smoothBallX = -1; smoothBallY = -1;
        ballMissStreak = 0; dynThreshold = -1;
        // Avto-AI kalibrlash O'CHIQ — vision noaniq koordinata berib pauza qildiryapti edi.
        // Bot default koordinatalar bilan ishlaydi (ishonchliroq).
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
                // Tezlik uchun: bitmapToMat FAQAT BIR MARTA, keyin gray ham, color ham shundan
                Mat fullColor = new Mat();
                Utils.bitmapToMat(frame, fullColor);
                frame.recycle();
                colorSmall = new Mat();
                Imgproc.resize(fullColor, colorSmall, new Size(s.processWidth, s.processHeight));
                fullColor.release();
                Mat small = new Mat();
                Imgproc.cvtColor(colorSmall, small, Imgproc.COLOR_RGBA2GRAY);
                navigateAndPlay(small, s);
                small.release();
                colorSmall.release();
                colorSmall = null;
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


    // ===================== AVTONOM NAVIGATSIYA =====================
    private void navigateAndPlay(Mat small, BotSettings s) {
        long now = System.currentTimeMillis();

        if (!isTargetGame(currentPackage)) {
            gameState = "O'YIN OCHIQ EMAS";
            if (now - lastLaunchTime > s.relaunchAfterMs) {
                lastLaunchTime = now;
                resetMatchEntryFlow();
                launchGame(s);
            }
            pushDebug(null, s);
            return;
        }

        if (tapIfFound(small, "continue_button", s)) { gameState = "MATCH TUGADI"; lastInMatchTime = now; return; }
        if (tapIfFound(small, "ok_button", s)) { gameState = "OYNA"; lastInMatchTime = now; return; }
        if (tapIfFound(small, "play_button", s)) { gameState = "MENYU"; lastInMatchTime = now; backPressCount = 0; return; }

        detectGoals(small, s);

        if (matchEntryActive && tapMatchEntryStep(now, s)) {
            pushDebug(null, s);
            return;
        }

        Rect roi = Vision.roiFromSettings(s);
        Mat ballTmpl = BotState.get().templates.get("ball");
        float useThr = (dynThreshold > 0) ? dynThreshold : s.matchThreshold;
        Vision.Match ball = (ballTmpl != null)
                ? Vision.matchMultiScale(small, ballTmpl, useThr, s.scales, roi)
                : null;
        // #9 Shablon bo'lmasa yoki topilmasa -> rang/shakl bo'yicha qidiramiz
        if (ball == null) ball = Vision.detectBallByColor(small, roi);
        // #21 Hali topilmasa -> oq rangi bo'yicha (HSV)
        if (ball == null && colorSmall != null) ball = Vision.detectBallByWhiteColor(colorSmall, roi);

        if (ball != null) {
            ballMissStreak = 0;
            dynThreshold = s.matchThreshold; // #22 normal holatga qaytaramiz
            // #46 silliqlash (eksponensial) + sakrashlarni kamaytirish
            if (smoothBallX < 0) { smoothBallX = ball.cx; smoothBallY = ball.cy; }
            else {
                smoothBallX = 0.6f * smoothBallX + 0.4f * ball.cx;
                smoothBallY = 0.6f * smoothBallY + 0.4f * ball.cy;
            }
            Vision.Match sm = new Vision.Match(smoothBallX, smoothBallY, ball.score, ball.scale);

            gameState = "O'YINDA";
            lastInMatchTime = now;
            matchEntryActive = false;
            backPressCount = 0;
            playFootball(small, sm, s, roi);
            pushDebug(sm, s);
            return;
        } else {
            ballMissStreak++;
            // #22 to'p uzoq topilmasa, threshold'ni avto-pasaytiramiz
            if (ballMissStreak == 8) dynThreshold = Math.max(0.45f, s.matchThreshold - 0.12f);
        }

        if (matchEntryActive) {
            gameState = "MATCHGA KIRISH KUTILMOQDA";
            pushDebug(null, s);
            return;
        }

        // To'p bir lahza topilmadi. Yaqinda o'ynayotgan bo'lsak — bu vaqtinchalik,
        // chiqib ketmaymiz (sozlama shart emas). To'pni qidirib oldinga harakatlanamiz.
        if (now - lastInMatchTime < 6000) {
            gameState = "TO'P QIDIRILMOQDA";
            if (now - lastActionTime > 250) {
                lastActionTime = now;
                ButtonCoord joy = s.findButton("joystick");
                moveJoystick(joy, s.attackRight ? 1f : -1f, 0f, s,
                        BotState.get().screenWidth, BotState.get().screenHeight);
            }
            pushDebug(null, s);
            return;
        }

        // Uzoq vaqt (6s+) to'p yo'q -> haqiqatan menyu/xato ekran -> chiqishga harakat
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
            resetMatchEntryFlow();
            launchGame(s);
        }
    }

    private void resetMatchEntryFlow() {
        matchEntryActive = true;
        matchEntryStep = 0;
        lastMatchEntryTapTime = 0;
    }

    private boolean tapMatchEntryStep(long now, BotSettings s) {
        int sw = BotState.get().screenWidth;
        int sh = BotState.get().screenHeight;
        if (sw <= 0 || sh <= 0) return false;

        if (lastMatchEntryTapTime > 0 && now - lastMatchEntryTapTime > MATCH_ENTRY_RESET_MS) {
            matchEntryStep = 0;
        }
        if (now - lastMatchEntryTapTime < MATCH_ENTRY_TAP_GAP_MS) return false;

        // DLS menyularida sozlashsiz matchga kirish: Career card -> Play Now -> PLAY.
        // Koordinatalar 16:9 DLS UI uchun normallashtirilgan, ekran o'lchamiga moslanadi.
        float[][] points = new float[][]{
                {0.28f, 0.32f}, // Home: CAREER / Career screen: Play Now card
                {0.28f, 0.32f}, // Agar birinchi bosish home'dan career'ga o'tkazgan bo'lsa
                {0.87f, 0.86f}, // Match preview: past o'ngdagi yashil PLAY
                {0.50f, 0.82f}, // Ba'zi dialoglarda markaz-past tasdiqlash
                {0.87f, 0.86f}
        };
        String[] labels = new String[]{
                "CAREER",
                "PLAY_NOW",
                "MATCH_PLAY",
                "CONFIRM",
                "MATCH_PLAY"
        };

        int idx = matchEntryStep % points.length;
        humanTap(points[idx][0] * sw, points[idx][1] * sh, sw, sh);
        gameState = "MATCHGA KIRISH: " + labels[idx];
        lastMatchEntryTapTime = now;
        matchEntryStep = (matchEntryStep + 1) % points.length;
        backPressCount = 0;
        Log.i(TAG, "Sozlamasiz navigatsiya: " + labels[idx] + " bosildi");
        return true;
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
            float dd = Vision.distance(ball.cx, ball.cy, ctrl.cx, ctrl.cy);
            hasBall = dd < (s.processWidth * 0.08f);
        } else {
            hasBall = ball.score > (s.matchThreshold + 0.08f);
        }

        float bx = ball.cx / s.processWidth;
        float by = ball.cy / s.processHeight;

        // #46/#48 To'p tezligini hisoblab, pozitsiyani oldindan bashorat qilamiz
        long now = System.currentTimeMillis();
        float velX = 0, velY = 0;
        if (lastBallX >= 0 && now - lastBallT > 0 && now - lastBallT < 400) {
            float dt = (now - lastBallT) / 1000f;
            velX = (bx - lastBallX) / dt;
            velY = (by - lastBallY) / dt;
        }
        lastBallX = bx; lastBallY = by; lastBallT = now;
        float predX = clamp01(bx + velX * 0.15f);
        float predY = clamp01(by + velY * 0.15f);

        strategy = LocalStrategy.decide(predX, predY, velX, velY, hasBall, s.attackRight, myGoals);
        actByStrategy(s);
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
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
        long minGap = Humanizer.randomDelay(150, 360);
        if (now - lastActionTime < minGap) return;
        if (Humanizer.shouldHesitate(0.06)) { lastActionTime = now + Humanizer.hesitationPause(); return; }
        lastActionTime = now;

        DecisionResponse d = strategy;
        if (d == null) return;
        int sw = BotState.get().screenWidth, sh = BotState.get().screenHeight;
        ButtonCoord shoot = s.findButton("A_shoot");
        ButtonCoord pass = s.findButton("B_pass");
        ButtonCoord thru = s.findButton("C_thru");
        ButtonCoord joy = s.findButton("joystick");

        switch (d.action) {
            case "SHOOT":
                // #1 Mo'ljal: joystickni burchak tomon burib, keyin zarba
                moveJoystick(joy, d.dirX, d.dirY, s, sw, sh);
                if (shoot != null) {
                    final float x = shoot.x * sw, y = shoot.y * sh;
                    botHandler.postDelayed(() -> humanTap(x, y, sw, sh), Humanizer.randomDelay(110, 180));
                }
                break;
            case "THROUGH":
                moveJoystick(joy, d.dirX, d.dirY, s, sw, sh);
                if (thru != null) humanTap(thru.x * sw, thru.y * sh, sw, sh);
                else if (pass != null) humanTap(pass.x * sw, pass.y * sh, sw, sh);
                break;
            case "PASS":
                moveJoystick(joy, d.dirX, d.dirY, s, sw, sh);
                if (pass != null) humanTap(pass.x * sw, pass.y * sh, sw, sh);
                break;
            case "CLEAR":
                moveJoystick(joy, d.dirX, d.dirY, s, sw, sh);
                if (shoot != null) humanTap(shoot.x * sw, shoot.y * sh, sw, sh);
                break;
            case "TACKLE":
                moveJoystick(joy, d.dirX, d.dirY, s, sw, sh);
                if (pass != null) humanTap(pass.x * sw, pass.y * sh, sw, sh); // B = bosim/tackle
                break;
            case "DRIBBLE":
            case "CHASE":
            default:
                moveJoystick(joy, d.dirX, d.dirY, s, sw, sh);
                break;
        }
    }

    // Joystickni berilgan yo'nalishga suradi (-1..1). Yo'nalish 0 bo'lsa hujum tomon.
    private void moveJoystick(ButtonCoord joy, float dirX, float dirY, BotSettings s, int sw, int sh) {
        if (joy == null) return;
        float jx = joy.x * sw, jy = joy.y * sh;
        float r = s.joystickRadius * sw;
        float mag = (float) Math.sqrt(dirX * dirX + dirY * dirY);
        if (mag < 0.01f) { dirX = s.attackRight ? 1f : -1f; dirY = 0f; mag = 1f; }
        float ex = jx + r * dirX / mag;
        float ey = jy + r * dirY / mag;
        humanSwipe(jx, jy, ex, ey, sw, sh, s.gestureDurationMs);
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
        if (gestureInFlight) return; // oldingi bosish tugamaguncha yangisini yubormaymiz (BEKOR kamayadi)
        try {
            gestureInFlight = true;
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, Math.max(10, durationMs));
            dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(),
                    new GestureResultCallback() {
                        @Override public void onCompleted(GestureDescription gestureDescription) {
                            gestureInFlight = false;
                            gestureOk++;
                            DebugOverlay d = DebugOverlay.getInstance();
                            if (d != null) d.setGesture(gestureOk, gestureCancel, "OK");
                        }
                        @Override public void onCancelled(GestureDescription gestureDescription) {
                            gestureInFlight = false;
                            gestureCancel++;
                            DebugOverlay d = DebugOverlay.getInstance();
                            if (d != null) d.setGesture(gestureOk, gestureCancel, "BEKOR");
                        }
                    }, botHandler);
        } catch (Exception e) {
            gestureInFlight = false;
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

    // #43 Groq Vision bilan tugmalarni avto-joylashtirish (START'da, kalit bo'lsa)
    private void autoAiCalibrate() {
        if (!loopActive) return;
        String url = LocalConfig.getBackendUrl(this);
        if (url == null || url.isEmpty()) return;
        Bitmap frame = BotState.get().getLatestFrameCopy();
        if (frame == null) return;
        new Thread(() -> GroqVision.autoCalibrate(frame, url, new GroqVision.Cb() {
            @Override public void onResult(List<ButtonCoord> b) {
                LocalConfig.saveButtons(getApplicationContext(), b);
                BotSettings s2 = BotState.get().settings;
                if (s2 != null) LocalConfig.applyCalibration(MyAccessibilityBotService.this, s2);
                Log.i(TAG, "AI auto-kalibrlash qo'llandi: " + b.size());
            }
            @Override public void onError(String e) {
                Log.w(TAG, "AI auto-kalibrlash: " + e);
            }
        })).start();
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

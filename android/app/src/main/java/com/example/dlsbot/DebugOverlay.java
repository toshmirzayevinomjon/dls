package com.example.dlsbot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.View;
import android.view.WindowManager;

/**
 * #4 + #5: Vizual debug overlay.
 * Bot nimani "ko'rayotganini" (to'p, rejim, hisob, FPS, holat) ekranda chizadi.
 * FLAG_NOT_TOUCHABLE -> o'yinga teginishni to'smaydi.
 */
public class DebugOverlay extends View {

    private static DebugOverlay instance;
    public static DebugOverlay getInstance() { return instance; }

    private final Paint ballPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();

    private float ballX = -1, ballY = -1;
    private boolean ballFound = false;
    private String action = "-";
    private String mode = "-";
    private String gameState = "-";
    private int myGoals = 0, oppGoals = 0;
    private float confidence = 0f;
    private float fps = 0f;

    private DebugOverlay(Context ctx) {
        super(ctx);
        ballPaint.setColor(Color.GREEN);
        ballPaint.setStyle(Paint.Style.STROKE);
        ballPaint.setStrokeWidth(5);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(32);
        bgPaint.setColor(Color.argb(150, 0, 0, 0));
    }

    public static void attach(Context ctx) {
        if (instance != null) return;
        instance = new DebugOverlay(ctx);
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        // MUHIM: faqat YUQORI chiziq (to'liq ekran emas) — aks holda o'yin
        // bot bosishlarini "to'silgan" deb rad etadi (obscured touch).
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                210,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = android.view.Gravity.TOP;
        wm.addView(instance, lp);
    }

    public static void detach(Context ctx) {
        if (instance == null) return;
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            wm.removeView(instance);
        } catch (Exception ignored) {}
        instance = null;
    }

    public void update(float ballRealX, float ballRealY, boolean found,
                       String action, String mode, String gameState, float confidence,
                       int myGoals, int oppGoals, float fps) {
        this.ballX = ballRealX;
        this.ballY = ballRealY;
        this.ballFound = found;
        this.action = action;
        this.mode = mode;
        this.gameState = gameState;
        this.confidence = confidence;
        this.myGoals = myGoals;
        this.oppGoals = oppGoals;
        this.fps = fps;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), 175, bgPaint);
        canvas.drawText("Holat: " + gameState + "   Rejim: " + mode
                + "   FPS: " + String.format("%.1f", fps), 20, 42, textPaint);
        canvas.drawText("Hisob: " + myGoals + "-" + oppGoals
                + "   Buyruq: " + action
                + "   Ishonch: " + String.format("%.2f", confidence), 20, 90, textPaint);
        canvas.drawText("Top: " + (ballFound ? "TOPILDI" : "yo'q"), 20, 138, textPaint);

        if (ballFound && ballX >= 0) {
            canvas.drawCircle(ballX, ballY, 40, ballPaint);
        }
    }
}

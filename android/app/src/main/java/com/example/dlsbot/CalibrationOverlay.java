package com.example.dlsbot;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.example.dlsbot.net.ApiClient;
import com.example.dlsbot.net.ButtonCoord;
import com.example.dlsbot.net.CalibrationRequest;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * #1: Kalibrlash. O'yin ustida shaffof oyna ochiladi; foydalanuvchi tugmalar joyini
 * ketma-ket bosadi; koordinatalar normallashtirilib backendga saqlanadi.
 */
public class CalibrationOverlay {

    private final Context ctx;
    private final WindowManager wm;
    private FrameLayout root;
    private TextView prompt;

    private final String[][] steps = {
            { "joystick", "JOYSTICK markazini bosing (chap pastdagi boshqaruv)" },
            { "A_shoot",  "ZARBA (A) tugmasini bosing" },
            { "B_pass",   "PAS (B) tugmasini bosing" },
            { "C_thru",   "Chiziqli PAS (C) tugmasini bosing" }
    };

    private int index = 0;
    private final List<ButtonCoord> result = new ArrayList<>();
    private int screenW, screenH;

    public CalibrationOverlay(Context ctx) {
        this.ctx = ctx;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    public void start() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;

        root = new FrameLayout(ctx);
        root.setBackgroundColor(Color.argb(90, 0, 0, 0));

        prompt = new TextView(ctx);
        prompt.setTextColor(Color.WHITE);
        prompt.setTextSize(18);
        prompt.setBackgroundColor(Color.argb(180, 0, 0, 0));
        prompt.setPadding(24, 24, 24, 24);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.TOP;
        root.addView(prompt, plp);
        updatePrompt();

        root.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                recordTap(ev.getRawX(), ev.getRawY());
                return true;
            }
            return false;
        });

        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        wm.addView(root, lp);
    }

    private void updatePrompt() {
        if (index < steps.length) {
            prompt.setText("KALIBRLASH (" + (index + 1) + "/" + steps.length + ")\n" + steps[index][1]);
        }
    }

    private void recordTap(float rawX, float rawY) {
        ButtonCoord bc = new ButtonCoord();
        bc.name = steps[index][0];
        bc.x = rawX / screenW;
        bc.y = rawY / screenH;
        result.add(bc);

        index++;
        if (index < steps.length) updatePrompt();
        else finish();
    }

    private void finish() {
        prompt.setText("Saqlanmoqda...");
        ApiClient.getService().saveCalibration(new CalibrationRequest(result))
                .enqueue(new Callback<Void>() {
                    @Override public void onResponse(Call<Void> c, Response<Void> r) {
                        Toast.makeText(ctx, "Kalibrlash saqlandi", Toast.LENGTH_LONG).show();
                        close();
                    }
                    @Override public void onFailure(Call<Void> c, Throwable t) {
                        Toast.makeText(ctx, "Saqlash xato: " + t.getMessage(), Toast.LENGTH_LONG).show();
                        close();
                    }
                });
    }

    private void close() {
        try { if (root != null) wm.removeView(root); } catch (Exception ignored) {}
        root = null;
    }
}

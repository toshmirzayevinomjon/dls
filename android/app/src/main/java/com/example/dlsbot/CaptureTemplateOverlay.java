package com.example.dlsbot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Ilova ichida shablon olish. Foydalanuvchi ekranda to'p/tugma atrofiga
 * barmoq bilan to'rtburchak chizadi -> o'sha qism kesib, telefon ichiga
 * (filesDir/templates/<nom>.png) saqlanadi. Server KERAK EMAS.
 *
 * MUHIM: avval "Ekran olishni boshlash" bosilgan bo'lishi kerak (kadr olish uchun).
 */
public class CaptureTemplateOverlay {

    private final Context ctx;
    private final WindowManager wm;
    private LinearLayout root;
    private TextView prompt;
    private DrawView draw;

    private final String[] names = Defaults.TEMPLATE_NAMES;
    private final String[] labels = {
            "TO'P (ball)",
            "Boshqaruvdagi o'yinchi belgisi",
            "PLAY tugmasi (menyu)",
            "Continue (match tugaganda)",
            "OK / Yopish tugmasi",
            "Bizning GOAL banneri",
            "Raqib goli banneri"
    };
    private int index = 0;

    public CaptureTemplateOverlay(Context ctx) {
        this.ctx = ctx;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    public void start() {
        if (BotState.get().getLatestFrameCopy() == null) {
            Toast.makeText(ctx, "Avval 'Ekran olishni boshlash' ni bosing!", Toast.LENGTH_LONG).show();
            return;
        }

        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.argb(200, 0, 0, 0));
        bar.setPadding(16, 16, 16, 16);

        prompt = new TextView(ctx);
        prompt.setTextColor(Color.WHITE);
        prompt.setTextSize(15);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        prompt.setLayoutParams(pl);

        Button skip = new Button(ctx);
        skip.setText("O'tkazish");
        skip.setAllCaps(false);
        skip.setOnClickListener(v -> advance());

        Button finish = new Button(ctx);
        finish.setText("Tugatish");
        finish.setAllCaps(false);
        finish.setOnClickListener(v -> close());

        bar.addView(prompt);
        bar.addView(skip);
        bar.addView(finish);

        draw = new DrawView(ctx);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        draw.setLayoutParams(dl);

        root.addView(bar);
        root.addView(draw);
        updatePrompt();

        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        wm.addView(root, lp);
    }

    private void updatePrompt() {
        if (index < names.length) {
            prompt.setText("(" + (index + 1) + "/" + names.length + ") Atrofiga to'rtburchak chizing:\n"
                    + labels[index]);
        }
    }

    private void advance() {
        index++;
        if (index >= names.length) {
            Toast.makeText(ctx, "Barcha shablonlar olindi!", Toast.LENGTH_LONG).show();
            close();
        } else {
            updatePrompt();
            draw.reset();
        }
    }

    private void saveCurrent(float l, float t, float r, float b) {
        Bitmap frame = BotState.get().getLatestFrameCopy();
        if (frame == null) {
            Toast.makeText(ctx, "Kadr yo'q — ekran olishni boshlang", Toast.LENGTH_SHORT).show();
            return;
        }
        int x = (int) Math.min(l, r);
        int y = (int) Math.min(t, b);
        int w = (int) Math.abs(r - l);
        int h = (int) Math.abs(b - t);
        if (w < 12 || h < 12) {
            Toast.makeText(ctx, "Juda kichik — kattaroq chizing", Toast.LENGTH_SHORT).show();
            return;
        }
        x = Math.max(0, Math.min(x, frame.getWidth() - 1));
        y = Math.max(0, Math.min(y, frame.getHeight() - 1));
        w = Math.min(w, frame.getWidth() - x);
        h = Math.min(h, frame.getHeight() - y);

        try {
            Bitmap crop = Bitmap.createBitmap(frame, x, y, w, h);
            File dir = new File(ctx.getFilesDir(), "templates");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, names[index] + ".png");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                crop.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            crop.recycle();
            Toast.makeText(ctx, labels[index] + " saqlandi (" + w + "x" + h + ")", Toast.LENGTH_SHORT).show();
            advance();
        } catch (Exception e) {
            Toast.makeText(ctx, "Saqlash xato: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void close() {
        try { if (root != null) wm.removeView(root); } catch (Exception ignored) {}
        root = null;
    }

    // To'rtburchakni chizadigan va tanlovni qabul qiladigan View
    private class DrawView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float sx, sy, cx, cy;
        private boolean drawing = false;

        DrawView(Context c) {
            super(c);
            setBackgroundColor(Color.argb(60, 0, 0, 0));
            paint.setColor(Color.GREEN);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4);
        }

        void reset() { drawing = false; invalidate(); }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    sx = ev.getRawX(); sy = ev.getRawY();
                    cx = sx; cy = sy; drawing = true;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    cx = ev.getRawX(); cy = ev.getRawY();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    cx = ev.getRawX(); cy = ev.getRawY();
                    drawing = false;
                    invalidate();
                    saveCurrent(sx, sy, cx, cy);
                    return true;
            }
            return false;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (drawing) {
                canvas.drawRect(Math.min(sx, cx), Math.min(sy, cy),
                        Math.max(sx, cx), Math.max(sy, cy), paint);
            }
        }
    }
}

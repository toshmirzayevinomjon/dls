package com.example.dlsbot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.example.dlsbot.net.ButtonCoord;

import java.util.List;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;

/**
 * Minimal boshqaruv paneli:
 *   ▶ START   — hammasini o'zi qiladi (o'yinni ochish, menyu, match, o'ynash)
 *   ■ STOP    — to'xtatish
 *   ⚙ Sozlash — 1 martalik: menyu tugmalarini ko'rsatish + A/B/C kalibrlash
 *   AUTO      — DLS ochilsa bot o'zi START qilsin
 */
public class FloatingOverlayService extends Service {

    private static final String CHANNEL_ID = "dls_overlay";
    private static final int NOTIF_ID = 7002;

    private WindowManager windowManager;
    private LinearLayout overlayView;
    private WindowManager.LayoutParams params;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());
        if (overlayView == null) showOverlay();
        return START_STICKY;
    }

    private void showOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        overlayView = new LinearLayout(this);
        overlayView.setOrientation(LinearLayout.VERTICAL);
        overlayView.setBackgroundColor(Color.argb(120, 0, 0, 0));
        overlayView.setPadding(8, 8, 8, 8);

        Button btnStart = makeButton("▶ START", "#2E7D32");
        btnStart.setOnClickListener(v -> {
            MyAccessibilityBotService bot = MyAccessibilityBotService.getInstance();
            if (bot == null) {
                Toast.makeText(this, "Avval Accessibility xizmatini yoqing!", Toast.LENGTH_LONG).show();
                return;
            }
            bot.startBot();
            Toast.makeText(this, "Bot ishga tushdi — o'zi o'ynaydi", Toast.LENGTH_SHORT).show();
        });

        Button btnStop = makeButton("■ STOP", "#C62828");
        btnStop.setOnClickListener(v -> {
            MyAccessibilityBotService bot = MyAccessibilityBotService.getInstance();
            if (bot != null) bot.stopBot();
            Toast.makeText(this, "To'xtatildi", Toast.LENGTH_SHORT).show();
        });

        // 1 martalik sozlash: shablon olish -> tugagach kalibrlash
        Button btnSetup = makeButton("⚙ Sozlash (ixtiyoriy)", "#1565C0");
        btnSetup.setOnClickListener(v -> {
            MyAccessibilityBotService bot = MyAccessibilityBotService.getInstance();
            if (bot != null) bot.stopBot();
            Toast.makeText(this, "Ixtiyoriy: aniqlik uchun menyu tugmalari + A/B/C", Toast.LENGTH_LONG).show();
            new CaptureTemplateOverlay(this, () -> new CalibrationOverlay(this).start()).start();
        });

        // 🤖 AI Sozlash: Groq Vision skrinshotdan tugmalarni topib joylashtiradi
        Button btnAi = makeButton("🤖 AI Sozlash", "#6A1B9A");
        btnAi.setOnClickListener(v -> {
            String key = LocalConfig.getGroqKey(this);
            if (key == null || key.isEmpty()) {
                Toast.makeText(this, "Avval ilovada Groq kalitini saqlang", Toast.LENGTH_LONG).show();
                return;
            }
            Bitmap frame = BotState.get().getLatestFrameCopy();
            if (frame == null) {
                Toast.makeText(this, "Avval 'Ekran olishni boshlash' ni bosing", Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(this, "AI tugmalarni qidirmoqda...", Toast.LENGTH_SHORT).show();
            final Handler h = new Handler(Looper.getMainLooper());
            new Thread(() -> GroqVision.autoCalibrate(frame, key, new GroqVision.Cb() {
                @Override public void onResult(List<ButtonCoord> b) {
                    LocalConfig.saveButtons(getApplicationContext(), b);
                    h.post(() -> Toast.makeText(FloatingOverlayService.this,
                            "AI " + b.size() + " ta tugmani joylashtirdi ✅", Toast.LENGTH_LONG).show());
                }
                @Override public void onError(String e) {
                    h.post(() -> Toast.makeText(FloatingOverlayService.this,
                            "AI xato: " + e, Toast.LENGTH_LONG).show());
                }
            })).start();
        });

        Button btnAuto = makeButton("AUTO: O'CHIQ", "#37474F");
        btnAuto.setOnClickListener(v -> {
            boolean on = !BotState.get().autoMode;
            BotState.get().autoMode = on;
            ((Button) v).setText("AUTO: " + (on ? "YONIQ" : "O'CHIQ"));
            Toast.makeText(this, on ? "DLS ochilsa bot o'zi boshlaydi" : "Avto o'chirildi",
                    Toast.LENGTH_SHORT).show();
        });

        overlayView.addView(btnStart);
        overlayView.addView(btnStop);
        overlayView.addView(btnSetup);
        overlayView.addView(btnAi);
        overlayView.addView(btnAuto);

        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 20;
        params.y = 200;

        overlayView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float touchX, touchY;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x; initialY = params.y;
                        touchX = event.getRawX(); touchY = event.getRawY();
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - touchX);
                        params.y = initialY + (int) (event.getRawY() - touchY);
                        windowManager.updateViewLayout(overlayView, params);
                        return false;
                }
                return false;
            }
        });

        windowManager.addView(overlayView, params);
    }

    private Button makeButton(String text, String color) {
        Button b = new Button(this);
        b.setText(text);
        b.setBackgroundColor(Color.parseColor(color));
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        return b;
    }

    private Notification buildNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("DLS Bot")
                .setContentText("Boshqaruv faol")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "DLS Overlay", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    @Override
    public void onDestroy() {
        if (overlayView != null && windowManager != null) {
            windowManager.removeView(overlayView);
            overlayView = null;
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}

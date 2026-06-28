package com.example.dlsbot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;

public class FloatingOverlayService extends Service {

    private static final String CHANNEL_ID = "dls_overlay";
    private static final int NOTIF_ID = 7002;

    private WindowManager windowManager;
    private LinearLayout overlayView;
    private WindowManager.LayoutParams params;

    // #9 profillar aylanmasi
    private final String[] profiles = { "balanced", "attacker", "defender" };
    private final String[] profileLabels = { "Balansli", "Hujumchi", "Himoyachi" };
    private int profileIndex = 0;
    private Button btnProfile;

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

        Button btnStart = makeButton("START", "#2E7D32");
        btnStart.setOnClickListener(v -> {
            MyAccessibilityBotService bot = MyAccessibilityBotService.getInstance();
            if (bot == null) {
                Toast.makeText(this, "Avval Accessibility xizmatini yoqing!", Toast.LENGTH_LONG).show();
                return;
            }
            bot.startBot();
            Toast.makeText(this, "Bot ishga tushdi", Toast.LENGTH_SHORT).show();
        });

        Button btnStop = makeButton("STOP", "#C62828");
        btnStop.setOnClickListener(v -> {
            MyAccessibilityBotService bot = MyAccessibilityBotService.getInstance();
            if (bot != null) bot.stopBot();
            Toast.makeText(this, "Bot to'xtatildi", Toast.LENGTH_SHORT).show();
        });

        Button btnCalib = makeButton("CALIB", "#1565C0");
        btnCalib.setOnClickListener(v -> {
            MyAccessibilityBotService bot = MyAccessibilityBotService.getInstance();
            if (bot != null) bot.stopBot();
            new CalibrationOverlay(this).start();
        });

        btnProfile = makeButton("Profil: " + profileLabels[profileIndex], "#6A1B9A");
        btnProfile.setOnClickListener(v -> {
            profileIndex = (profileIndex + 1) % profiles.length;
            BotState.get().profile = profiles[profileIndex];
            btnProfile.setText("Profil: " + profileLabels[profileIndex]);
            Toast.makeText(this, "Profil: " + profileLabels[profileIndex], Toast.LENGTH_SHORT).show();
        });
        BotState.get().profile = profiles[profileIndex];

        overlayView.addView(btnStart);
        overlayView.addView(btnStop);
        overlayView.addView(btnCalib);
        overlayView.addView(btnProfile);

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

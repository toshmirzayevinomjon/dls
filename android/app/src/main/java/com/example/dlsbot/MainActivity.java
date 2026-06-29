package com.example.dlsbot;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PROJECTION = 1001;
    private static final int REQ_NOTIF = 1002;

    private MediaProjectionManager projectionManager;
    private android.widget.TextView statusView;
    private boolean autoOverlayOpened = false, autoAccessOpened = false, projectionRequested = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 80, 40, 40);

        Button btnOverlay = new Button(this);
        btnOverlay.setText("1. Overlay ruxsatini yoqish");
        btnOverlay.setOnClickListener(v -> requestOverlay());

        Button btnAccess = new Button(this);
        btnAccess.setText("2. Accessibility xizmatini yoqish");
        btnAccess.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        Button btnStart = new Button(this);
        btnStart.setText("3. Ekran olishni boshlash + Overlay");
        btnStart.setOnClickListener(v -> requestProjection());

        // Backend URL (Railway) — AI kalibrlash shu orqali. Kalit ILOVADA EMAS.
        final android.widget.EditText keyField = new android.widget.EditText(this);
        keyField.setHint("Backend URL (Railway, masalan https://xxx.up.railway.app)");
        keyField.setText(LocalConfig.getBackendUrl(this));

        Button btnSaveKey = new Button(this);
        btnSaveKey.setText("Backend URL saqlash");
        btnSaveKey.setOnClickListener(v -> {
            LocalConfig.saveBackendUrl(this, keyField.getText().toString().trim());
            Toast.makeText(this, "Backend URL saqlandi", Toast.LENGTH_SHORT).show();
        });

        statusView = new android.widget.TextView(this);
        statusView.setText("Sozlash boshlanmoqda...");
        statusView.setTextSize(17);
        statusView.setPadding(0, 0, 0, 40);

        root.addView(statusView);
        root.addView(btnOverlay);
        root.addView(btnAccess);
        root.addView(btnStart);
        root.addView(keyField);
        root.addView(btnSaveKey);
        setContentView(root);

        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        requestNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        advanceSetup();
    }

    // Avto-sehrgar: kerakli ruxsatlarni ketma-ket o'zi ochib boradi
    private void advanceSetup() {
        if (statusView == null) return;
        if (!Settings.canDrawOverlays(this)) {
            statusView.setText("1/3 Overlay ruxsati — sahifa ochilmoqda, yoqing");
            if (!autoOverlayOpened) { autoOverlayOpened = true; requestOverlay(); }
            return;
        }
        if (!isAccessibilityEnabled()) {
            statusView.setText("2/3 Accessibility — ro'yxatdan 'DLS Bot' ni yoqing");
            if (!autoAccessOpened) {
                autoAccessOpened = true;
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
            return;
        }
        if (BotState.get().projectionResultData == null) {
            statusView.setText("3/3 Ekran olishga ruxsat bering");
            if (!projectionRequested) { projectionRequested = true; requestProjection(); }
            return;
        }
        statusView.setText("✅ Hammasi tayyor! Bot avtomatik ishlaydi.");
    }

    private boolean isAccessibilityEnabled() {
        try {
            String flat = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return flat != null && flat.contains(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    private void requestOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } else {
            Toast.makeText(this, "Overlay allaqachon yoqilgan", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestProjection() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Avval Overlay ruxsatini yoqing!", Toast.LENGTH_LONG).show();
            return;
        }
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PROJECTION && resultCode == Activity.RESULT_OK && data != null) {
            BotState.get().projectionResultCode = resultCode;
            BotState.get().projectionResultData = data;

            DisplayMetrics dm = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            BotState.get().screenWidth = dm.widthPixels;
            BotState.get().screenHeight = dm.heightPixels;
            BotState.get().screenDensity = dm.densityDpi;

            Intent svc = new Intent(this, ScreenCaptureService.class);
            Intent overlay = new Intent(this, FloatingOverlayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
                startForegroundService(overlay);
            } else {
                startService(svc);
                startService(overlay);
            }

            DebugOverlay.attach(getApplicationContext());

            Toast.makeText(this, "Tayyor! O'yinni oching va START bosing.", Toast.LENGTH_LONG).show();
            moveTaskToBack(true);
        } else if (requestCode == REQ_PROJECTION) {
            Toast.makeText(this, "Ekran olish bekor qilindi", Toast.LENGTH_SHORT).show();
        }
    }
}

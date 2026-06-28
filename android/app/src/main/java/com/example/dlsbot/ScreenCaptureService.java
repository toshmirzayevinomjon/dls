package com.example.dlsbot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;

public class ScreenCaptureService extends Service {

    private static final String CHANNEL_ID = "dls_capture";
    private static final int NOTIF_ID = 7001;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());

        BotState state = BotState.get();
        if (state.projectionResultData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        MediaProjectionManager mgr =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mgr.getMediaProjection(state.projectionResultCode, state.projectionResultData);
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { releaseCapture(); }
        }, new Handler(getMainLooper()));

        state.mediaProjection = projection;
        startCaptureLoop(state);
        return START_STICKY;
    }

    private void startCaptureLoop(BotState state) {
        captureThread = new HandlerThread("CaptureThread");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        final int width = state.screenWidth;
        final int height = state.screenHeight;
        final int density = state.screenDensity;

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        virtualDisplay = projection.createVirtualDisplay(
                "DLSBotCapture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, captureHandler);

        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null) return;

                Image.Plane[] planes = image.getPlanes();
                ByteBuffer buffer = planes[0].getBuffer();
                int pixelStride = planes[0].getPixelStride();
                int rowStride = planes[0].getRowStride();
                int rowPadding = rowStride - pixelStride * width;

                Bitmap bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
                bitmap.copyPixelsFromBuffer(buffer);

                Bitmap cropped = bitmap;
                if (rowPadding != 0) {
                    cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height);
                    bitmap.recycle();
                }
                BotState.get().setLatestFrame(cropped);
            } catch (Exception ignored) {
            } finally {
                if (image != null) image.close();
            }
        }, captureHandler);
    }

    private void releaseCapture() {
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        try { if (imageReader != null) imageReader.close(); } catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        BotState.get().mediaProjection = null;
        if (captureThread != null) captureThread.quitSafely();
    }

    private Notification buildNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("DLS Bot")
                .setContentText("Ekran tahlil qilinmoqda")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "DLS Capture", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    @Override
    public void onDestroy() {
        releaseCapture();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}

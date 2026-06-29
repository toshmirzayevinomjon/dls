package com.example.dlsbot;

import android.graphics.Bitmap;
import android.util.Base64;

import com.example.dlsbot.net.ApiClient;
import com.example.dlsbot.net.ButtonCoord;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * AI kalibrlash — skrinshotni BACKEND (Railway) ga yuboradi. Groq kaliti
 * faqat Railway'da, ilovada SAQLANMAYDI.
 */
public final class GroqVision {

    public interface Cb {
        void onResult(List<ButtonCoord> buttons);
        void onError(String error);
    }

    private GroqVision() {}

    public static void autoCalibrate(Bitmap frame, String backendUrl, Cb cb) {
        try {
            if (backendUrl == null || backendUrl.isEmpty()) {
                cb.onError("Backend URL yo'q");
                return;
            }
            Bitmap small = scaleTo(frame, 768);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 80, baos);
            String b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
            String dataUrl = "data:image/jpeg;base64," + b64;

            JSONObject body = new JSONObject().put("image", dataUrl);
            String base = backendUrl.endsWith("/") ? backendUrl : backendUrl + "/";

            Request req = new Request.Builder()
                    .url(base + "api/bot/vision-calibrate")
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                    .build();

            try (Response resp = ApiClient.rawHttpClient().newCall(req).execute()) {
                String s = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    cb.onError("server " + resp.code() + ": " + shorten(s));
                    return;
                }
                JSONObject coords = new JSONObject(s);
                List<ButtonCoord> list = new ArrayList<>();
                for (String name : new String[]{"joystick", "A_shoot", "B_pass", "C_thru"}) {
                    if (coords.has(name)) {
                        JSONObject o = coords.getJSONObject(name);
                        ButtonCoord bc = new ButtonCoord();
                        bc.name = name;
                        bc.x = (float) o.getDouble("x");
                        bc.y = (float) o.getDouble("y");
                        if (bc.x >= 0 && bc.x <= 1 && bc.y >= 0 && bc.y <= 1) list.add(bc);
                    }
                }
                if (list.isEmpty()) { cb.onError("tugma aniqlanmadi"); return; }
                cb.onResult(list);
            }
        } catch (Exception e) {
            cb.onError(e.getMessage());
        }
    }

    private static Bitmap scaleTo(Bitmap src, int maxW) {
        if (src.getWidth() <= maxW) return src;
        float r = (float) maxW / src.getWidth();
        return Bitmap.createScaledBitmap(src, maxW, Math.round(src.getHeight() * r), true);
    }

    private static String shorten(String s) {
        if (s == null) return "";
        return s.length() > 160 ? s.substring(0, 160) : s;
    }
}

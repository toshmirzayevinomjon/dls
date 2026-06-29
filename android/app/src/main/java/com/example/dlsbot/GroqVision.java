package com.example.dlsbot;

import android.graphics.Bitmap;
import android.util.Base64;

import com.example.dlsbot.net.ApiClient;
import com.example.dlsbot.net.ButtonCoord;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Groq Vision AI — skrinshotdan o'yin tugmalarini topib, normallashtirilgan
 * koordinatalarini qaytaradi. Kalit telefon ichida saqlanadi (standalone).
 */
public final class GroqVision {

    public interface Cb {
        void onResult(List<ButtonCoord> buttons);
        void onError(String error);
    }

    private static final String URL = "https://api.groq.com/openai/v1/chat/completions";
    // Vision (multimodal) model. Kerak bo'lsa boshqasiga almashtiring.
    private static final String MODEL = "meta-llama/llama-4-scout-17b-16e-instruct";

    private static final String PROMPT =
            "This is a Dream League Soccer mobile game screenshot during a match. "
            + "Find the on-screen touch controls and return ONLY JSON with normalized "
            + "coordinates (0.0 to 1.0, x from left, y from top) of their CENTERS: "
            + "joystick (round movement control on the LEFT), A_shoot (shoot button, "
            + "usually bottom-right), B_pass (pass button near shoot), C_thru "
            + "(through-pass button near shoot). Respond EXACTLY as: "
            + "{\"joystick\":{\"x\":0.0,\"y\":0.0},\"A_shoot\":{\"x\":0.0,\"y\":0.0},"
            + "\"B_pass\":{\"x\":0.0,\"y\":0.0},\"C_thru\":{\"x\":0.0,\"y\":0.0}}. No other text.";

    private GroqVision() {}

    public static void autoCalibrate(Bitmap frame, String apiKey, Cb cb) {
        try {
            Bitmap small = scaleTo(frame, 768);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 80, baos);
            String b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
            String dataUrl = "data:image/jpeg;base64," + b64;

            JSONObject txt = new JSONObject().put("type", "text").put("text", PROMPT);
            JSONObject img = new JSONObject().put("type", "image_url")
                    .put("image_url", new JSONObject().put("url", dataUrl));
            JSONArray content = new JSONArray().put(txt).put(img);
            JSONObject msg = new JSONObject().put("role", "user").put("content", content);
            JSONObject body = new JSONObject()
                    .put("model", MODEL)
                    .put("temperature", 0.1)
                    .put("max_tokens", 400)
                    .put("messages", new JSONArray().put(msg));

            Request req = new Request.Builder()
                    .url(URL)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(body.toString(),
                            MediaType.parse("application/json")))
                    .build();

            try (Response resp = ApiClient.rawHttpClient().newCall(req).execute()) {
                String s = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    cb.onError("Groq " + resp.code() + ": " + shorten(s));
                    return;
                }
                JSONObject root = new JSONObject(s);
                String contentStr = root.getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content");

                int a = contentStr.indexOf('{');
                int b = contentStr.lastIndexOf('}');
                if (a < 0 || b < 0 || b <= a) {
                    cb.onError("JSON topilmadi: " + shorten(contentStr));
                    return;
                }
                JSONObject coords = new JSONObject(contentStr.substring(a, b + 1));

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
                if (list.isEmpty()) { cb.onError("Tugma aniqlanmadi"); return; }
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

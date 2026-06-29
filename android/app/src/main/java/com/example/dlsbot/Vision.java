package com.example.dlsbot;

import com.example.dlsbot.net.BotSettings;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenCV ko'rish yordamchisi:
 *  - #3 multi-scale matchTemplate (to'p yaqin/uzoqligiga moslashish)
 *  - #10 ROI (faqat kerakli hududda qidirish -> tezroq, kam qizish)
 */
public final class Vision {

    private Vision() {}

    public static class Match {
        public final float cx, cy, score, scale;
        public Match(float cx, float cy, float score, float scale) {
            this.cx = cx; this.cy = cy; this.score = score; this.scale = scale;
        }
    }

    /**
     * Sahna (kulrang, processWidth x processHeight) ichidan shablonni topadi.
     * @param sceneGray  640x360 kulrang kadr
     * @param tmpl       shablon (kulrang)
     * @param threshold  minimal ishonch
     * @param scales     sinab ko'riladigan o'lchamlar (null -> faqat 1.0)
     * @param roi        qidiruv hududi (null -> butun sahna)
     * @return eng yaxshi moslik (sahna koordinatasida) yoki null
     */
    public static Match matchMultiScale(Mat sceneGray, Mat tmpl, float threshold,
                                        float[] scales, Rect roi) {
        if (tmpl == null || tmpl.empty()) return null;

        Mat searchArea = sceneGray;
        int offX = 0, offY = 0;
        Mat roiMat = null;
        if (roi != null) {
            Rect safe = clampRect(roi, sceneGray.cols(), sceneGray.rows());
            if (safe.width <= 0 || safe.height <= 0) return null;
            roiMat = new Mat(sceneGray, safe);
            searchArea = roiMat;
            offX = safe.x;
            offY = safe.y;
        }

        float[] useScales = (scales != null && scales.length > 0) ? scales : new float[]{1.0f};

        Match best = null;
        Mat scaled = new Mat();
        Mat result = new Mat();

        for (float s : useScales) {
            int tw = Math.max(1, Math.round(tmpl.cols() * s));
            int th = Math.max(1, Math.round(tmpl.rows() * s));
            if (tw > searchArea.cols() || th > searchArea.rows()) continue;

            Imgproc.resize(tmpl, scaled, new Size(tw, th));

            int rc = searchArea.cols() - tw + 1;
            int rr = searchArea.rows() - th + 1;
            if (rc <= 0 || rr <= 0) continue;
            result.create(rr, rc, CvType.CV_32FC1);

            Imgproc.matchTemplate(searchArea, scaled, result, Imgproc.TM_CCOEFF_NORMED);
            Core.MinMaxLocResult mm = Core.minMaxLoc(result);

            if (mm.maxVal >= threshold && (best == null || mm.maxVal > best.score)) {
                Point loc = mm.maxLoc;
                float cx = (float) (offX + loc.x + tw / 2.0);
                float cy = (float) (offY + loc.y + th / 2.0);
                best = new Match(cx, cy, (float) mm.maxVal, s);
            }
        }

        scaled.release();
        result.release();
        if (roiMat != null) roiMat.release();
        return best;
    }

    // Yagona o'lchamli (1.0) tez moslik — banner/menyu uchun
    public static Match matchSingle(Mat sceneGray, Mat tmpl, float threshold, Rect roi) {
        return matchMultiScale(sceneGray, tmpl, threshold, new float[]{1.0f}, roi);
    }

    // Sozlamadagi normallashtirilgan ROI'ni piksel Rect'ga aylantiradi
    public static Rect roiFromSettings(BotSettings s) {
        if (s == null || s.roi == null) return null;
        int x = Math.round(s.roi.x * s.processWidth);
        int y = Math.round(s.roi.y * s.processHeight);
        int w = Math.round(s.roi.w * s.processWidth);
        int h = Math.round(s.roi.h * s.processHeight);
        return new Rect(x, y, w, h);
    }

    private static Rect clampRect(Rect r, int maxW, int maxH) {
        int x = Math.max(0, Math.min(r.x, maxW - 1));
        int y = Math.max(0, Math.min(r.y, maxH - 1));
        int w = Math.min(r.width, maxW - x);
        int h = Math.min(r.height, maxH - y);
        return new Rect(x, y, w, h);
    }

    // #9/#10 — To'pni SHABLONSIZ topish: dumaloq (oq) shaklni HoughCircles bilan aniqlash
    public static Match detectBallByColor(Mat sceneGray, Rect roi) {
        Mat area = sceneGray;
        int offX = 0, offY = 0;
        Mat roiMat = null;
        if (roi != null) {
            Rect safe = clampRect(roi, sceneGray.cols(), sceneGray.rows());
            if (safe.width <= 0 || safe.height <= 0) return null;
            roiMat = new Mat(sceneGray, safe);
            area = roiMat;
            offX = safe.x;
            offY = safe.y;
        }

        Mat blurred = new Mat();
        Imgproc.medianBlur(area, blurred, 5);
        Mat circles = new Mat();
        // param: dp=1, minDist=20, Canny=100, accum=18, minR=3, maxR=16
        Imgproc.HoughCircles(blurred, circles, Imgproc.HOUGH_GRADIENT,
                1.0, 20, 100, 18, 3, 16);

        Match best = null;
        if (!circles.empty() && circles.cols() > 0) {
            double[] c = circles.get(0, 0); // x, y, r
            if (c != null && c.length >= 2) {
                float cx = (float) (offX + c[0]);
                float cy = (float) (offY + c[1]);
                best = new Match(cx, cy, 0.75f, 1.0f);
            }
        }
        blurred.release();
        circles.release();
        if (roiMat != null) roiMat.release();
        return best;
    }

    // #21 To'pni OQ RANGI bo'yicha topish (HSV) — dumaloq oq shaklni qidiradi.
    public static Match detectBallByWhiteColor(Mat rgba, Rect roi) {
        if (rgba == null || rgba.empty()) return null;
        Mat area = rgba;
        int offX = 0, offY = 0;
        Mat roiMat = null;
        if (roi != null) {
            Rect safe = clampRect(roi, rgba.cols(), rgba.rows());
            if (safe.width <= 0 || safe.height <= 0) return null;
            roiMat = new Mat(rgba, safe);
            area = roiMat;
            offX = safe.x;
            offY = safe.y;
        }

        Mat rgb = new Mat(), hsv = new Mat(), mask = new Mat();
        Imgproc.cvtColor(area, rgb, Imgproc.COLOR_RGBA2RGB);
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV);
        // Oq: past to'yinganlik (S), yuqori yorqinlik (V)
        Core.inRange(hsv, new Scalar(0, 0, 200), new Scalar(180, 45, 255), mask);
        Imgproc.medianBlur(mask, mask, 3);

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(mask, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        Match best = null;
        double bestScore = 0;
        for (MatOfPoint c : contours) {
            double a = Imgproc.contourArea(c);
            if (a < 8 || a > 500) continue;
            MatOfPoint2f c2f = new MatOfPoint2f(c.toArray());
            double peri = Imgproc.arcLength(c2f, true);
            c2f.release();
            if (peri <= 0) continue;
            double circ = 4 * Math.PI * a / (peri * peri); // dumaloqlik
            if (circ < 0.6) continue;
            Moments m = Imgproc.moments(c);
            if (m.get_m00() == 0) continue;
            float cx = (float) (offX + m.get_m10() / m.get_m00());
            float cy = (float) (offY + m.get_m01() / m.get_m00());
            if (circ > bestScore) {
                bestScore = circ;
                best = new Match(cx, cy, (float) Math.min(0.9, 0.6 + circ * 0.3), 1.0f);
            }
        }

        rgb.release(); hsv.release(); mask.release(); hierarchy.release();
        for (MatOfPoint c : contours) c.release();
        if (roiMat != null) roiMat.release();
        return best;
    }

    // #2 yordamchi: ikki nuqta orasidagi masofa (piksel)
    public static float distance(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2, dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}

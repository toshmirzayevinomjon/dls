package com.example.dlsbot;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * #1 — O'rnatilgan DLS paket nomini avtomatik topish.
 */
public final class AppUtil {

    private AppUtil() {}

    public static List<String> detectInstalledDls(Context ctx, List<String> candidates) {
        PackageManager pm = ctx.getPackageManager();
        LinkedHashSet<String> found = new LinkedHashSet<>();

        // 1) Ma'lum nomlarni tekshiramiz
        if (candidates != null) {
            for (String p : candidates) {
                if (isInstalled(pm, p)) found.add(p);
            }
        }

        // 2) Barcha ilovalarni skanерlab, DLS'ga o'xshashlarini qo'shamiz
        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if (ai.packageName == null) continue;
                String p = ai.packageName.toLowerCase();
                if (p.contains("firsttouchgames") || p.contains("dreamleague") || p.contains("dls")) {
                    found.add(ai.packageName);
                }
            }
        } catch (Exception ignored) {}

        return new ArrayList<>(found);
    }

    private static boolean isInstalled(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

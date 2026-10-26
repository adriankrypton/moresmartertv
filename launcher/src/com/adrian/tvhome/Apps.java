package com.adrian.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Installed launchable apps and the user's favourites list. */
final class Apps {
    static final class App {
        String pkg, label;
        Intent launch;
        Drawable banner, icon;
    }

    /** Package -> JustWatch provider short names (country specific codes included). */
    static final String[][] PROVIDERS = {
            {"com.netflix.ninja", "nfx"},
            {"com.amazon.amazonvideo.livingroom", "amp,prv"},
            {"com.disney.disneyplus", "dnp"},
            {"com.apple.atve.androidtv.appletv", "atp"},
            {"com.canal.android.canal", "cpd"},
            {"com.cbs.ott", "pmp"},
            {"com.cbs.ca", "pmp"},
            {"com.crunchyroll.crunchyroid", "cru"},
            {"de.prosiebensat1digital.seventv", "jyn"},
            {"de.sky.online", "sko"},
            {"de.sky.bw", "skg"},
            {"de.cbc.tvnow.androidtv", "rps"},
            {"de.telekom.magentatv.tv", "etv"},
            {"de.swr.avp.ard.tv", "dem"},
            {"com.zdf.android.mediathek", "zdf"},
            {"com.dazn", "dzn"},
            {"com.wbd.stream", "mxx"},
    };

    /** Preferred order for the very first favourites list. */
    private static final List<String> PREFERRED = Arrays.asList(
            "com.google.android.youtube.tv", "com.amazon.firetv.youtube", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus", "de.exaring.waipu", "de.exaring.waipu.firetv.live", "com.apple.atve.androidtv.appletv", "com.canal.android.canal",
            "com.wbd.stream", "com.cbs.ott", "de.sky.online", "de.prosiebensat1digital.seventv",
            "de.cbc.tvnow.androidtv", "com.spotify.tv.android", "com.plexapp.android", "org.jellyfin.androidtv",
            "org.xbmc.kodi", "com.dazn");

    /** Never shown: legal notices, app stores, settings and launchers. */
    private static final List<String> HIDDEN = Arrays.asList("com.google.android.tv.axel",
            "com.android.vending", "com.amazon.venezia", "com.android.settings", "com.android.tv.settings",
            "com.amazon.tv.settings.v2", "com.amazon.tv.launcher", "com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher");

    /** Preinstalled apps that are real media apps even when they were never updated. */
    private static final List<String> MEDIA_SYSTEM_APPS = Arrays.asList("com.google.android.youtube.tv",
            "com.google.android.youtube.tvmusic", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            "com.amazon.firetv.youtube", "com.amazon.bueller.music", "com.amazon.avod");

    /** Apps the user installed (or updated store apps), not system tools. */
    private static boolean isRealApp(android.content.pm.ApplicationInfo ai) {
        boolean system = (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
        boolean updated = (ai.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        return !system || updated || MEDIA_SYSTEM_APPS.contains(ai.packageName);
    }

    static List<App> all(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, App> map = new LinkedHashMap<>();
        collect(c, pm, map, Intent.CATEGORY_LEANBACK_LAUNCHER, true);
        collect(c, pm, map, Intent.CATEGORY_LAUNCHER, false);
        List<App> list = new ArrayList<>(map.values());
        Collections.sort(list, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return list;
    }

    private static void collect(Context c, PackageManager pm, Map<String, App> map, String cat, boolean leanback) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || map.containsKey(pkg) || HIDDEN.contains(pkg)) continue;
            if (!isRealApp(ri.activityInfo.applicationInfo)) continue;
            App a = new App();
            a.pkg = pkg;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.launch = new Intent(Intent.ACTION_MAIN).addCategory(cat)
                    .setComponent(new ComponentName(pkg, ri.activityInfo.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            if (leanback) a.banner = ri.activityInfo.loadBanner(pm);
            if (a.banner == null) {
                try { a.banner = pm.getApplicationBanner(pkg); } catch (Exception ignored) { }
            }
            a.icon = ri.loadIcon(pm);
            map.put(pkg, a);
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    static List<String> favorites(Context c, List<App> all) {
        List<String> installed = new ArrayList<>();
        for (App a : all) installed.add(a.pkg);
        String saved = prefs(c).getString("favs", null);
        List<String> favs = new ArrayList<>();
        if (saved != null) {
            for (String p : TextUtils.split(saved, ",")) if (installed.contains(p)) favs.add(p);
            return favs;
        }
        for (String p : PREFERRED) if (installed.contains(p) && favs.size() < 8) favs.add(p);
        for (App a : all) {
            if (favs.size() >= 6) break;
            if (a.banner != null && !favs.contains(a.pkg) && !a.pkg.contains("settings")) favs.add(a.pkg);
        }
        save(c, favs);
        return favs;
    }

    static void save(Context c, List<String> favs) {
        prefs(c).edit().putString("favs", TextUtils.join(",", favs)).apply();
    }

    static void toggle(Context c, List<App> all, String pkg) {
        List<String> f = favorites(c, all);
        if (f.contains(pkg)) f.remove(pkg); else f.add(pkg);
        save(c, f);
    }

    static App find(List<App> all, String pkg) {
        for (App a : all) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    /** JustWatch provider codes for the streaming apps that are installed. */
    static List<String> providerCodes(List<App> all) {
        List<String> codes = new ArrayList<>();
        for (String[] p : PROVIDERS) {
            if (find(all, p[0]) == null) continue;
            for (String code : p[1].split(",")) if (!codes.contains(code)) codes.add(code);
        }
        return codes;
    }

    /** Installed package that serves a provider code, or null. */
    static String packageFor(List<App> all, String code) {
        for (String[] p : PROVIDERS) {
            if (Arrays.asList(p[1].split(",")).contains(code) && find(all, p[0]) != null) return p[0];
        }
        return null;
    }
}

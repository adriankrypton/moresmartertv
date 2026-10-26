package com.adrian.tvhome;

import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String JW = "https://apis.justwatch.com/graphql";
    private static final String IMG = "https://images.justwatch.com";
    private static final long RELEASES_TTL = 3 * 3600_000L;
    private static final long WEATHER_TTL = 20 * 60_000L;
    private TextView weatherPlace;

    static final class Title {
        String name, desc, meta, poster, backdrop, provider, pkg;
    }

    private Typeface thin, regular, medium, bold;
    private ImageView bgA, bgB;
    private boolean bgAFront = true;
    private String bgUrl;
    private LinearLayout content, header, appsRow, postersRow, relSection, infoPanel, weatherCard, weatherHours;
    private HorizontalScrollView appsScroll, postersScroll;
    private TextView clock, date, ramText, diskText, appsTitle, relTitle;
    private TextView wTemp, wGlyph, wCond, wHiLo;
    private View hero;
    private FrameLayout pageHolder;
    private AppsPage appsPage;
    private SmartHomePage homePage;
    private int page = 0;
    private final List<View> tabViews = new ArrayList<>();
    private TextView infoKicker, infoTitle, infoMeta, infoDesc;
    private RingView ramRing, diskRing;

    private List<Apps.App> apps = new ArrayList<>();
    private final List<Title> titles = new ArrayList<>();
    private boolean releasesMode;
    private long releasesLoaded, weatherLoaded;
    private SharedPreferences prefs;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateClock();
            updateStats();
            Net.MAIN.postDelayed(this, 5000);
        }
    };
    private Runnable pendingBg;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Net.cacheDir = getCacheDir();
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        thin = Typeface.create("sans-serif-thin", Typeface.NORMAL);
        regular = Typeface.create("sans-serif", Typeface.NORMAL);
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        bold = Typeface.create("sans-serif", Typeface.BOLD);
        setContentView(build());
        loadCachedReleases();
        ensurePlace();
        HomeWatch.start(this);
    }

    /**
     * First run asks for the weather location. Installs from before the setup existed
     * keep their place: it is recovered from the cached forecast's coordinates.
     */
    private void ensurePlace() {
        if (SetupActivity.hasPlace(this)) return;
        String cached = prefs.getString("weather2", null);
        if (cached == null) { startActivity(new Intent(this, SetupActivity.class)); return; }
        Net.IO.execute(() -> {
            try {
                JSONObject w = new JSONObject(cached);
                String lat = String.valueOf(w.getDouble("latitude")), lon = String.valueOf(w.getDouble("longitude"));
                JSONObject g = new JSONObject(Net.text("https://api.bigdatacloud.net/data/reverse-geocode-client?latitude="
                        + lat + "&longitude=" + lon + "&localityLanguage=de", null));
                String name = g.optString("city", "");
                if (name.isEmpty()) name = g.optString("locality", "Zuhause");
                prefs.edit().putString(SetupActivity.PLACE, name).putString(SetupActivity.LAT, lat)
                        .putString(SetupActivity.LON, lon).apply();
                Net.MAIN.post(() -> showWeather(cached));
            } catch (Exception e) {
                Net.MAIN.post(() -> startActivity(new Intent(this, SetupActivity.class)));
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        HomeWatch.hideOverlay();
        apps = Apps.all(this);
        renderApps();
        Net.MAIN.removeCallbacks(ticker);
        ticker.run();
        long now = System.currentTimeMillis();
        if (now - weatherLoaded > WEATHER_TTL || !prefs.contains("weather2")) loadWeather();
        if (now - releasesLoaded > RELEASES_TTL) loadReleases();
        if (page == 2) homePage.onShow();
        else if (page == 0 && !releasesMode) focusFirstApp();
    }

    @Override protected void onPause() {
        super.onPause();
        HomeWatch.snapshot(getWindow().getDecorView());
        Net.MAIN.removeCallbacks(ticker);
        homePage.onHide();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: back to the top.
        showPage(0);
        appsScroll.smoothScrollTo(0, 0);
        postersScroll.smoothScrollTo(0, 0);
        focusFirstApp();
    }

    @Override public void onBackPressed() {
        if (page != 0) { showPage(0); tabViews.get(0).requestFocus(); return; }
        if (releasesMode) focusFirstApp();
        // A launcher never finishes on back.
    }

    // ---------------------------------------------------------------- layout

    private int dp(float v) { return Ui.dp(this, v); }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF23262F, 0xFF121318, 0xFF08080A});
        root.setBackground(base);

        bgA = Ui.fillImage(this, ImageView.ScaleType.CENTER_CROP);
        bgB = Ui.fillImage(this, ImageView.ScaleType.CENTER_CROP);
        bgB.setAlpha(0f);
        root.addView(bgA);
        root.addView(bgB);

        View scrimLeft = new View(this);
        scrimLeft.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xE6050507, 0x99050507, 0x1A050507}));
        root.addView(scrimLeft, new FrameLayout.LayoutParams(-1, -1));
        View scrimBottom = new View(this);
        scrimBottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xF5050507, 0xB3050507, 0x00050507}));
        FrameLayout.LayoutParams sb = new FrameLayout.LayoutParams(-1, dp(360));
        sb.gravity = Gravity.BOTTOM;
        root.addView(scrimBottom, sb);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(56), dp(24), 0, 0);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        // Taller than the screen so the lower rows still exist after sliding up.
        root.addView(content, new FrameLayout.LayoutParams(-1, dp(1000)));

        // Top bar (tabs + system stats), then clock/date and the weather card
        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(0, 0, dp(56), 0);
        header.setClipChildren(false);

        FrameLayout top = new FrameLayout(this);
        top.setClipChildren(false);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setBackground(Ui.pill(this, 22));
        tabs.setPadding(dp(5), 0, dp(5), 0);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setClipToPadding(false);
        tabs.setClipChildren(false);
        tabs.addView(tab(getString(R.string.tab_home), 0, null));
        tabs.addView(tab(getString(R.string.all_apps), 1, null));
        tabs.addView(tab("Home", 2, null));
        TextView gear = (TextView) tab("⚙", -1,
                new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        gear.setTextSize(19);
        gear.setPadding(dp(13), 0, dp(13), dp(2));
        gear.setContentDescription(getString(R.string.settings));
        tabs.addView(gear);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, dp(46), Gravity.START | Gravity.CENTER_VERTICAL);
        tp.leftMargin = 0;
        top.addView(tabs, tp);

        LinearLayout stats = new LinearLayout(this);
        stats.setGravity(Gravity.CENTER_VERTICAL);
        stats.setBackground(Ui.pill(this, 22));
        stats.setPadding(dp(12), 0, dp(16), 0);
        ramRing = new RingView(this);
        diskRing = new RingView(this);
        ramText = stat(stats, ramRing, getString(R.string.ram), 0);
        diskText = stat(stats, diskRing, getString(R.string.disk), 16);
        top.addView(stats, new FrameLayout.LayoutParams(-2, dp(46), Gravity.END | Gravity.CENTER_VERTICAL));
        header.addView(top, new LinearLayout.LayoutParams(-1, dp(46)));

        LinearLayout heroRow = new LinearLayout(this);
        hero = heroRow;
        heroRow.setGravity(Gravity.BOTTOM);
        LinearLayout clockCol = new LinearLayout(this);
        clockCol.setOrientation(LinearLayout.VERTICAL);
        clock = Ui.text(this, 76, 0xFFFFFFFF, thin);
        clockCol.addView(clock);
        date = Ui.text(this, 20, 0xF2FFFFFF, medium);
        clockCol.addView(date, margins(-2, -2, 0, 6, 0, 4));
        heroRow.addView(clockCol, new LinearLayout.LayoutParams(0, -2, 1f));
        heroRow.addView(buildWeatherCard(), new LinearLayout.LayoutParams(dp(372), -2));
        header.addView(hero, margins(-1, -2, 0, 14, 0, 0));
        content.addView(header);

        // Sub-pages ("Alle Apps", "Home") share the tab bar and background
        pageHolder = new FrameLayout(this);
        pageHolder.setPadding(0, dp(18), dp(56), 0);
        pageHolder.setClipChildren(false);
        pageHolder.setClipToPadding(false);
        pageHolder.setVisibility(View.GONE);
        appsPage = new AppsPage(this);
        homePage = new SmartHomePage(this);
        pageHolder.addView(appsPage, new FrameLayout.LayoutParams(-1, -1));
        pageHolder.addView(homePage, new FrameLayout.LayoutParams(-1, -1));
        content.addView(pageHolder, new LinearLayout.LayoutParams(-1, dp(424)));

        appsTitle = Ui.text(this, 20, 0xFFFFFFFF, medium);
        appsTitle.setText(R.string.favorites);
        content.addView(appsTitle, margins(-2, -2, 0, 26, 0, 4));

        appsScroll = hScroll();
        appsRow = row();
        appsScroll.addView(appsRow);
        content.addView(appsScroll, new LinearLayout.LayoutParams(-1, -2));

        relTitle = Ui.text(this, 20, 0xFFFFFFFF, medium);
        relTitle.setText(R.string.releases);
        relTitle.setVisibility(View.GONE);
        content.addView(relTitle, margins(-2, -2, 0, 18, 0, 4));

        relSection = new LinearLayout(this);
        relSection.setOrientation(LinearLayout.HORIZONTAL);
        relSection.setClipChildren(false);
        relSection.setClipToPadding(false);
        LayoutTransition lt = new LayoutTransition();
        lt.setDuration(220);
        relSection.setLayoutTransition(lt);
        content.addView(relSection, new LinearLayout.LayoutParams(-1, -2));

        infoPanel = new LinearLayout(this);
        infoPanel.setOrientation(LinearLayout.VERTICAL);
        infoPanel.setBackground(Ui.pill(this, 18));
        infoPanel.setPadding(dp(22), dp(18), dp(22), dp(16));
        infoPanel.setVisibility(View.GONE);
        infoKicker = Ui.text(this, 11, 0xCCFFFFFF, bold);
        infoKicker.setLetterSpacing(0.12f);
        infoKicker.setAllCaps(true);
        infoTitle = Ui.text(this, 22, 0xFFFFFFFF, bold);
        infoTitle.setMaxLines(1);
        infoTitle.setEllipsize(TextUtils.TruncateAt.END);
        infoMeta = Ui.text(this, 13, 0xB3FFFFFF, medium);
        infoDesc = Ui.text(this, 13.5f, 0xE6FFFFFF, regular);
        infoDesc.setLineSpacing(dp(3), 1f);
        infoDesc.setMaxLines(3);
        infoDesc.setEllipsize(TextUtils.TruncateAt.END);
        infoDesc.setIncludeFontPadding(true);
        TextView credit = Ui.text(this, 10, 0x80FFFFFF, regular);
        credit.setText(R.string.credit);
        infoPanel.addView(infoKicker);
        infoPanel.addView(infoTitle, margins(-1, -2, 0, 8, 0, 0));
        infoPanel.addView(infoMeta, margins(-1, -2, 0, 6, 0, 0));
        infoPanel.addView(infoDesc, margins(-1, -2, 0, 10, 0, 0));
        infoPanel.addView(new View(this), margins(1, 0, 0, 0, 0, 0, 1f));
        infoPanel.addView(credit, margins(-1, -2, 0, 8, 0, 0));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(340), dp(196));
        ip.setMargins(0, dp(10), dp(24), 0);
        relSection.addView(infoPanel, ip);

        postersScroll = hScroll();
        // Clip so posters scrolled to the left never paint over the info panel.
        postersScroll.setClipChildren(true);
        postersScroll.setClipToPadding(true);
        // Clip via outline as well: elevated (focused) cards otherwise escape the parent clip
        postersScroll.setOutlineProvider(android.view.ViewOutlineProvider.BOUNDS);
        postersScroll.setClipToOutline(true);
        postersRow = row();
        postersRow.setPadding(dp(10), dp(12), dp(56), dp(12));
        postersScroll.addView(postersRow);
        relSection.addView(postersScroll, new LinearLayout.LayoutParams(0, -2, 1f));
        return root;
    }

    private TextView stat(LinearLayout parent, RingView ring, String label, int leftDp) {
        parent.addView(ring, margins(dp(28), dp(28), leftDp, 0, 0, 0));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView l = Ui.text(this, 11, 0xB3FFFFFF, medium);
        l.setText(label);
        l.setSingleLine(true);
        col.setMinimumWidth(dp(64));
        TextView v = Ui.text(this, 15, 0xFFFFFFFF, medium);
        col.addView(l);
        col.addView(v, margins(-2, -2, 0, 2, 0, 0));
        parent.addView(col, margins(-2, -2, 10, 0, 0, 0));
        return v;
    }

    private LinearLayout.LayoutParams margins(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private LinearLayout.LayoutParams margins(int w, int h, int l, int t, int r, int b, float weight) {
        LinearLayout.LayoutParams p = margins(w, h, l, t, r, b);
        p.weight = weight;
        return p;
    }

    private HorizontalScrollView hScroll() {
        HorizontalScrollView s = new EdgeScroll(this, dp(64));
        s.setHorizontalScrollBarEnabled(false);
        s.setClipChildren(false);
        s.setClipToPadding(false);
        s.setOverScrollMode(View.OVER_SCROLL_NEVER);
        s.setSmoothScrollingEnabled(true);
        return s;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        r.setPadding(0, dp(12), dp(56), dp(12));
        return r;
    }

    // ---------------------------------------------------------------- apps

    private void renderApps() {
        View focused = getCurrentFocus();
        String focusedPkg = focused != null && focused.getParent() == appsRow ? (String) focused.getTag() : null;
        appsRow.removeAllViews();
        List<String> favs = Apps.favorites(this, apps);
        for (String pkg : favs) {
            Apps.App a = Apps.find(apps, pkg);
            if (a != null) appsRow.addView(appTile(a));
        }
        appsRow.addView(actionTile("__all", "⋯", getString(R.string.all_apps),
                null), tileParams(108));
        lockRowEnds(appsRow);
        if (focusedPkg != null) {
            for (int i = 0; i < appsRow.getChildCount(); i++) {
                if (focusedPkg.equals(appsRow.getChildAt(i).getTag())) appsRow.getChildAt(i).requestFocus();
            }
        }
    }

    private View actionTile(String tag, String glyphText, String label, final Intent intent) {
        FrameLayout t = Ui.card(this, 108, 108, 16);
        t.setTag(tag);
        TextView glyph = Ui.centered(this, glyphText, 34);
        glyph.setPadding(0, 0, 0, dp(18));
        TextView lbl = Ui.text(this, 12, 0xD9FFFFFF, medium);
        lbl.setText(label);
        lbl.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.bottomMargin = dp(16);
        t.addView(glyph);
        t.addView(lbl, lp);
        t.setContentDescription(label);
        t.setOnClickListener(v -> { if (intent == null) { showPage(1); appsPage.firstFocus().requestFocus(); } else launch(intent); });
        wireAppFocus(t);
        return t;
    }

    /** Stop LEFT/RIGHT at the row ends instead of jumping to another row. */
    private static void lockRowEnds(LinearLayout row) {
        int n = row.getChildCount();
        if (n == 0) return;
        View first = row.getChildAt(0), last = row.getChildAt(n - 1);
        if (first.getId() == View.NO_ID) first.setId(View.generateViewId());
        if (last.getId() == View.NO_ID) last.setId(View.generateViewId());
        first.setNextFocusLeftId(first.getId());
        last.setNextFocusRightId(last.getId());
    }

    private LinearLayout.LayoutParams tileParams(int wDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(wDp), dp(108));
        p.rightMargin = dp(20);
        return p;
    }

    private View appTile(final Apps.App a) {
        FrameLayout t = Ui.card(this, 192, 108, 16);
        t.setTag(a.pkg);
        if (a.banner != null) {
            ImageView iv = Ui.fillImage(this, ImageView.ScaleType.FIT_XY);
            iv.setImageDrawable(a.banner);
            t.addView(iv);
        } else {
            Ui.iconTile(this, t, a.icon, a.label, 56);
        }
        t.setContentDescription(a.label);
        t.setOnClickListener(v -> launch(a.launch));
        t.setOnLongClickListener(v -> { editFavorite(a.pkg); return true; });
        wireAppFocus(t);
        t.setLayoutParams(tileParams(192));
        return t;
    }

    private void wireAppFocus(final View tile) {
        final View.OnFocusChangeListener inner = tile.getOnFocusChangeListener();
        tile.setOnFocusChangeListener((v, has) -> {
            inner.onFocusChange(v, has);
            if (has) setReleasesMode(false);
        });
    }

    private void focusFirstApp() {
        if (appsRow.getChildCount() > 0) appsRow.getChildAt(0).requestFocus();
    }

    private void launch(Intent i) {
        try { startActivity(i); } catch (Exception ignored) { }
    }

    private void editFavorite(final String pkg) {
        final List<String> favs = Apps.favorites(this, apps);
        final int idx = favs.indexOf(pkg);
        if (idx < 0) return;
        String[] items = {getString(R.string.move_left), getString(R.string.move_right), getString(R.string.remove_fav)};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Apps.find(apps, pkg).label)
                .setItems(items, (d, which) -> {
                    if (which == 0 && idx > 0) { favs.remove(idx); favs.add(idx - 1, pkg); }
                    if (which == 1 && idx < favs.size() - 1) { favs.remove(idx); favs.add(idx + 1, pkg); }
                    if (which == 2) favs.remove(idx);
                    Apps.save(this, favs);
                    renderApps();
                    int target = Math.max(0, Math.min(which == 0 ? idx - 1 : which == 1 ? idx + 1 : idx, appsRow.getChildCount() - 1));
                    appsRow.getChildAt(target).requestFocus();
                })
                .show();
    }

    // ---------------------------------------------------------------- mode switch

    private void showPage(int p) {
        if (p == page) return;
        page = p;
        boolean start = p == 0;
        int vis = start ? View.VISIBLE : View.GONE;
        hero.setVisibility(vis);
        appsTitle.setVisibility(vis);
        appsScroll.setVisibility(vis);
        relTitle.setVisibility(start && !titles.isEmpty() ? View.VISIBLE : View.GONE);
        relSection.setVisibility(vis);
        pageHolder.setVisibility(start ? View.GONE : View.VISIBLE);
        appsPage.setVisibility(p == 1 ? View.VISIBLE : View.GONE);
        homePage.setVisibility(p == 2 ? View.VISIBLE : View.GONE);
        if (p == 1) appsPage.refresh();
        if (p == 2) homePage.onShow(); else homePage.onHide();
        if (start) renderApps();
        for (int i = 0; i < tabViews.size(); i++) tabViews.get(i).setSelected(i == p);
        View page = start ? hero : pageHolder;
        page.setAlpha(0f);
        page.animate().alpha(1f).setDuration(220).start();
    }

    private void setReleasesMode(boolean on) {
        if (on == releasesMode) return;
        releasesMode = on;
        float shift = on ? -(appsTitle.getTop() - content.getPaddingTop()) : 0;
        DecelerateInterpolator di = new DecelerateInterpolator(1.6f);
        content.animate().translationY(shift).setDuration(380).setInterpolator(di).start();
        header.animate().alpha(on ? 0f : 1f).setDuration(260).start();
        infoPanel.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) {
            postersScroll.smoothScrollTo(0, 0);
            if (!titles.isEmpty()) setBackdrop(titles.get(0).backdrop, 0);
        }
    }

    // ---------------------------------------------------------------- clock & stats

    private void updateClock() {
        Date now = new Date();
        boolean h24 = DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now));
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM");
        String d = new SimpleDateFormat(pattern, Locale.getDefault()).format(now);
        date.setText(d.substring(0, 1).toUpperCase(Locale.getDefault()) + d.substring(1));
    }

    private void updateStats() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        float ram = 1f - (float) mi.availMem / mi.totalMem;
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        float disk = 1f - (float) fs.getAvailableBytes() / fs.getTotalBytes();
        ramRing.setValue(ram);
        diskRing.setValue(disk);
        ramText.setText(Math.round(ram * 100) + " %");
        diskText.setText(Math.round(disk * 100) + " %");
    }

    // ---------------------------------------------------------------- weather

    private View buildWeatherCard() {
        weatherCard = new LinearLayout(this);
        weatherCard.setOrientation(LinearLayout.VERTICAL);
        weatherCard.setBackground(Ui.pill(this, 20));
        weatherCard.setPadding(dp(20), dp(14), dp(20), dp(14));
        weatherCard.setVisibility(View.INVISIBLE);

        LinearLayout now = new LinearLayout(this);
        now.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView place = Ui.text(this, 12, 0xB3FFFFFF, medium);
        weatherPlace = place;
        wTemp = Ui.text(this, 40, 0xFFFFFFFF, thin);
        left.addView(place);
        left.addView(wTemp, margins(-2, -2, 0, 4, 0, 0));
        now.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        wGlyph = Ui.text(this, 26, 0xFFFFFFFF, regular);
        wCond = Ui.text(this, 13, 0xFFFFFFFF, medium);
        wHiLo = Ui.text(this, 12, 0xB3FFFFFF, regular);
        right.addView(wGlyph);
        right.addView(wCond, margins(-2, -2, 0, 4, 0, 0));
        right.addView(wHiLo, margins(-2, -2, 0, 3, 0, 0));
        now.addView(right);
        weatherCard.addView(now);

        View rule = new View(this);
        rule.setBackgroundColor(0x1FFFFFFF);
        weatherCard.addView(rule, margins(-1, 1, 0, 12, 0, 10));

        weatherHours = new LinearLayout(this);
        weatherCard.addView(weatherHours, new LinearLayout.LayoutParams(-1, -2));
        return weatherCard;
    }

    private void loadWeather() {
        String cached = prefs.getString("weather2", null);
        if (cached != null) showWeather(cached);
        Net.IO.execute(() -> {
            try {
                String lat = prefs.getString(SetupActivity.LAT, null), lon = prefs.getString(SetupActivity.LON, null);
                if (lat == null || lon == null) return;
                String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                        + "&current=temperature_2m,weather_code,is_day,apparent_temperature,relative_humidity_2m,wind_speed_10m"
                        + "&hourly=temperature_2m,weather_code,is_day"
                        + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                        + "&timezone=Europe%2FBerlin&forecast_days=2";
                final String s = Net.text(url, null);
                new JSONObject(s).getJSONObject("current");
                prefs.edit().putString("weather2", s).apply();
                Net.MAIN.post(() -> { weatherLoaded = System.currentTimeMillis(); showWeather(s); });
            } catch (Exception ignored) { }
        });
    }

    private void showWeather(String json) {
        try {
            JSONObject w = new JSONObject(json);
            JSONObject cur = w.getJSONObject("current");
            weatherPlace.setText("➤  " + prefs.getString(SetupActivity.PLACE, ""));
            int code = cur.getInt("weather_code");
            wTemp.setText(Math.round(cur.getDouble("temperature_2m")) + "°");
            wGlyph.setText(weatherGlyph(code, cur.optInt("is_day", 1) == 1));
            wCond.setText(weatherText(code));
            JSONObject daily = w.getJSONObject("daily");
            wHiLo.setText("H " + Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)) + "°   T "
                    + Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)) + "°");

            // Next five hours, starting with the current hour.
            JSONObject hourly = w.getJSONObject("hourly");
            JSONArray times = hourly.getJSONArray("time");
            String nowHour = new SimpleDateFormat("yyyy-MM-dd'T'HH:00", Locale.ROOT).format(new Date());
            int start = 0;
            for (int i = 0; i < times.length(); i++) {
                if (times.getString(i).compareTo(nowHour) >= 0) { start = i; break; }
            }
            weatherHours.removeAllViews();
            for (int i = start; i < Math.min(start + 5, times.length()); i++) {
                LinearLayout col = new LinearLayout(this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setGravity(Gravity.CENTER_HORIZONTAL);
                TextView h = Ui.text(this, 11.5f, 0xB3FFFFFF, medium);
                h.setText(i == start ? "Jetzt" : times.getString(i).substring(11, 13) + " Uhr");
                TextView g = Ui.text(this, 17, 0xFFFFFFFF, regular);
                g.setText(weatherGlyph(hourly.getJSONArray("weather_code").getInt(i),
                        hourly.getJSONArray("is_day").getInt(i) == 1));
                TextView t = Ui.text(this, 13, 0xFFFFFFFF, medium);
                t.setText(Math.round(hourly.getJSONArray("temperature_2m").getDouble(i)) + "°");
                col.addView(h);
                col.addView(g, margins(-2, -2, 0, 6, 0, 0));
                col.addView(t, margins(-2, -2, 0, 5, 0, 0));
                weatherHours.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            weatherCard.setVisibility(View.VISIBLE);
        } catch (Exception ignored) { }
    }

    static String weatherText(int code) {
        if (code == 0) return "Klar";
        if (code == 1) return "Überwiegend klar";
        if (code == 2) return "Teils bewölkt";
        if (code == 3) return "Bedeckt";
        if (code == 45 || code == 48) return "Nebel";
        if (code >= 51 && code <= 57) return "Nieselregen";
        if (code >= 61 && code <= 67) return "Regen";
        if (code >= 71 && code <= 77) return "Schnee";
        if (code >= 80 && code <= 82) return "Regenschauer";
        if (code == 85 || code == 86) return "Schneeschauer";
        if (code >= 95) return "Gewitter";
        return "";
    }

    private View tab(String label, final int pageIdx, final Intent target) {
        TextView t = Ui.text(this, 15, 0xFFFFFFFF, medium);
        t.setShadowLayer(0, 0, 0, 0);
        t.setText(label);
        t.setPadding(dp(20), 0, dp(20), 0);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(36)));
        t.setFocusable(true);
        t.setClickable(true);
        GradientDrawable on = new GradientDrawable();
        on.setColor(0xFFFFFFFF);
        on.setCornerRadius(dp(17));
        GradientDrawable sel = new GradientDrawable();
        sel.setColor(0x33FFFFFF);
        sel.setCornerRadius(dp(17));
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, on);
        bg.addState(new int[]{android.R.attr.state_selected}, sel);
        t.setBackground(bg);
        t.setTextColor(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}}, new int[]{0xFF111114, 0xFFFFFFFF}));
        t.setOnClickListener(v -> {
            if (target != null) { launch(target); return; }
            showPage(pageIdx);
            if (pageIdx == 0) focusFirstApp();
            else if (pageIdx == 1) appsPage.firstFocus().requestFocus();
            else homePage.firstFocus().requestFocus();
        });
        t.setOnFocusChangeListener((v, has) -> {
            if (!has) return;
            setReleasesMode(false);
            if (pageIdx >= 0) showPage(pageIdx);
        });
        tabViews.add(t);
        return t;
    }

    static String weatherGlyph(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";
        if (code <= 2) return day ? "🌤️" : "☁️";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "❄️";
        if (code >= 95) return "⛈️";
        if (code >= 80) return "🌦️";
        return "🌧️";
    }

    // ---------------------------------------------------------------- releases

    private void loadCachedReleases() {
        String c = prefs.getString("releases", null);
        if (c != null) {
            try { showReleases(parse(new JSONObject(c))); } catch (Exception ignored) { }
        }
    }

    private void loadReleases() {
        final List<String> codes = Apps.providerCodes(apps);
        Locale loc = Locale.getDefault();
        final String country = loc.getCountry().isEmpty() ? "DE" : loc.getCountry().toUpperCase(Locale.ROOT);
        final String lang = loc.getLanguage().isEmpty() ? "de" : loc.getLanguage();
        int minYear = Calendar.getInstance().get(Calendar.YEAR) - 1;
        StringBuilder pk = new StringBuilder();
        for (String c : codes) pk.append(pk.length() == 0 ? "" : ",").append('"').append(c).append('"');
        String filter = "objectTypes: [MOVIE, SHOW], releaseYear: {min: " + minYear + "}"
                + (codes.isEmpty() ? "" : ", packages: [" + pk + "]");
        String q = "query { popularTitles(country: " + country + ", first: 24, sortBy: POPULAR, filter: {" + filter + "}) "
                + "{ edges { node { objectType content(country: " + country + ", language: \"" + lang + "\") "
                + "{ title shortDescription originalReleaseYear runtime genres { shortName } posterUrl backdrops { backdropUrl } } "
                + "offers(country: " + country + ", platform: WEB) { package { shortName clearName } } } } } }";
        final String body;
        try { body = new JSONObject().put("query", q).toString(); } catch (Exception e) { return; }
        Net.IO.execute(() -> {
            try {
                JSONObject res = new JSONObject(Net.text(JW, body));
                final List<Title> list = parse(res);
                if (list.isEmpty()) return;
                prefs.edit().putString("releases", res.toString()).apply();
                Net.MAIN.post(() -> {
                    releasesLoaded = System.currentTimeMillis();
                    relTitle.setText(codes.isEmpty() ? R.string.trending : R.string.releases);
                    showReleases(list);
                });
            } catch (Exception ignored) { }
        });
    }

    private List<Title> parse(JSONObject res) throws Exception {
        List<Title> out = new ArrayList<>();
        JSONArray edges = res.getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges");
        for (int i = 0; i < edges.length(); i++) {
            JSONObject n = edges.getJSONObject(i).getJSONObject("node");
            JSONObject c = n.getJSONObject("content");
            if (c.isNull("posterUrl")) continue;
            Title t = new Title();
            t.name = c.getString("title");
            t.desc = c.optString("shortDescription", "");
            t.poster = IMG + c.getString("posterUrl").replace("{profile}", "s332").replace("{format}", "webp");
            JSONArray bd = c.optJSONArray("backdrops");
            if (bd != null && bd.length() > 0) {
                t.backdrop = IMG + bd.getJSONObject(0).getString("backdropUrl").replace("{profile}", "s1440").replace("{format}", "webp");
            }
            boolean show = "SHOW".equals(n.optString("objectType"));
            StringBuilder meta = new StringBuilder();
            meta.append(c.optInt("originalReleaseYear"));
            meta.append("  ·  ").append(show ? (Locale.getDefault().getLanguage().equals("de") ? "Serie" : Locale.getDefault().getLanguage().equals("fr") ? "Série" : "Series")
                    : (Locale.getDefault().getLanguage().equals("de") ? "Film" : "Film"));
            int rt = c.optInt("runtime", 0);
            if (!show && rt > 0) meta.append("  ·  ").append(rt / 60).append("h").append(String.format(Locale.ROOT, "%02d", rt % 60));
            t.meta = meta.toString();
            JSONArray offers = n.optJSONArray("offers");
            if (offers != null) {
                for (int k = 0; k < offers.length() && t.pkg == null; k++) {
                    JSONObject p = offers.getJSONObject(k).getJSONObject("package");
                    String pkg = Apps.packageFor(apps, p.getString("shortName"));
                    if (t.provider == null) t.provider = p.getString("clearName");
                    if (pkg != null) { t.pkg = pkg; t.provider = p.getString("clearName"); }
                }
            }
            out.add(t);
        }
        return out;
    }

    private void showReleases(List<Title> list) {
        if (sameTitles(list)) return;
        titles.clear();
        titles.addAll(list);
        boolean hadFocus = postersRow.hasFocus();
        postersRow.removeAllViews();
        relTitle.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        for (final Title t : list) {
            FrameLayout card = Ui.card(this, 124, 186, 12);
            ImageView iv = Ui.fillImage(this, ImageView.ScaleType.CENTER_CROP);
            card.addView(iv);
            Net.into(iv, t.poster);
            card.setContentDescription(t.name);
            final View.OnFocusChangeListener inner = card.getOnFocusChangeListener();
            card.setOnFocusChangeListener((v, has) -> {
                inner.onFocusChange(v, has);
                if (has) { setReleasesMode(true); showInfo(t); }
            });
            card.setOnClickListener(v -> openTitle(t));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(124), dp(186));
            p.rightMargin = dp(18);
            postersRow.addView(card, p);
            Net.bitmap(t.backdrop, b -> { });  // warm the cache
        }
        lockRowEnds(postersRow);
        if (hadFocus && postersRow.getChildCount() > 0) postersRow.getChildAt(0).requestFocus();
        if (!releasesMode && !list.isEmpty()) setBackdrop(list.get(0).backdrop, 0);
    }

    private boolean sameTitles(List<Title> list) {
        if (list.size() != titles.size()) return false;
        for (int i = 0; i < list.size(); i++) {
            if (!list.get(i).poster.equals(titles.get(i).poster)) return false;
        }
        return true;
    }

    private void showInfo(Title t) {
        infoKicker.setText(t.provider != null ? getString(R.string.new_on, t.provider) : getString(R.string.releases));
        infoTitle.setText(t.name);
        infoMeta.setText(t.meta);
        infoDesc.setText(t.desc);
        setBackdrop(t.backdrop, 300);
    }

    private void openTitle(Title t) {
        if (t.pkg == null) return;
        Apps.App a = Apps.find(apps, t.pkg);
        if (a != null) launch(a.launch);
    }

    private void setBackdrop(final String url, long delay) {
        if (pendingBg != null) Net.MAIN.removeCallbacks(pendingBg);
        if (url == null || url.equals(bgUrl)) return;
        pendingBg = () -> Net.bitmap(url, b -> {
            if (!url.equals(bgUrl) && url.equals(latestBg)) {
                bgUrl = url;
                prefs.edit().putString("bg", url).apply();
                final ImageView in = bgAFront ? bgB : bgA;
                final ImageView out = bgAFront ? bgA : bgB;
                bgAFront = !bgAFront;
                in.setImageBitmap(b);
                in.animate().cancel();
                out.animate().cancel();
                in.animate().alpha(1f).setDuration(700).start();
                out.animate().alpha(0f).setDuration(700).start();
                // Slow Ken Burns drift on the incoming image.
                ObjectAnimator kb = ObjectAnimator.ofPropertyValuesHolder(in,
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.07f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.07f));
                kb.setDuration(30000);
                kb.setInterpolator(new LinearInterpolator());
                kb.start();
            }
        });
        latestBg = url;
        Net.MAIN.postDelayed(pendingBg, delay);
    }

    private String latestBg;

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        // Menu key on a favourite opens the edit dialog, like a long press.
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) {
            View f = getCurrentFocus();
            if (f != null && f.getParent() == appsRow && f.getTag() instanceof String && !((String) f.getTag()).startsWith("__")) {
                editFavorite((String) f.getTag());
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }
}

package com.adrian.airplay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** AirPlay settings, laid out like the tvOS settings list. */
public class SettingsActivity extends Activity {
    static final String EXTRA_NAME = "name", EXTRA_ID = "id", EXTRA_NETWORK = "network", EXTRA_IP = "ip";

    private SharedPreferences prefs;
    private Typeface bold, medium, regular;
    private LinearLayout list;

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    static boolean enabled(SharedPreferences p) { return p.getBoolean("enabled", true); }
    static boolean audio(SharedPreferences p) { return p.getBoolean("audio", true); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("airplay", MODE_PRIVATE);
        bold = Typeface.create("sans-serif", Typeface.BOLD);
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        regular = Typeface.create("sans-serif", Typeface.NORMAL);

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF26221F, 0xFF161414, 0xFF0A0909}));

        TextView title = text(34, 0xE6FFFFFF, bold);
        title.setText("AirPlay-Einstellungen");
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2);
        tp.setMargins(dp(80), dp(52), 0, 0);
        root.addView(title, tp);

        // Left illustration: the two AirPlay glyphs, large and dimmed
        LinearLayout art = new LinearLayout(this);
        art.setGravity(Gravity.CENTER_VERTICAL);
        art.setAlpha(0.85f);
        ImageView a1 = new ImageView(this);
        a1.setImageResource(R.drawable.ic_airplay);
        ImageView a2 = new ImageView(this);
        a2.setImageResource(R.drawable.ic_airplay_audio);
        art.addView(a1, new LinearLayout.LayoutParams(dp(104), dp(104)));
        LinearLayout.LayoutParams a2p = new LinearLayout.LayoutParams(dp(96), dp(96));
        a2p.leftMargin = dp(28);
        art.addView(a2, a2p);
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL);
        ap.leftMargin = dp(70);
        root.addView(art, ap);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, dp(120), 0, dp(40));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(520), -1, Gravity.END);
        sp.rightMargin = dp(72);
        root.addView(scroll, sp);
        setContentView(root);
        render();
    }

    private void render() {
        list.removeAllViews();
        Intent in = getIntent();
        section("AirPlay");
        View first = row("AirPlay", enabled(prefs) ? "Ein" : "Aus", false, v -> {
            prefs.edit().putBoolean("enabled", !enabled(prefs)).apply();
            render();
            list.getChildAt(1).requestFocus();
        });
        row("Ton über den Fernseher", audio(prefs) ? "Ein" : "Aus", false, v -> {
            prefs.edit().putBoolean("audio", !audio(prefs)).apply();
            render();
            list.getChildAt(2).requestFocus();
        });
        row("Gerätename", trim(in.getStringExtra(EXTRA_NAME)), true, v -> {
            try { startActivity(new Intent("android.settings.DEVICE_NAME")); }
            catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        });
        row("Über AirPlay", null, true, v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Über AirPlay")
                .setMessage("AirPlay-Empfänger für Google TV\nVersion 1.0\n\nBildschirmsynchronisierung mit H.264-Video "
                        + "und AAC-Ton. Der Empfang ist aktiv, solange die AirPlay-App geöffnet ist.\n\n"
                        + "Protokoll basiert auf java-airplay-lib (MIT).")
                .setPositiveButton("OK", null).show());

        section("Netzwerk");
        row("WLAN", in.getStringExtra(EXTRA_NETWORK), true, v -> {
            try { startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); } catch (Exception ignored) { }
        });
        row("IP-Adresse", in.getStringExtra(EXTRA_IP), false, null);
        row("Port", String.valueOf(RtspServer.PORT), false, null);
        row("Geräte-ID", in.getStringExtra(EXTRA_ID), false, null);

        TextView foot = text(15, 0x80FFFFFF, regular);
        foot.setText("Spiegle den Bildschirm deines iPhone, iPad oder Mac auf diesen Fernseher. "
                + "Beide Geräte müssen im selben WLAN sein.");
        foot.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.setMargins(dp(20), dp(26), dp(20), 0);
        list.addView(foot, fp);
        if (getCurrentFocus() == null) first.requestFocus();
    }

    private static String trim(String s) { return s == null ? "" : s.replace(" ", "").trim(); }

    private void section(String label) {
        TextView t = text(16, 0x80FFFFFF, medium);
        t.setText(label);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.setMargins(dp(20), list.getChildCount() == 0 ? 0 : dp(28), 0, dp(10));
        list.addView(t, p);
    }

    private View row(String label, String value, boolean chevron, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(20), 0, dp(18), 0);
        r.setFocusable(true);
        r.setClickable(true);
        GradientDrawable on = new GradientDrawable();
        on.setColor(0xFFE8E2EC);
        on.setCornerRadius(dp(7));
        GradientDrawable off = new GradientDrawable();
        off.setColor(0x14FFFFFF);
        off.setCornerRadius(dp(7));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, on);
        bg.addState(new int[]{}, off);
        r.setBackground(bg);
        ColorStateList main = new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{0xFF1C1B1F, 0xFFFFFFFF});
        ColorStateList dim = new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{0xFF4A4650, 0x99FFFFFF});

        TextView l = text(18, 0xFFFFFFFF, bold);
        l.setText(label);
        l.setTextColor(main);
        l.setDuplicateParentStateEnabled(true);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        if (value != null) {
            TextView v = text(17, 0x99FFFFFF, medium);
            v.setText(value);
            v.setTextColor(dim);
            v.setDuplicateParentStateEnabled(true);
            r.addView(v);
        }
        if (chevron) {
            TextView c = text(22, 0x99FFFFFF, regular);
            c.setText("›");
            c.setTextColor(dim);
            c.setDuplicateParentStateEnabled(true);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
            cp.leftMargin = dp(12);
            r.addView(c, cp);
        }
        r.setOnFocusChangeListener((v, has) -> r.animate().scaleX(has ? 1.02f : 1f).scaleY(has ? 1.02f : 1f).setDuration(120).start());
        if (click != null) r.setOnClickListener(click);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(52));
        p.bottomMargin = dp(8);
        list.addView(r, p);
        return r;
    }

    private TextView text(float sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        return t;
    }
}

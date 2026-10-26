package com.adrian.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full-screen accessory control in the style of Apple Home: a large vertical
 * slider changed with ▲/▼, name and status on the left, quick presets on the right.
 */
final class ControlSheet {
    interface Format { String text(double v); }
    interface Change { void apply(double v); }

    private static final int INK = 0xFF1C1C1E, INK2 = 0xFF6E6E73;
    /** RenderEffect blur exists from Android 12 on. */
    private static final boolean BLUR = android.os.Build.VERSION.SDK_INT >= 31;

    private final Activity act;
    private final Dialog dialog;
    private final double min, max, step;
    private double value;
    private final Format format;
    private final Change change;
    private final int[] fillColors;
    private final FrameLayout slider;
    private final View fill;
    private final TextView valueText, statusText;
    private final LinearLayout presets;
    private final Runnable commit;
    private FrameLayout root;
    private View cardView;
    private boolean closing;

    private int dp(float v) { return Ui.dp(act, v); }

    ControlSheet(Activity a, int icon, String name, String status, double value, double min, double max,
                 double step, int[] fillColors, Format format, Change change) {
        act = a;
        this.value = value;
        this.min = min;
        this.max = max;
        this.step = step;
        this.format = format;
        this.change = change;
        this.fillColors = fillColors;
        commit = () -> change.apply(this.value);

        Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
        Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);

        dialog = new Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        Window w = dialog.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setWindowAnimations(0); // we animate ourselves

        // Light frosted card, like the Home panel
        root = new FrameLayout(a);
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        GradientDrawable card = new GradientDrawable();
        card.setColor(0xE6E8EAED);
        card.setCornerRadius(dp(32));
        card.setStroke(dp(1), 0x66FFFFFF);
        row.setBackground(card);
        row.setElevation(dp(24));
        row.setPadding(dp(44), dp(36), dp(44), dp(36));
        root.addView(row, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        cardView = row;
        dialog.setOnKeyListener((d, code, e) -> {
            if (code == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) { dismiss(); return true; }
            return code == KeyEvent.KEYCODE_BACK;
        });

        // Left: icon, name, status, hint
        LinearLayout left = new LinearLayout(a);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.START);
        FrameLayout badge = new FrameLayout(a);
        GradientDrawable bb = new GradientDrawable();
        bb.setShape(GradientDrawable.OVAL);
        bb.setColor(0xFFFFFFFF);
        bb.setStroke(dp(1), 0x14000000);
        badge.setBackground(bb);
        ImageView iv = new ImageView(a);
        iv.setImageResource(icon);
        badge.addView(iv, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        left.addView(badge, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView n = text(28, INK, bold);
        n.setText(name);
        statusText = text(17, INK2, regular);
        statusText.setText(status);
        TextView hint = text(13, 0x991C1C1E, regular);
        hint.setText("▲ ▼  einstellen   ·   Zurück  schließen");
        left.addView(n, lp(-2, -2, 0, 18, 0, 0));
        left.addView(statusText, lp(-2, -2, 0, 6, 0, 0));
        left.addView(hint, lp(-2, -2, 0, 28, 0, 0));
        row.addView(left, new LinearLayout.LayoutParams(dp(250), -2));

        // Center: big pill slider
        slider = new FrameLayout(a);
        slider.setFocusable(true);
        slider.setClipToOutline(true);
        Ui.round(slider, dp(44));
        GradientDrawable track = new GradientDrawable();
        track.setColor(0x1F000000);
        track.setCornerRadius(dp(44));
        slider.setBackground(track);
        fill = new View(a);
        GradientDrawable fd = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, fillColors);
        fill.setBackground(fd);
        slider.addView(fill, new FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM));
        valueText = text(30, 0xFF1C1C1E, bold);
        valueText.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        vp.topMargin = dp(26);
        slider.addView(valueText, vp);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(44));
        ring.setStroke(dp(3), 0xFF1C1C1E);
        StateListDrawable fg = new StateListDrawable();
        fg.addState(new int[]{android.R.attr.state_focused}, ring);
        slider.setForeground(fg);
        slider.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_UP) { set(this.value + step, true); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) { set(this.value - step, true); return true; }
            return false;
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(136), dp(330));
        sp.setMargins(dp(36), 0, dp(44), 0);
        row.addView(slider, sp);

        // Right: presets
        presets = new LinearLayout(a);
        presets.setOrientation(LinearLayout.VERTICAL);
        presets.setClipChildren(false);
        row.addView(presets, new LinearLayout.LayoutParams(dp(220), -2));

        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> {
            Net.MAIN.removeCallbacks(commit);
            if (BLUR) act.getWindow().getDecorView().setRenderEffect(null);
        });
        render();
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private TextView text(float sp, int color, Typeface tf) {
        TextView t = new TextView(act);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        return t;
    }

    void setStatus(String s) { statusText.setText(s); }

    /** Adds a quick-select pill on the right. */
    ControlSheet preset(String label, Integer dot, Runnable action) {
        LinearLayout b = new LinearLayout(act);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(dp(18), 0, dp(18), 0);
        GradientDrawable on = new GradientDrawable();
        on.setColor(0xFFFFFFFF);
        on.setCornerRadius(dp(14));
        GradientDrawable off = new GradientDrawable();
        off.setColor(0x12000000);
        off.setCornerRadius(dp(14));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, on);
        bg.addState(new int[]{}, off);
        b.setBackground(bg);
        if (dot != null) {
            View d = new View(act);
            GradientDrawable dd = new GradientDrawable();
            dd.setShape(GradientDrawable.OVAL);
            dd.setColor(dot);
            dd.setStroke(dp(1), 0x40000000);
            d.setBackground(dd);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(18), dp(18));
            dlp.rightMargin = dp(12);
            b.addView(d, dlp);
        }
        TextView t = text(16, 0xFFFFFFFF, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setText(label);
        t.setDuplicateParentStateEnabled(true);
        t.setTextColor(INK);
        b.addView(t);
        b.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f)
                .translationZ(has ? dp(10) : 0).setDuration(120).start());
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(210), dp(44));
        p.bottomMargin = dp(10);
        presets.addView(b, p);
        return this;
    }

    /** Sets the slider value; when {@code apply} the change is sent after a short pause. */
    void set(double v, boolean apply) {
        value = Math.max(min, Math.min(max, Math.round(v / step) * step));
        render();
        if (apply) {
            Net.MAIN.removeCallbacks(commit);
            Net.MAIN.postDelayed(commit, 450);
        }
    }

    double value() { return value; }

    private void render() {
        valueText.setText(format.text(value));
        float frac = (float) ((value - min) / (max - min));
        slider.post(() -> {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) fill.getLayoutParams();
            p.height = Math.round(slider.getHeight() * frac);
            fill.setLayoutParams(p);
            // Value sits on the fill once it is high enough, otherwise above it in white.
            valueText.setTextColor(INK);
        });
    }

    /** Animates dim + blur of the launcher behind the card from {@code from} to {@code to} (0..1). */
    private void backdrop(float from, float to, long ms, Runnable end) {
        final View behind = act.getWindow().getDecorView();
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(from, to);
        va.setDuration(ms);
        va.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
        va.addUpdateListener(an -> {
            float f = (float) an.getAnimatedValue();
            // Without blur (Android < 12, e.g. Fire TV) dim a bit more instead
            root.setBackgroundColor(Color.argb(Math.round(f * (BLUR ? 90 : 150)), 0, 0, 0));
            if (BLUR) {
                float r = f * dp(14);
                behind.setRenderEffect(r < 0.5f ? null : android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP));
            }
        });
        if (end != null) va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { end.run(); }
        });
        va.start();
    }

    void show() {
        cardView.setAlpha(0f);
        cardView.setScaleX(0.92f);
        cardView.setScaleY(0.92f);
        cardView.setTranslationY(dp(18));
        dialog.show();
        backdrop(0f, 1f, 320, null);
        cardView.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0).setDuration(320)
                .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0.9f, 0.25f, 1f)).start();
        slider.requestFocus();
    }

    void dismiss() {
        if (closing) return;
        closing = true;
        cardView.animate().alpha(0f).scaleX(0.95f).scaleY(0.95f).translationY(dp(10)).setDuration(200)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.4f)).start();
        backdrop(1f, 0f, 220, dialog::dismiss);
    }
}

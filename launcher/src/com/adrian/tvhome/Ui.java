package com.adrian.tvhome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

final class Ui {
    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static GradientDrawable pill(Context c, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x73202024);
        g.setCornerRadius(dp(c, radiusDp));
        g.setStroke(dp(c, 1), 0x26FFFFFF);
        return g;
    }

    static TextView text(Context c, float sp, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        t.setShadowLayer(dp(c, 6), 0, dp(c, 1), 0x99000000);
        return t;
    }

    static void round(View v, final float radiusPx) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });
        v.setClipToOutline(true);
    }

    /** Focusable rounded card that scales up and gets a white ring when focused. */
    static FrameLayout card(Context c, int wDp, int hDp, int radiusDp) {
        final FrameLayout f = new FrameLayout(c);
        f.setFocusable(true);
        f.setClickable(true);
        f.setLayoutParams(new FrameLayout.LayoutParams(dp(c, wDp), dp(c, hDp)));
        final float r = dp(c, radiusDp);
        round(f, r);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2A2A30);
        bg.setCornerRadius(r);
        f.setBackground(bg);
        final GradientDrawable ring = new GradientDrawable();
        ring.setColor(Color.TRANSPARENT);
        ring.setCornerRadius(r);
        ring.setStroke(dp(c, 3), 0xFFFFFFFF);
        final Drawable none = new GradientDrawable();
        f.setForeground(none);
        final float lift = dp(c, 14);
        f.setOnFocusChangeListener((v, has) -> {
            f.setForeground(has ? ring : none);
            f.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .translationZ(has ? lift : 0).setDuration(170)
                    .setInterpolator(new DecelerateInterpolator()).start();
        });
        return f;
    }

    /**
     * Rebuilds a container's children without focus escaping to another part of the
     * screen (which would e.g. switch tabs); focus returns to the same child index.
     */
    static void rebuildKeepingFocus(android.view.ViewGroup g, Runnable rebuild) {
        View f = g.findFocus();
        int idx = -1;
        if (f != null) {
            while (f.getParent() != g && f.getParent() instanceof View) f = (View) f.getParent();
            idx = g.indexOfChild(f);
        }
        if (idx < 0) { rebuild.run(); return; }
        g.setFocusable(true);
        g.setDescendantFocusability(android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        g.requestFocus();
        rebuild.run();
        g.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (g.getChildCount() > 0) g.getChildAt(Math.min(idx, g.getChildCount() - 1)).requestFocus();
        g.setFocusable(false);
    }

    /**
     * Tile for apps without a TV banner: the logo large on a gradient in the colour of the logo,
     * name underneath.
     */
    static void iconTile(Context c, FrameLayout t, Drawable icon, String label, int iconDp) {
        int col = dominantColor(icon);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{shade(col, 0.55f), shade(col, 0.28f)});
        bg.setCornerRadius(dp(c, 16));
        t.setBackground(bg);
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(icon);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(dp(c, iconDp), dp(c, iconDp), Gravity.CENTER_HORIZONTAL);
        ip.topMargin = dp(c, 12);
        t.addView(iv, ip);
        TextView l = text(c, 13, 0xFFFFFFFF, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        l.setText(label);
        l.setSingleLine(true);
        l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        l.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.setMargins(dp(c, 10), 0, dp(c, 10), dp(c, 10));
        t.addView(l, lp);
    }

    /** Average colour of the opaque, not grey pixels of a drawable. */
    static int dominantColor(Drawable d) {
        try {
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(24, 24, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(b);
            d.setBounds(0, 0, 24, 24);
            d.draw(cv);
            long r = 0, g = 0, bl = 0, n = 0;
            for (int y = 0; y < 24; y++) for (int x = 0; x < 24; x++) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 200) continue;
                int pr = Color.red(p), pg = Color.green(p), pb = Color.blue(p);
                if (Math.max(pr, Math.max(pg, pb)) - Math.min(pr, Math.min(pg, pb)) < 24) continue; // skip greys
                r += pr; g += pg; bl += pb; n++;
            }
            if (n == 0) return 0xFF3A3F47;
            return Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
        } catch (Exception e) {
            return 0xFF3A3F47;
        }
    }

    private static int shade(int c, float f) {
        return Color.rgb(Math.round(Color.red(c) * f), Math.round(Color.green(c) * f), Math.round(Color.blue(c) * f));
    }

    static ImageView fillImage(Context c, ImageView.ScaleType st) {
        ImageView iv = new ImageView(c);
        iv.setScaleType(st);
        iv.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        return iv;
    }

    static TextView centered(Context c, String s, float sp) {
        TextView t = text(c, sp, 0xFFFFFFFF, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 10), 0, dp(c, 10), 0);
        t.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        return t;
    }
}

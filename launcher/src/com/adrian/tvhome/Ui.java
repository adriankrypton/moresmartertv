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

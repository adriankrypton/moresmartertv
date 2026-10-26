package com.adrian.tvhome;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** "Alle Apps" page: every installed app. OK launches, long-press toggles favourite. */
final class AppsPage extends ScrollView {
    private static final int COLS = 5;
    private final Activity act;
    private final GridLayout grid;
    private List<Apps.App> apps;

    private int dp(float v) { return Ui.dp(act, v); }

    AppsPage(Activity a) {
        super(a);
        act = a;
        setVerticalScrollBarEnabled(false);
        setClipChildren(false);
        setClipToPadding(false);
        setPadding(0, dp(8), 0, dp(40));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        TextView hint = Ui.text(a, 14, 0x99FFFFFF, Typeface.create("sans-serif", Typeface.NORMAL));
        hint.setText(R.string.all_apps_hint);
        hint.setIncludeFontPadding(true);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
        hp.setMargins(0, dp(4), 0, dp(18));
        col.addView(hint, hp);
        grid = new GridLayout(a);
        grid.setColumnCount(COLS);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        col.addView(grid);
        addView(col);
    }

    void refresh() {
        apps = Apps.all(act);
        render();
    }

    View firstFocus() { return grid.getChildCount() > 0 ? grid.getChildAt(0) : this; }

    private void render() {
        List<String> favs = Apps.favorites(act, apps);
        grid.removeAllViews();
        for (final Apps.App a : apps) {
            final FrameLayout t = Ui.card(act, 160, 90, 14);
            if (a.banner != null) {
                ImageView iv = Ui.fillImage(act, ImageView.ScaleType.FIT_XY);
                iv.setImageDrawable(a.banner);
                t.addView(iv);
            } else {
                ImageView iv = new ImageView(act);
                iv.setImageDrawable(a.icon);
                FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER_HORIZONTAL);
                ip.topMargin = dp(14);
                t.addView(iv, ip);
                TextView l = Ui.text(act, 12, 0xFFFFFFFF, Typeface.create("sans-serif-medium", Typeface.NORMAL));
                l.setText(a.label);
                l.setSingleLine(true);
                l.setEllipsize(TextUtils.TruncateAt.END);
                l.setGravity(Gravity.CENTER);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
                lp.setMargins(dp(8), 0, dp(8), dp(10));
                t.addView(l, lp);
            }
            if (favs.contains(a.pkg)) {
                TextView star = Ui.text(act, 14, 0xFFFFD54F, Typeface.DEFAULT_BOLD);
                star.setText("★");
                FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
                sp.setMargins(0, dp(6), dp(8), 0);
                t.addView(star, sp);
            }
            t.setContentDescription(a.label);
            t.setOnClickListener(v -> {
                try { act.startActivity(a.launch); } catch (Exception ignored) { }
            });
            t.setOnLongClickListener(v -> {
                Apps.toggle(act, apps, a.pkg);
                Ui.rebuildKeepingFocus(grid, this::render);
                return true;
            });
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width = dp(160);
            p.height = dp(90);
            p.setMargins(0, 0, dp(22), dp(22));
            grid.addView(t, p);
        }
    }
}

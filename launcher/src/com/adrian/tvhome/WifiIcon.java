package com.adrian.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Wi-Fi symbol like on tvOS: a dot and three arcs, lit according to signal strength. */
final class WifiIcon extends View {
    private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG), off = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    /** 0 = no signal, 1..3 = arcs lit; -1 = offline (crossed out look: all dim). */
    private int bars = 3;
    private boolean ethernet;

    WifiIcon(Context c) {
        super(c);
        on.setColor(0xFFFFFFFF);
        off.setColor(0x47FFFFFF);
        for (Paint p : new Paint[]{on, off}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(Ui.dp(c, 3f));
        }
    }

    void setLevel(int percent) {
        ethernet = false;
        bars = percent < 0 ? -1 : percent >= 66 ? 3 : percent >= 33 ? 2 : percent > 0 ? 1 : 0;
        invalidate();
    }

    void setEthernet() {
        ethernet = true;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (ethernet) {
            // Simple network plug: three boxes joined by lines
            Paint p = on;
            float s = w * 0.22f;
            c.drawRect(w / 2 - s / 2, h * 0.12f, w / 2 + s / 2, h * 0.12f + s, p);
            c.drawLine(w / 2, h * 0.12f + s, w / 2, h * 0.55f, p);
            c.drawLine(w * 0.2f, h * 0.55f, w * 0.8f, h * 0.55f, p);
            c.drawRect(w * 0.2f - s / 2, h * 0.7f, w * 0.2f + s / 2, h * 0.7f + s, p);
            c.drawRect(w * 0.8f - s / 2, h * 0.7f, w * 0.8f + s / 2, h * 0.7f + s, p);
            c.drawLine(w * 0.2f, h * 0.55f, w * 0.2f, h * 0.7f, p);
            c.drawLine(w * 0.8f, h * 0.55f, w * 0.8f, h * 0.7f, p);
            return;
        }
        float cx = w / 2, cy = h * 0.86f;
        // Dot
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(bars > 0 ? 0xFFFFFFFF : 0x47FFFFFF);
        c.drawCircle(cx, cy, on.getStrokeWidth() * 0.9f, dot);
        // Arcs, inner to outer
        for (int i = 1; i <= 3; i++) {
            float rad = h * 0.27f * i;
            r.set(cx - rad, cy - rad, cx + rad, cy + rad);
            c.drawArc(r, 225, 90, false, bars >= i + 0 && bars > 0 && i <= bars ? on : off);
        }
    }
}

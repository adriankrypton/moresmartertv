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
        // tvOS style: filled wedge plus two thick rounded bands, all within the view.
        // In units of H (wedge tip to top): wedge 0.33, gap 0.11, band 0.22.
        float band0 = 0, H = Math.min(h * 0.94f, w / 1.5f);
        float cx = w / 2, cy = (h + H) / 2;
        float r0 = 0.33f * H, g = 0.11f * H, b = 0.22f * H;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(bars >= 1 ? 0xFFFFFFFF : 0x47FFFFFF);
        p.setStyle(Paint.Style.FILL_AND_STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(b * 0.35f);
        android.graphics.Path wedge = new android.graphics.Path();
        float rw = r0 - b * 0.175f; // compensate for the rounding stroke
        r.set(cx - rw, cy - b * 0.175f - rw, cx + rw, cy - b * 0.175f + rw);
        wedge.moveTo(cx, cy - b * 0.175f);
        wedge.arcTo(r, 225, 90);
        wedge.close();
        c.drawPath(wedge, p);
        for (int i = 2; i <= 3; i++) {
            float rad = r0 + g * (i - 1) + b * (i - 2) + b / 2;
            Paint a = new Paint(Paint.ANTI_ALIAS_FLAG);
            a.setStyle(Paint.Style.STROKE);
            a.setStrokeCap(Paint.Cap.ROUND);
            a.setStrokeWidth(b);
            a.setColor(bars >= i ? 0xFFFFFFFF : 0x47FFFFFF);
            r.set(cx - rad, cy - rad, cx + rad, cy + rad);
            // Shorten the sweep a little so the round caps end on the wedge edges
            float trim = (float) Math.toDegrees((b / 2) / rad);
            c.drawArc(r, 225 + trim, 90 - 2 * trim, false, a);
        }
    }
}

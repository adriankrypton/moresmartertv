package com.adrian.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Small circular progress ring used for RAM / storage. */
final class RingView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float value;
    private boolean warnHigh = true;

    /** Red ring above 90 % (for RAM); off for values where high is good. */
    void setWarnHigh(boolean w) { warnHigh = w; }

    RingView(Context c) {
        super(c);
        float sw = Ui.dp(c, 3.5f);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(sw);
        track.setColor(0x40FFFFFF);
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(sw);
        fill.setStrokeCap(Paint.Cap.ROUND);
        fill.setColor(0xFFFFFFFF);
    }

    void setValue(float v) {
        value = Math.max(0f, Math.min(1f, v));
        fill.setColor(warnHigh && value > 0.9f ? 0xFFFF6B6B : 0xFFFFFFFF);
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        float inset = track.getStrokeWidth() / 2f + 1;
        r.set(inset, inset, getWidth() - inset, getHeight() - inset);
        c.drawArc(r, 0, 360, false, track);
        c.drawArc(r, -90, 360 * value, false, fill);
    }
}

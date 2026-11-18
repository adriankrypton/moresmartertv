package com.adrian.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.widget.TextView;

/**
 * Weather symbols in the style of tvOS / SF Symbols (multicolour): yellow sun, white cloud,
 * blue rain. Drawn in a 1x1 unit box, so they stay sharp at any size and never depend on the
 * emoji font of the device.
 */
final class WeatherIcon extends Drawable {
    private static final int SUN = 0xFFFFD60A, CLOUD = 0xFFFFFFFF, RAIN = 0xFF64D2FF,
            MOON = 0xFFD1D1D6, FOG = 0xCCFFFFFF, BOLT = 0xFFFFD60A;

    enum Kind { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, SHOWERS, SNOW, THUNDER }

    private final Kind kind;
    private final boolean day;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int alpha = 255;

    WeatherIcon(Kind kind, boolean day) {
        this.kind = kind;
        this.day = day;
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
    }

    static Kind kind(int code) {
        if (code == 0) return Kind.CLEAR;
        if (code <= 2) return Kind.PARTLY;
        if (code == 3) return Kind.CLOUDY;
        if (code == 45 || code == 48) return Kind.FOG;
        if (code >= 51 && code <= 57) return Kind.DRIZZLE;
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return Kind.SNOW;
        if (code >= 95) return Kind.THUNDER;
        if (code >= 80) return Kind.SHOWERS;
        return Kind.RAIN;
    }

    /** Puts the symbol for an Open-Meteo weather code at the start of a TextView. */
    static void apply(TextView tv, int code, boolean day, float sizeDp, float gapDp) {
        Context c = tv.getContext();
        int s = Ui.dp(c, sizeDp);
        Drawable d = new WeatherIcon(kind(code), day);
        d.setBounds(0, 0, s, s);
        tv.setCompoundDrawablesRelative(d, null, null, null);
        tv.setCompoundDrawablePadding(Ui.dp(c, gapDp));
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        float size = Math.min(b.width(), b.height());
        c.save();
        c.translate(b.left + (b.width() - size) / 2f, b.top + (b.height() - size) / 2f);
        c.scale(size, size);
        switch (kind) {
            case CLEAR:
                if (day) sun(c, 0.5f, 0.5f, 0.2f); else moon(c, 0.5f, 0.5f, 0.3f);
                break;
            case PARTLY:
                behindCloud(c, 0.36f, 0.36f, 0.14f, 0.08f, 0.1f, 0.86f);
                break;
            case CLOUDY:
                cloud(c, 0f, 0f, 1f);
                break;
            case FOG:
                cloud(c, 0f, -0.12f, 1f);
                lines(c, FOG, 0.18f, 0.82f, new float[]{0.76f, 0.88f});
                break;
            case DRIZZLE:
                cloud(c, 0f, -0.12f, 1f);
                drops(c, 0.045f);
                break;
            case RAIN:
                cloud(c, 0f, -0.12f, 1f);
                drops(c, 0.12f);
                break;
            case SHOWERS:
                behindCloud(c, 0.32f, 0.26f, 0.12f, 0.06f, -0.08f, 0.9f);
                drops(c, 0.1f);
                break;
            case SNOW:
                cloud(c, 0f, -0.12f, 1f);
                flakes(c);
                break;
            case THUNDER:
                cloud(c, 0f, -0.12f, 1f);
                bolt(c);
                break;
        }
        c.restore();
    }

    private int a(int color) {
        return (color & 0x00FFFFFF) | ((((color >>> 24) * alpha) / 255) << 24);
    }

    private void sun(Canvas c, float cx, float cy, float r) {
        fill.setColor(a(SUN));
        c.drawCircle(cx, cy, r, fill);
        stroke.setColor(a(SUN));
        stroke.setStrokeWidth(r * 0.36f);
        for (int i = 0; i < 8; i++) {
            double ang = Math.PI / 4 * i;
            float r1 = r * 1.5f, r2 = r * 2.05f;
            c.drawLine(cx + (float) Math.cos(ang) * r1, cy + (float) Math.sin(ang) * r1,
                    cx + (float) Math.cos(ang) * r2, cy + (float) Math.sin(ang) * r2, stroke);
        }
    }

    private void moon(Canvas c, float cx, float cy, float r) {
        Path outer = new Path(), bite = new Path();
        outer.addCircle(cx, cy, r, Path.Direction.CW);
        bite.addCircle(cx + r * 0.55f, cy - r * 0.4f, r * 0.82f, Path.Direction.CW);
        outer.op(bite, Path.Op.DIFFERENCE);
        fill.setColor(a(MOON));
        c.drawPath(outer, fill);
    }

    /** Cloud body built from three circles on a rounded base; dx/dy shift, s scales around the centre. */
    private void cloud(Canvas c, float dx, float dy, float s) {
        fill.setColor(a(CLOUD));
        c.drawPath(cloudPath(dx, dy, s), fill);
    }

    /** Sun or moon behind a cloud, with a thin gap cut around the cloud like SF Symbols. */
    private void behindCloud(Canvas c, float bx, float by, float br, float dx, float dy, float s) {
        c.save();
        c.clipPath(outline(cloudPath(dx, dy, s), 0.12f), android.graphics.Region.Op.DIFFERENCE);
        // The moon sits further up-left so its crescent stays visible beside the cloud
        if (day) sun(c, bx, by, br); else moon(c, bx - 0.07f, by - 0.07f, br * 1.5f);
        c.restore();
        cloud(c, dx, dy, s);
    }

    private static Path outline(Path p, float w) {
        Paint s = new Paint();
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeWidth(w);
        Path out = new Path();
        s.getFillPath(p, out);
        out.op(p, Path.Op.UNION);
        return out;
    }

    private Path cloudPath(float dx, float dy, float s) {
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setTranslate(-0.5f, -0.5f);
        m.postScale(s, s);
        m.postTranslate(0.5f + dx, 0.5f + dy);
        Path p = new Path(), t = new Path();
        p.addRoundRect(new RectF(0.12f, 0.5f, 0.88f, 0.76f), 0.13f, 0.13f, Path.Direction.CW);
        t.addCircle(0.34f, 0.54f, 0.16f, Path.Direction.CW);
        p.op(t, Path.Op.UNION);
        t.reset();
        t.addCircle(0.56f, 0.45f, 0.21f, Path.Direction.CW);
        p.op(t, Path.Op.UNION);
        t.reset();
        t.addCircle(0.74f, 0.58f, 0.14f, Path.Direction.CW);
        p.op(t, Path.Op.UNION);
        p.transform(m);
        return p;
    }

    private void drops(Canvas c, float len) {
        stroke.setColor(a(RAIN));
        stroke.setStrokeWidth(0.06f);
        float[] xs = {0.34f, 0.52f, 0.7f};
        for (int i = 0; i < xs.length; i++) {
            float y = 0.74f + (i % 2) * 0.05f;
            c.drawLine(xs[i], y, xs[i] - len * 0.35f, y + len, stroke);
        }
    }

    private void flakes(Canvas c) {
        fill.setColor(a(CLOUD));
        float[][] pts = {{0.34f, 0.8f}, {0.52f, 0.88f}, {0.7f, 0.8f}};
        for (float[] p : pts) c.drawCircle(p[0], p[1], 0.045f, fill);
    }

    private void bolt(Canvas c) {
        Path p = new Path();
        p.moveTo(0.56f, 0.6f);
        p.lineTo(0.4f, 0.8f);
        p.lineTo(0.5f, 0.8f);
        p.lineTo(0.44f, 0.97f);
        p.lineTo(0.62f, 0.74f);
        p.lineTo(0.52f, 0.74f);
        p.lineTo(0.58f, 0.6f);
        p.close();
        fill.setColor(a(BOLT));
        c.drawPath(p, fill);
    }

    private void lines(Canvas c, int color, float x0, float x1, float[] ys) {
        stroke.setColor(a(color));
        stroke.setStrokeWidth(0.06f);
        for (int i = 0; i < ys.length; i++) {
            float inset = i % 2 == 0 ? 0f : 0.08f;
            c.drawLine(x0 + inset, ys[i], x1 - inset, ys[i], stroke);
        }
    }

    @Override public void setAlpha(int a) { alpha = a; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter cf) { fill.setColorFilter(cf); stroke.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return -1; }
    @Override public int getIntrinsicHeight() { return -1; }

    /** Location arrow pointing up-right, like the tvOS/iOS "location.fill" symbol. */
    static Drawable locationArrow(Context ctx, float sizeDp, int color) {
        final int s = Ui.dp(ctx, sizeDp);
        Drawable d = new Drawable() {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(Canvas c) {
                Rect b = getBounds();
                c.save();
                c.translate(b.left, b.top);
                c.scale(b.width(), b.height());
                Path path = new Path();
                path.moveTo(0.92f, 0.08f);
                path.lineTo(0.08f, 0.46f);
                path.lineTo(0.52f, 0.48f);
                path.lineTo(0.54f, 0.92f);
                path.close();
                p.setColor(color);
                p.setStyle(Paint.Style.FILL);
                c.drawPath(path, p);
                c.restore();
            }
            @Override public void setAlpha(int a) { p.setAlpha(a); }
            @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
        d.setBounds(0, 0, s, s);
        return d;
    }
}

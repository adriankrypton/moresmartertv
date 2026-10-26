package com.adrian.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;

/**
 * Volume indicator in the style of tvOS, top left - only while sound plays over Bluetooth
 * (headphones, speaker). Then the box owns the volume: the keys are handled here without the
 * system panel and the real level is shown. Otherwise the keys pass through untouched and the
 * TV changes its own volume via HDMI-CEC and shows its own indicator.
 */
public class VolumeHud extends AccessibilityService {
    private static final long SHOW_MS = 1600;

    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout pill;
    private Speaker speaker;
    private boolean added;
    private final Runnable hide = () -> {
        if (pill != null) pill.animate().alpha(0f).translationY(-Ui.dp(this, 12)).setDuration(220)
                .withEndAction(this::remove).start();
    };

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }

    @Override public void onInterrupt() { }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        int code = e.getKeyCode();
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN
                && code != KeyEvent.KEYCODE_VOLUME_MUTE) return false;
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        if (!bluetoothOut(am)) return false; // TV volume via CEC: leave it to the TV
        int s = android.media.AudioManager.STREAM_MUSIC;
        if (code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            am.adjustStreamVolume(s, android.media.AudioManager.ADJUST_TOGGLE_MUTE, 0);
        } else {
            am.adjustStreamVolume(s, code == KeyEvent.KEYCODE_VOLUME_UP
                    ? android.media.AudioManager.ADJUST_RAISE : android.media.AudioManager.ADJUST_LOWER, 0);
        }
        boolean muted = am.isStreamMute(s);
        float level = muted ? 0f : (float) am.getStreamVolume(s) / Math.max(1, am.getStreamMaxVolume(s));
        main.post(() -> show(muted, level));
        return true; // handled here, so the system panel does not appear on top
    }

    private static boolean bluetoothOut(android.media.AudioManager am) {
        for (android.media.AudioDeviceInfo d : am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || (android.os.Build.VERSION.SDK_INT >= 31 && (t == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
                    || t == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER))) return true;
        }
        return false;
    }

    private void show(boolean muted, float level) {
        if (pill == null) build();
        speaker.set(muted, level);
        if (!added) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-2, -2,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = Ui.dp(this, 48);
            lp.y = Ui.dp(this, 36);
            try {
                ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(pill, lp);
                added = true;
                pill.setAlpha(0f);
                pill.setTranslationY(-Ui.dp(this, 12));
            } catch (Exception ignored) { return; }
        }
        pill.animate().cancel();
        pill.animate().alpha(1f).translationY(0).setDuration(180).start();
        main.removeCallbacks(hide);
        main.postDelayed(hide, SHOW_MS);
    }

    private void build() {
        pill = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xD92C2C30);
        bg.setCornerRadius(Ui.dp(this, 28));
        pill.setBackground(bg);
        pill.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 18), Ui.dp(this, 12));
        speaker = new Speaker(this);
        pill.addView(speaker, new FrameLayout.LayoutParams(Ui.dp(this, 132), Ui.dp(this, 32)));
    }

    private void remove() {
        if (!added) return;
        try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(pill); } catch (Exception ignored) { }
        added = false;
    }

    /** Speaker glyph followed by the volume bar, like tvOS. */
    private static final class Speaker extends View {
        private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG), dim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private boolean muted;
        private float level, shown;

        Speaker(android.content.Context c) {
            super(c);
            white.setColor(0xFFFFFFFF);
            dim.setColor(0x4DFFFFFF);
        }

        void set(boolean m, float l) {
            muted = m;
            level = l;
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(shown, l);
            a.setDuration(140);
            a.addUpdateListener(v -> { shown = (float) v.getAnimatedValue(); invalidate(); });
            a.start();
        }

        @Override protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth(), u = h / 10f;
            Path p = new Path();
            p.moveTo(0, 3.5f * u); p.lineTo(2.2f * u, 3.5f * u); p.lineTo(5.2f * u, 0.8f * u);
            p.lineTo(5.2f * u, 9.2f * u); p.lineTo(2.2f * u, 6.5f * u); p.lineTo(0, 6.5f * u); p.close();
            c.drawPath(p, white);
            Paint wave = new Paint(white);
            wave.setStyle(Paint.Style.STROKE);
            wave.setStrokeCap(Paint.Cap.ROUND);
            wave.setStrokeWidth(u * 0.9f);
            float cx = 5.6f * u, cy = 5f * u;
            if (muted || level == 0) {
                c.drawLine(cx + 1.6f * u, cy - 1.8f * u, cx + 5f * u, cy + 1.8f * u, wave);
                c.drawLine(cx + 5f * u, cy - 1.8f * u, cx + 1.6f * u, cy + 1.8f * u, wave);
            } else {
                int waves = level > 0.5f ? 2 : 1;
                for (int i = 1; i <= waves; i++) {
                    float rad = 2.2f * u * i;
                    r.set(cx - rad, cy - rad, cx + rad, cy + rad);
                    c.drawArc(r, -45, 90, false, wave);
                }
            }
            float left = 11.5f * u, right = w - 1.5f * u, top = cy - 0.9f * u, bot = cy + 0.9f * u;
            r.set(left, top, right, bot);
            c.drawRoundRect(r, u, u, dim);
            if (!muted && shown > 0) {
                r.set(left, top, left + (right - left) * shown, bot);
                c.drawRoundRect(r, u, u, white);
            }
        }
    }
}

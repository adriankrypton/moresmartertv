package com.adrian.tvhome;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Ambient screensaver: full-screen landscape photos that slowly drift and crossfade,
 * with a large clock, weather and indoor temperature. Text shifts slightly over time
 * so nothing burns in.
 */
public class AmbientDream extends DreamService {
    private static final long SLIDE_MS = 25_000L;

    private final Random random = new Random();
    private final List<String[]> photos = new ArrayList<>(); // {url, title}
    private int index;
    private ImageView imgA, imgB;
    private boolean aFront = true;
    private LinearLayout info;
    private TextView clock, date, caption, weather, indoor;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateTexts();
            Net.MAIN.postDelayed(this, 15_000L);
        }
    };
    private final Runnable slide = new Runnable() {
        @Override public void run() {
            next();
            Net.MAIN.postDelayed(this, SLIDE_MS);
        }
    };

    private int dp(float v) { return Ui.dp(this, v); }

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        Net.cacheDir = getCacheDir();

        Typeface thin = Typeface.create("sans-serif-thin", Typeface.NORMAL);
        Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
        Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        imgA = Ui.fillImage(this, ImageView.ScaleType.CENTER_CROP);
        imgB = Ui.fillImage(this, ImageView.ScaleType.CENTER_CROP);
        imgA.setAlpha(0f);
        imgB.setAlpha(0f);
        root.addView(imgA);
        root.addView(imgB);

        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xB3000000, 0x40000000, 0x00000000}));
        root.addView(bottom, new FrameLayout.LayoutParams(-1, dp(300), Gravity.BOTTOM));
        View top = new View(this);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x66000000, 0x00000000}));
        root.addView(top, new FrameLayout.LayoutParams(-1, dp(140), Gravity.TOP));

        caption = text(13, 0xCCFFFFFF, medium);
        caption.setLetterSpacing(0.04f);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(520), -2, Gravity.TOP | Gravity.END);
        cp.setMargins(0, dp(36), dp(48), 0);
        caption.setGravity(Gravity.END);
        root.addView(caption, cp);

        // Bottom info strip: clock + date on the left, weather + indoor on the right
        info = new LinearLayout(this);
        info.setGravity(Gravity.BOTTOM);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        clock = text(92, 0xFFFFFFFF, thin);
        date = text(20, 0xE6FFFFFF, light);
        left.addView(clock);
        left.addView(date);
        info.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        weather = text(24, 0xFFFFFFFF, light);
        weather.setGravity(Gravity.END);
        indoor = text(16, 0xCCFFFFFF, light);
        indoor.setGravity(Gravity.END);
        right.addView(weather);
        right.addView(indoor);
        info.addView(right);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        ip.setMargins(dp(56), 0, dp(56), dp(40));
        root.addView(info, ip);

        setContentView(root);
        updateTexts();
        loadPhotos();
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        Net.MAIN.post(tick);
    }

    @Override public void onDreamingStopped() {
        super.onDreamingStopped();
        Net.MAIN.removeCallbacks(tick);
        Net.MAIN.removeCallbacks(slide);
    }

    private TextView text(float sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setShadowLayer(dp(10), 0, dp(1), 0x99000000);
        return t;
    }

    // ---------------------------------------------------------------- photos

    /** Bing photos of the day (landscapes, 1080p); falls back to cached release backdrops. */
    private void loadPhotos() {
        Net.IO.execute(() -> {
            List<String[]> list = new ArrayList<>();
            try {
                JSONArray imgs = new JSONObject(Net.text(
                        "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=de-DE", null)).getJSONArray("images");
                for (int i = 0; i < imgs.length(); i++) {
                    JSONObject o = imgs.getJSONObject(i);
                    String title = o.optString("title", "");
                    String copy = o.optString("copyright", "");
                    int paren = copy.indexOf(" (");
                    String place = paren > 0 ? copy.substring(0, paren) : copy;
                    list.add(new String[]{"https://www.bing.com" + o.getString("urlbase") + "_1920x1080.jpg",
                            title.isEmpty() ? place : title + "  ·  " + place});
                }
                getSharedPreferences("home", MODE_PRIVATE).edit().putString("ambient", imgs.toString()).apply();
            } catch (Exception e) {
                String bg = getSharedPreferences("home", MODE_PRIVATE).getString("bg", null);
                if (bg != null) list.add(new String[]{bg, ""});
            }
            Net.MAIN.post(() -> {
                photos.clear();
                photos.addAll(list);
                if (!photos.isEmpty()) {
                    index = random.nextInt(photos.size());
                    Net.MAIN.removeCallbacks(slide);
                    Net.MAIN.post(slide);
                }
            });
        });
    }

    private void next() {
        if (photos.isEmpty()) return;
        final String[] p = photos.get(index % photos.size());
        index++;
        Net.bitmap(p[0], b -> {
            final ImageView in = aFront ? imgB : imgA;
            final ImageView out = aFront ? imgA : imgB;
            aFront = !aFront;
            in.setImageBitmap(b);
            in.animate().cancel();
            out.animate().cancel();
            in.animate().alpha(1f).setDuration(2500).start();
            out.animate().alpha(0f).setDuration(2500).start();
            // Slow drift: zoom in while panning a little in a random direction
            float dx = (random.nextFloat() - 0.5f) * dp(60), dy = (random.nextFloat() - 0.5f) * dp(30);
            ObjectAnimator drift = ObjectAnimator.ofPropertyValuesHolder(in,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1.04f, 1.14f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.04f, 1.14f),
                    PropertyValuesHolder.ofFloat(View.TRANSLATION_X, -dx, dx),
                    PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, -dy, dy));
            drift.setDuration(SLIDE_MS + 3000);
            drift.setInterpolator(new LinearInterpolator());
            drift.start();
            caption.animate().alpha(0f).setDuration(600).withEndAction(() -> {
                caption.setText(p[1]);
                caption.animate().alpha(1f).setDuration(900).start();
            }).start();
            // Burn-in protection: nudge the info strip a few dp each slide
            info.animate().translationX((random.nextFloat() - 0.5f) * dp(24))
                    .translationY((random.nextFloat() - 0.5f) * dp(14)).setDuration(2500).start();
        });
    }

    // ---------------------------------------------------------------- texts

    private void updateTexts() {
        Date now = new Date();
        clock.setText(new SimpleDateFormat(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"),
                Locale.getDefault()).format(now));

        SharedPreferences prefs = getSharedPreferences("home", MODE_PRIVATE);
        try {
            JSONObject cur = new JSONObject(prefs.getString("weather2", "{}")).getJSONObject("current");
            int code = cur.getInt("weather_code");
            weather.setText(MainActivity.weatherGlyph(code, cur.optInt("is_day", 1) == 1) + "  "
                    + Math.round(cur.getDouble("temperature_2m")) + "°  " + MainActivity.weatherText(code));
        } catch (Exception e) {
            weather.setText("");
        }
        // The room chosen next to the clock on the Home tab; average of all rooms otherwise
        try {
            JSONArray rooms = new JSONArray();
            for (String key : new String[]{"cacheHmip", "cacheBosch"}) {
                JSONArray a = new JSONArray(prefs.getString(key, "[]"));
                for (int i = 0; i < a.length(); i++) rooms.put(a.getJSONObject(i));
            }
            String pinned = prefs.getString("climateRoom", null);
            String text = "";
            double sum = 0;
            int n = 0;
            for (int i = 0; i < rooms.length(); i++) {
                JSONObject r = rooms.getJSONObject(i);
                if (r.isNull("cur")) continue;
                if (r.getString("id").equals(pinned)) {
                    text = String.format(Locale.GERMANY, "%s %.1f°", r.getString("name"), r.getDouble("cur"));
                }
                sum += r.getDouble("cur");
                n++;
            }
            if (text.isEmpty() && n > 0) text = String.format(Locale.GERMANY, "Innen %.1f°", sum / n);
            indoor.setText(text);
        } catch (Exception e) {
            indoor.setText("");
        }
    }
}

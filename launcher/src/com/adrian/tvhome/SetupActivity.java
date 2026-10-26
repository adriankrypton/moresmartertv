package com.adrian.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** First-run setup: choose the location used for the weather (postcode or place name). */
public class SetupActivity extends Activity {
    static final String PLACE = "place", LAT = "lat", LON = "lon";

    private Typeface thin, regular, medium, bold;
    private EditText input;
    private LinearLayout results;
    private TextView status;

    static boolean hasPlace(Context c) {
        return c.getSharedPreferences("home", MODE_PRIVATE).contains(PLACE);
    }

    private int dp(float v) { return Ui.dp(this, v); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        thin = Typeface.create("sans-serif-thin", Typeface.NORMAL);
        regular = Typeface.create("sans-serif", Typeface.NORMAL);
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        bold = Typeface.create("sans-serif", Typeface.BOLD);

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1E2A3A, 0xFF121620, 0xFF08090C}));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        // Top-aligned so the results stay above the on-screen keyboard
        FrameLayout.LayoutParams colLp = new FrameLayout.LayoutParams(dp(560), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        colLp.topMargin = dp(56);
        root.addView(col, colLp);

        boolean first = !hasPlace(this);
        TextView title = text(first ? 54 : 40, 0xFFFFFFFF, thin);
        title.setText(first ? "Willkommen" : "Standort");
        TextView sub = text(19, 0xCCFFFFFF, regular);
        sub.setText("Wo steht dein Fernseher? Der Ort wird für das Wetter genutzt.");
        sub.setGravity(Gravity.CENTER);
        title.setGravity(Gravity.CENTER);
        col.addView(title, new LinearLayout.LayoutParams(-1, -2));
        col.addView(sub, lp(-2, -2, 0, 10, 0, 24));

        input = new EditText(this);
        input.setHint("Postleitzahl oder Ort");
        input.setHintTextColor(0x80FFFFFF);
        input.setTextColor(0xFFFFFFFF);
        input.setTextSize(20);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setPadding(dp(22), dp(14), dp(22), dp(14));
        input.setBackground(pill(0x26FFFFFF, 0x40FFFFFF, 0xFFFFFFFF));
        input.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_DONE
                    || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN)) {
                // Close the keyboard so the results are visible
                ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                        .hideSoftInputFromWindow(input.getWindowToken(), 0);
                search(input.getText().toString().trim());
                return true;
            }
            return false;
        });
        col.addView(input, new LinearLayout.LayoutParams(-1, -2));

        status = text(15, 0x99FFFFFF, regular);
        col.addView(status, lp(-2, -2, 0, 16, 0, 6));
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        col.addView(results, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        input.requestFocus();
    }

    @Override public void onBackPressed() {
        // The first setup cannot be skipped; changing the location later can.
        if (hasPlace(this)) super.onBackPressed();
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private TextView text(float sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        return t;
    }

    private StateListDrawable pill(int fill, int stroke, int focusStroke) {
        GradientDrawable off = new GradientDrawable();
        off.setColor(fill);
        off.setCornerRadius(dp(16));
        off.setStroke(dp(1), stroke);
        GradientDrawable on = new GradientDrawable();
        on.setColor(fill);
        on.setCornerRadius(dp(16));
        on.setStroke(dp(2), focusStroke);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, off);
        return s;
    }

    /** German postcodes via zippopotam.us, everything else via the Open-Meteo geocoder. */
    private void search(final String q) {
        if (q.isEmpty()) return;
        status.setText("Suche …");
        results.removeAllViews();
        Net.IO.execute(() -> {
            final List<String[]> found = new ArrayList<>(); // {label, lat, lon, place}
            try {
                if (q.matches("\\d{5}")) {
                    JSONArray places = new JSONObject(Net.text("https://api.zippopotam.us/de/" + q, null)).getJSONArray("places");
                    for (int i = 0; i < places.length(); i++) {
                        JSONObject p = places.getJSONObject(i);
                        String name = p.getString("place name");
                        found.add(new String[]{q + " " + name + ", " + p.optString("state"),
                                p.getString("latitude"), p.getString("longitude"), name});
                    }
                } else {
                    JSONObject r = new JSONObject(Net.text("https://geocoding-api.open-meteo.com/v1/search?count=6&language=de&name="
                            + URLEncoder.encode(q, "UTF-8"), null));
                    JSONArray res = r.optJSONArray("results");
                    for (int i = 0; res != null && i < res.length(); i++) {
                        JSONObject p = res.getJSONObject(i);
                        String name = p.getString("name");
                        String region = p.optString("admin1", "");
                        String country = p.optString("country", "");
                        found.add(new String[]{name + (region.isEmpty() ? "" : ", " + region) + (country.isEmpty() ? "" : " · " + country),
                                String.valueOf(p.getDouble("latitude")), String.valueOf(p.getDouble("longitude")), name});
                    }
                }
            } catch (Exception ignored) { }
            Net.MAIN.post(() -> show(found));
        });
    }

    private void show(List<String[]> found) {
        results.removeAllViews();
        status.setText(found.isEmpty() ? "Nichts gefunden. Andere Schreibweise oder PLZ probieren." : "Bitte auswählen:");
        for (final String[] f : found) {
            TextView t = text(18, 0xFFFFFFFF, medium);
            t.setText(f[0]);
            t.setFocusable(true);
            t.setClickable(true);
            t.setPadding(dp(22), dp(14), dp(22), dp(14));
            GradientDrawable on = new GradientDrawable();
            on.setColor(0xFFFFFFFF);
            on.setCornerRadius(dp(14));
            GradientDrawable off = new GradientDrawable();
            off.setColor(0x1AFFFFFF);
            off.setCornerRadius(dp(14));
            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_focused}, on);
            bg.addState(new int[]{}, off);
            t.setBackground(bg);
            t.setTextColor(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                    new int[]{0xFF111114, 0xFFFFFFFF}));
            t.setOnClickListener(v -> choose(f));
            results.addView(t, lp(-1, -2, 0, 0, 0, 8));
        }
        if (!found.isEmpty()) results.getChildAt(0).requestFocus();
    }

    private void choose(String[] f) {
        SharedPreferences.Editor e = getSharedPreferences("home", MODE_PRIVATE).edit();
        e.putString(PLACE, f[3]).putString(LAT, f[1]).putString(LON, f[2]);
        e.remove("weather2"); // force a fresh forecast for the new place
        e.apply();
        setResult(RESULT_OK);
        finish();
    }
}

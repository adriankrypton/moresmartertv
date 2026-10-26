package com.adrian.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** "Home" page of the launcher, styled like the Apple TV Home overlay: info row + frosted panel. */
final class SmartHomePage extends LinearLayout {
    private static final int INK = 0xFF1C1C1E, INK2 = 0xFF6E6E73;

    private final Activity act;
    private Govee govee; // null until configured or found on the LAN
    private Govee.Status status;
    private Bosch bosch;
    private Hmip hmip;
    private List<Bosch.Room> rooms;
    private boolean shown;
    private long lastOpenFetch;

    private final Typeface regular, medium, bold;
    private TextView wGlyph, wTemp, wCond, wRange, tTime, tDay, tDate, cTemp, cLine1, cLine2;
    private GridLayout accessories;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            refreshLamp();
            renderHeader();
            Net.MAIN.postDelayed(this, 8000);
        }
    };

    private int dp(float v) { return Ui.dp(act, v); }

    SmartHomePage(Activity a) {
        super(a);
        act = a;
        regular = Typeface.create("sans-serif", Typeface.NORMAL);
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        bold = Typeface.create("sans-serif", Typeface.BOLD);
        String goveeIp = Config.goveeIp(a);
        if (goveeIp != null) govee = new Govee(goveeIp);
        else Govee.discover(found -> {
            if (found == null) return;
            Config.rememberGovee(act, found[0], found[1]);
            govee = new Govee(found[0]);
            refreshLamp();
        });
        try { bosch = new Bosch(a); } catch (Exception e) { android.util.Log.w("TvHome", "bosch init", e); }
        try { hmip = new Hmip(a); } catch (Exception ignored) { }

        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        addView(header(), new LayoutParams(-1, -2));
        View spacer = new View(a);
        addView(spacer, new LayoutParams(1, 0, 1f));
        addView(panel(), new LayoutParams(-1, -2));
        lastHmip = hmip != null ? loadRooms("cacheHmip") : null;
        lastBosch = bosch != null ? loadRooms("cacheBosch") : null;
        mergeRooms();
    }

    void onShow() {
        shown = true;
        // Heating is fetched only when the page opens (and after a change), never polled.
        // Focus passing over the tab also opens the page, so allow at most one fetch a minute.
        long now = System.currentTimeMillis();
        if (now - lastOpenFetch > 60_000L) {
            lastOpenFetch = now;
            // A failed system is only retried by clicking its tile
            refreshHeating(boschState != FAILED, hmipState != FAILED);
        }
        Net.MAIN.removeCallbacks(poll);
        poll.run();
    }

    void onHide() {
        shown = false;
        Net.MAIN.removeCallbacks(poll);
    }

    View firstFocus() { return accessories.getChildCount() > 0 ? accessories.getChildAt(0) : this; }

    private TextView text(float sp, int color, Typeface tf) {
        TextView t = new TextView(act);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        return t;
    }

    private TextView shadowed(float sp, Typeface tf) {
        TextView t = text(sp, 0xFFFFFFFF, tf);
        t.setShadowLayer(dp(8), 0, dp(1), 0x80000000);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    // ---------------------------------------------------------------- info row: weather · time · climate

    private View header() {
        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.TOP);

        LinearLayout w = column();
        w.setFocusable(true);
        w.setClickable(true);
        w.setPadding(dp(16), dp(8), dp(16), dp(10));
        GradientDrawable wFocus = new GradientDrawable();
        wFocus.setColor(0x33FFFFFF);
        wFocus.setCornerRadius(dp(20));
        android.graphics.drawable.StateListDrawable wbg = new android.graphics.drawable.StateListDrawable();
        wbg.addState(new int[]{android.R.attr.state_focused}, wFocus);
        w.setBackground(wbg);
        w.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(150).start());
        // OK on the weather changes the location
        w.setOnClickListener(v -> act.startActivity(new android.content.Intent(act, SetupActivity.class)));
        LinearLayout wTop = new LinearLayout(act);
        wTop.setGravity(Gravity.CENTER_VERTICAL);
        wGlyph = shadowed(30, regular);
        wTemp = shadowed(42, medium);
        wTop.addView(wGlyph);
        LayoutParams tp = new LayoutParams(-2, -2);
        tp.leftMargin = dp(12);
        wTop.addView(wTemp, tp);
        wCond = shadowed(18, medium);
        wRange = shadowed(16, regular);
        w.addView(wTop);
        w.addView(wCond);
        w.addView(wRange);
        LinearLayout wWrap = column();
        wWrap.addView(w, new LayoutParams(-2, -2));
        row.addView(wWrap, new LayoutParams(0, -2, 1f));

        LinearLayout t = column();
        tTime = shadowed(46, medium);
        tDay = shadowed(18, medium);
        tDate = shadowed(16, regular);
        t.addView(tTime);
        t.addView(tDay);
        t.addView(tDate);
        row.addView(t, new LayoutParams(0, -2, 1f));

        LinearLayout c = column();
        c.setFocusable(true);
        c.setClickable(true);
        c.setPadding(dp(16), dp(8), dp(16), dp(10));
        GradientDrawable focusBg = new GradientDrawable();
        focusBg.setColor(0x33FFFFFF);
        focusBg.setCornerRadius(dp(20));
        android.graphics.drawable.StateListDrawable cbg = new android.graphics.drawable.StateListDrawable();
        cbg.addState(new int[]{android.R.attr.state_focused}, focusBg);
        c.setBackground(cbg);
        c.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(150).start());
        c.setOnClickListener(v -> pickClimateRoom());
        LinearLayout cTop = new LinearLayout(act);
        cTop.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout circle = new FrameLayout(act);
        GradientDrawable cb = new GradientDrawable();
        cb.setShape(GradientDrawable.OVAL);
        cb.setColor(0xFF0A84FF);
        circle.setBackground(cb);
        ImageView house = new ImageView(act);
        house.setImageResource(R.drawable.ic_home_circle);
        circle.addView(house, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
        cTop.addView(circle, new LayoutParams(dp(48), dp(48)));
        cTemp = shadowed(42, medium);
        LayoutParams cp = new LayoutParams(-2, -2);
        cp.leftMargin = dp(14);
        cTop.addView(cTemp, cp);
        cLine1 = shadowed(18, medium);
        cLine2 = shadowed(16, regular);
        c.addView(cTop);
        c.addView(cLine1);
        c.addView(cLine2);
        LinearLayout cWrap = column();
        cWrap.addView(c, new LayoutParams(-2, -2));
        row.addView(cWrap, new LayoutParams(0, -2, 1f));
        return row;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        return l;
    }

    private void renderHeader() {
        Date now = new Date();
        tTime.setText(new SimpleDateFormat(DateFormat.is24HourFormat(act) ? "HH:mm" : "h:mm a", Locale.getDefault()).format(now));
        tDay.setText(new SimpleDateFormat("EEEE", Locale.getDefault()).format(now));
        tDate.setText(new SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMMMyyyy"), Locale.getDefault()).format(now));

        try {
            JSONObject w = new JSONObject(act.getSharedPreferences("home", Activity.MODE_PRIVATE).getString("weather2", "{}"));
            JSONObject cur = w.getJSONObject("current");
            JSONObject daily = w.getJSONObject("daily");
            int code = cur.getInt("weather_code");
            wGlyph.setText(MainActivity.weatherGlyph(code, cur.optInt("is_day", 1) == 1));
            wTemp.setText(Math.round(cur.getDouble("temperature_2m")) + "°");
            wCond.setText(MainActivity.weatherText(code));
            wRange.setText("Tief " + Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)) + "° / Hoch "
                    + Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)) + "°");
        } catch (Exception e) {
            wGlyph.setText("");
            wTemp.setText("–");
            wCond.setText("Wetter");
            wRange.setText("lädt …");
        }

        double sum = 0;
        int n = 0, heating = 0;
        if (rooms != null) for (Bosch.Room r : rooms) {
            if (!Double.isNaN(r.current)) { sum += r.current; n++; }
            if (!Double.isNaN(r.current) && !Double.isNaN(r.setpoint) && r.setpoint > r.current + 0.2) heating++;
        }
        Bosch.Room pinned = pinnedRoom();
        cLine1.setText("Innen");
        if (pinned != null) {
            cTemp.setText(Double.isNaN(pinned.current) ? "–" : fmt(pinned.current) + "°");
            cLine1.setText(pinned.name);
            cLine2.setText(Double.isNaN(pinned.setpoint) ? "Heizung" : "Ziel " + fmt(pinned.setpoint) + "°");
        } else if (n > 0) {
            cTemp.setText(fmt(sum / n) + "°");
            cLine2.setText(heating > 0 ? heating + (heating == 1 ? " Raum heizt" : " Räume heizen") : n + " Räume");
        } else {
            cTemp.setText("–");
            cLine2.setText(rooms == null ? "Heizung nicht verbunden" : "Keine Räume");
        }
    }

    // ---------------------------------------------------------------- frosted panel

    private View panel() {
        LinearLayout p = new LinearLayout(act);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xD9DADDE0);
        bg.setCornerRadius(dp(26));
        p.setBackground(bg);
        p.setPadding(dp(24), dp(18), dp(24), dp(22));
        p.setClipChildren(false);
        p.setClipToPadding(false);

        // Camera placeholder
        LinearLayout cam = new LinearLayout(act);
        cam.setOrientation(VERTICAL);
        cam.setClipChildren(false);
        cam.addView(sectionTitle("Kamera"));
        FrameLayout feed = new FrameLayout(act);
        feed.setFocusable(true);
        feed.setClickable(true);
        GradientDrawable fb = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF3A3F47, 0xFF1F2227});
        fb.setCornerRadius(dp(14));
        feed.setBackground(fb);
        LinearLayout ph = new LinearLayout(act);
        ph.setOrientation(VERTICAL);
        ph.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView camIcon = new ImageView(act);
        camIcon.setImageResource(R.drawable.ic_camera);
        camIcon.setAlpha(0.9f);
        TextView c1 = text(13.5f, 0xFFFFFFFF, bold);
        c1.setText("Mit UniFi Protect verbinden");
        TextView c2 = text(12.5f, 0xB3FFFFFF, regular);
        c2.setText("Live-Bild deiner Kameras");
        ph.addView(camIcon, new LayoutParams(dp(32), dp(32)));
        LayoutParams c1p = new LayoutParams(-2, -2);
        c1p.topMargin = dp(8);
        ph.addView(c1, c1p);
        ph.addView(c2);
        feed.addView(ph, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        focusLift(feed, 1.04f, 14);
        feed.setOnClickListener(v -> new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("UniFi Protect verbinden")
                .setMessage("Für das Live-Bild in " + Config.cameraName(act) + " bei der Kamera unter Einstellungen → Erweitert "
                        + "den RTSP-Stream aktivieren.")
                .setPositiveButton("OK", null).show());
        cam.addView(feed, new LayoutParams(-1, dp(150)));
        TextView caption = text(14, INK, medium);
        caption.setText("Keine Kamera verbunden");
        caption.setGravity(Gravity.CENTER);
        LayoutParams capp = new LayoutParams(-1, -2);
        capp.topMargin = dp(10);
        cam.addView(caption, capp);
        p.addView(cam, new LayoutParams(dp(184), -2));

        // Accessories
        LinearLayout acc = new LinearLayout(act);
        acc.setOrientation(VERTICAL);
        acc.setClipChildren(false);
        acc.addView(sectionTitle("Geräte"));
        // Two rows that scroll sideways, so any number of devices fits the panel
        accessories = grid();
        accessories.setColumnCount(GridLayout.UNDEFINED);
        accessories.setOrientation(GridLayout.VERTICAL);
        accessories.setRowCount(2);
        android.widget.HorizontalScrollView accScroll = new android.widget.HorizontalScrollView(act);
        accScroll.setHorizontalScrollBarEnabled(false);
        // Clip so tiles scrolled sideways never paint over the camera column
        accScroll.setClipChildren(true);
        accScroll.setClipToPadding(true);
        accessories.setPadding(dp(8), dp(6), dp(8), dp(4));
        accScroll.setHorizontalFadingEdgeEnabled(false);
        accScroll.addView(accessories);
        acc.addView(accScroll, new LayoutParams(-1, -2));
        LayoutParams ap = new LayoutParams(0, -2, 1f);
        ap.leftMargin = dp(24);
        p.addView(acc, ap);

        // Scenes
        LinearLayout sc = new LinearLayout(act);
        sc.setOrientation(VERTICAL);
        sc.setClipChildren(false);
        sc.addView(sectionTitle("Szenen"));
        GridLayout scenes = grid();
        scenes.setColumnCount(2);
        scenes.addView(sceneTile("Hell", () -> { govee.power(true); govee.kelvin(4500); govee.brightness(100); }));
        scenes.addView(sceneTile("Gemütlich", () -> { govee.power(true); govee.kelvin(2700); govee.brightness(40); }));
        scenes.addView(sceneTile("Kino", () -> { govee.power(true); govee.color(30, 40, 255); govee.brightness(12); }));
        scenes.addView(sceneTile("Alles aus", () -> govee.power(false)));
        sc.addView(scenes);
        LayoutParams sp = new LayoutParams(-2, -2);
        sp.leftMargin = dp(24);
        p.addView(sc, sp);
        return p;
    }

    private TextView sectionTitle(String what) {
        TextView t = text(13, INK, bold);
        t.setText(android.text.Html.fromHtml("Home&nbsp;&nbsp;<font color='#8E8E93'>" + what + "</font>", 0));
        t.setPadding(dp(2), 0, 0, dp(10));
        return t;
    }

    private GridLayout grid() {
        GridLayout g = new GridLayout(act);
        g.setColumnCount(3);
        g.setClipChildren(false);
        g.setClipToPadding(false);
        return g;
    }

    private void focusLift(final View v, final float scale, int radiusDp) {
        Ui.round(v, dp(radiusDp));
        // Zoom only, no shadow on focus
        v.setOnFocusChangeListener((x, has) -> v.animate().scaleX(has ? scale : 1f).scaleY(has ? scale : 1f)
                .setDuration(160).setInterpolator(new DecelerateInterpolator()).start());
    }

    /** Views of a tile that change with the device state (kept in the tile's tag). */
    private static final class TileRefs {
        String key;
        GradientDrawable bg;
        ImageView icon;
        TextView state;
    }

    /** Apple-style tile: icon top-left, name + status bottom-left. */
    private FrameLayout tile(int icon, String name, String state, boolean active, int widthDp) {
        FrameLayout t = new FrameLayout(act);
        t.setFocusable(true);
        t.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(active ? 0xFFFFFFFF : 0x8CFFFFFF);
        bg.setCornerRadius(dp(14));
        GradientDrawable focusedBg = new GradientDrawable();
        focusedBg.setColor(0xFFFFFFFF);
        focusedBg.setCornerRadius(dp(14));
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focusedBg);
        states.addState(new int[]{}, bg);
        t.setBackground(states);
        t.setPadding(dp(10), dp(9), dp(8), dp(8));
        ImageView i = new ImageView(act);
        i.setImageResource(icon);
        if (!active && icon == R.drawable.ic_bulb) i.setAlpha(0.45f);
        t.addView(i, new FrameLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout labels = new LinearLayout(act);
        labels.setOrientation(VERTICAL);
        TextView n = text(11.5f, INK, medium);
        n.setText(name);
        n.setMaxLines(2);
        n.setEllipsize(TextUtils.TruncateAt.END);
        n.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        n.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
        labels.addView(n);
        if (state != null) {
            TextView s = text(10.5f, INK2, regular);
            s.setText(state);
            s.setSingleLine(true);
            s.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(s);
        }
        t.addView(labels, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        TileRefs refs = new TileRefs();
        refs.bg = bg;
        refs.icon = i;
        refs.state = labels.getChildCount() > 1 ? (TextView) labels.getChildAt(1) : null;
        t.setTag(refs);
        focusLift(t, 1.12f, 14);
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.width = dp(widthDp);
        p.height = dp(88);
        p.setMargins(0, 0, dp(6), dp(6));
        t.setLayoutParams(p);
        return t;
    }

    private View sceneTile(String name, final Runnable action) {
        FrameLayout t = tile(R.drawable.ic_house, name, null, true, 78);
        t.setOnClickListener(v -> {
            if (govee == null) return; // scenes drive the Govee light
            action.run();
            v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).withEndAction(() ->
                    v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).start()).start();
            Net.MAIN.postDelayed(this::refreshLamp, 1200);
        });
        return t;
    }

    // ---------------------------------------------------------------- accessories

    /** One accessory tile's content. */
    private static final class Spec {
        final String key, name, state;
        final int icon;
        final boolean active;
        Spec(String key, int icon, String name, String state, boolean active) {
            this.key = key; this.icon = icon; this.name = name; this.state = state; this.active = active;
        }
    }

    private java.util.List<Spec> accessorySpecs() {
        java.util.List<Spec> out = new java.util.ArrayList<>();
        boolean on = status != null && status.on;
        if (govee != null) out.add(new Spec("lamp", R.drawable.ic_bulb, Config.goveeName(act),
                status == null ? "Keine Antwort" : on ? "An · " + status.brightness + " %" : "Aus", on));
        // A system without data yet shows a status tile instead of its rooms
        if (hmip != null && lastHmip == null)
            out.add(new Spec("status:hmip", R.drawable.ic_thermo, "Homematic", stateText(hmipState), false));
        if (bosch != null && lastBosch == null)
            out.add(new Spec("status:bosch", R.drawable.ic_thermo, "Bosch Smart Home", stateText(boschState), false));
        if (hmip == null && bosch == null) {
            out.add(new Spec("heating", R.drawable.ic_thermo, "Heizung", "Verbinden", false));
        } else if (rooms != null) {
            java.util.List<Bosch.Room> ordered = new java.util.ArrayList<>(rooms);
            // Rooms without a radiator yet go to the end
            java.util.Collections.sort(ordered, (x, y) -> Boolean.compare(
                    x.climateDevice == null && x.hmipGroup == null, y.climateDevice == null && y.hmipGroup == null));
            for (Bosch.Room r : ordered) {
                boolean empty = r.climateDevice == null && r.hmipGroup == null;
                String st = empty ? "Noch leer" : (Double.isNaN(r.current) ? "–" : fmt(r.current))
                        + (Double.isNaN(r.setpoint) ? "°" : " → " + short1(r.setpoint) + "°");
                boolean heating = !Double.isNaN(r.current) && !Double.isNaN(r.setpoint) && r.setpoint > r.current + 0.2;
                out.add(new Spec("room:" + r.id, R.drawable.ic_thermo, r.name, st, heating));
            }
        }
        return out;
    }

    /**
     * Updates the tiles in place when the set of devices is unchanged, so the focused
     * tile is not recreated (which would replay its focus animation every poll).
     */
    private void renderAccessories() {
        java.util.List<Spec> specs = accessorySpecs();
        boolean same = accessories.getChildCount() == specs.size();
        for (int k = 0; same && k < specs.size(); k++) {
            Object tag = accessories.getChildAt(k).getTag();
            same = tag instanceof TileRefs && specs.get(k).key.equals(((TileRefs) tag).key);
        }
        if (!same) {
            Ui.rebuildKeepingFocus(accessories, () -> buildAccessories(specs));
            return;
        }
        for (int k = 0; k < specs.size(); k++) {
            Spec sp = specs.get(k);
            TileRefs refs = (TileRefs) accessories.getChildAt(k).getTag();
            refs.bg.setColor(sp.active ? 0xFFFFFFFF : 0x8CFFFFFF);
            if (sp.icon == R.drawable.ic_bulb) refs.icon.setAlpha(sp.active ? 1f : 0.45f);
            if (refs.state != null && !sp.state.contentEquals(refs.state.getText())) refs.state.setText(sp.state);
        }
    }

    private Bosch.Room roomById(String id) {
        if (rooms != null) for (Bosch.Room r : rooms) if (r.id.equals(id)) return r;
        return null;
    }

    private void buildAccessories(java.util.List<Spec> specs) {
        accessories.removeAllViews();
        for (Spec sp : specs) {
            FrameLayout t = tile(sp.icon, sp.name, sp.state, sp.active, 90);
            ((TileRefs) t.getTag()).key = sp.key;
            if (sp.key.equals("lamp")) {
                // OK opens the control card; long press is a quick on/off toggle
                t.setOnClickListener(v -> lampDialog());
                t.setOnLongClickListener(v -> {
                    boolean target = status == null || !status.on;
                    govee.power(target);
                    if (status == null) status = new Govee.Status();
                    status.on = target;
                    renderAccessories();
                    Net.MAIN.postDelayed(this::refreshLamp, 900);
                    return true;
                });
            } else if (sp.key.startsWith("status:")) {
                final boolean isHmip = sp.key.equals("status:hmip");
                // Only a click retries a failed connection
                t.setOnClickListener(v -> refreshHeating(!isHmip, isHmip));
            } else if (sp.key.equals("heating")) {
                t.setOnClickListener(v -> new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Heizung")
                        .setMessage("Bosch Smart Home und Homematic IP werden am PC mit den Skripten aus launcher/pairing gekoppelt. "
                                + "Danach erscheinen hier alle Räume.")
                        .setPositiveButton("OK", null).show());
            } else {
                final String id = sp.key.substring(5);
                t.setOnClickListener(v -> {
                    Bosch.Room r = roomById(id);
                    if (r != null) roomDialog(r);
                });
            }
            accessories.addView(t);
        }
    }

    private void lampDialog() {
        final boolean on = status != null && status.on;
        final int bri = status != null ? status.brightness : 50;
        int col = status == null ? 0xFFFFD8A8 : status.kelvin > 0 ? (status.kelvin <= 3000 ? 0xFFFFC98A : status.kelvin <= 4500 ? 0xFFFFF1DE : 0xFFDDE8FF)
                : (status.r + status.g + status.b == 0 ? 0xFFFFD8A8 : 0xFF000000 | (status.r << 16) | (status.g << 8) | status.b);
        final ControlSheet cs = new ControlSheet(act, R.drawable.ic_bulb, Config.goveeName(act),
                on ? "An" : "Aus", on ? bri : 0, 0, 100, 10, new int[]{0xFFFFFFFF, col},
                v -> v <= 0 ? "Aus" : (int) v + " %",
                v -> {
                    if (v <= 0) govee.power(false);
                    else { govee.power(true); govee.brightness((int) v); }
                    Net.MAIN.postDelayed(this::refreshLamp, 900);
                });
        cs.preset("Ein / Aus", null, () -> cs.set(cs.value() > 0 ? 0 : Math.max(bri, 10), true))
          .preset("Warmweiß", 0xFFFFC98A, () -> { govee.power(true); govee.kelvin(2700); if (cs.value() == 0) cs.set(60, true); })
          .preset("Neutralweiß", 0xFFFFF1DE, () -> { govee.power(true); govee.kelvin(4000); if (cs.value() == 0) cs.set(60, true); })
          .preset("Kaltweiß", 0xFFDDE8FF, () -> { govee.power(true); govee.kelvin(6500); if (cs.value() == 0) cs.set(60, true); })
          .preset("Rot", 0xFFFF3B30, () -> { govee.power(true); govee.color(255, 0, 0); if (cs.value() == 0) cs.set(60, true); })
          .preset("Blau", 0xFF0A84FF, () -> { govee.power(true); govee.color(0, 90, 255); if (cs.value() == 0) cs.set(60, true); })
          .preset("Lila", 0xFFBF5AF2, () -> { govee.power(true); govee.color(170, 0, 255); if (cs.value() == 0) cs.set(60, true); });
        cs.show();
    }

    private void roomDialog(final Bosch.Room r) {
        if (r.climateDevice == null && r.hmipGroup == null) {
            new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(r.name)
                    .setMessage("In diesem Raum ist bei Bosch Smart Home noch kein Heizkörper-Thermostat angelernt. "
                            + "Sobald du ihn in der Bosch-App hinzufügst, kannst du ihn hier steuern.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        if (Double.isNaN(r.setpoint)) return;
        final ControlSheet cs = new ControlSheet(act, R.drawable.ic_thermo, r.name,
                "Raumtemperatur " + (Double.isNaN(r.current) ? "–" : fmt(r.current) + "°"),
                r.setpoint, 5, 30, 0.5, new int[]{0xFFFFB340, 0xFFFF6B3D},
                v -> fmt(v) + "°",
                v -> {
                    r.setpoint = v;
                    renderAccessories();
                    renderHeader();
                    Net.IO.execute(() -> {
                        try {
                            boolean isHmip = r.hmipGroup != null;
                            if (isHmip) hmip.setSetpoint(r.hmipGroup, v);
                            else bosch.setSetpoint(r.climateDevice, v);
                            // one confirming read of just that system
                            Net.MAIN.postDelayed(() -> refreshHeating(!isHmip, isHmip), 5000);
                        } catch (Exception ignored) { }
                    });
                });
        cs.preset("Komfort  22°", null, () -> cs.set(22, true))
          .preset("Normal  21°", null, () -> cs.set(21, true))
          .preset("Sparen  19°", null, () -> cs.set(19, true))
          .preset("Nacht  17°", null, () -> cs.set(17, true))
          .preset("Aus  5°", null, () -> cs.set(5, true));
        cs.show();
    }

    private void refreshLamp() {
        if (govee == null) return;
        govee.status(s -> {
            if (s != null) status = s;
            if (shown) renderAccessories();
        });
    }

    // Last good result per system, so a failed fetch does not make rooms disappear
    private List<Bosch.Room> lastBosch, lastHmip;
    private static final int IDLE = 0, LOADING = 1, OK = 2, FAILED = 3;
    private int boschState = IDLE, hmipState = IDLE;

    private static String stateText(int st) {
        return st == FAILED ? "Fehlgeschlagen · OK = erneut" : "Verbindung wird hergestellt …";
    }

    private void refreshHeating(final boolean doBosch, final boolean doHmip) {
        final boolean b = doBosch && bosch != null, h = doHmip && hmip != null;
        if (!b && !h) return;
        if (b) boschState = LOADING;
        if (h) hmipState = LOADING;
        renderAccessories();
        Net.IO.execute(() -> {
            if (b) {
                try { lastBosch = bosch.rooms(); boschState = OK; saveRooms("cacheBosch", lastBosch); }
                catch (Exception e) { boschState = FAILED; android.util.Log.w("TvHome", "bosch: " + e.getMessage()); }
            }
            if (h) {
                try { lastHmip = hmip.rooms(); hmipState = OK; saveRooms("cacheHmip", lastHmip); }
                catch (Exception e) { hmipState = FAILED; android.util.Log.w("TvHome", "hmip: " + e.getMessage()); }
            }
            Net.MAIN.post(this::mergeRooms);
        });
    }

    /** Combines the per-system results into the room list and redraws. */
    private void mergeRooms() {
        if (lastBosch == null && lastHmip == null) rooms = null;
        else {
            List<Bosch.Room> res = new java.util.ArrayList<>();
            if (lastHmip != null) res.addAll(lastHmip);
            if (lastBosch != null) res.addAll(lastBosch);
            rooms = res;
        }
        renderAccessories();
        renderHeader();
    }

    private void saveRooms(String key, List<Bosch.Room> list) {
        try {
            org.json.JSONArray a = new org.json.JSONArray();
            for (Bosch.Room r : list) {
                a.put(new JSONObject().put("id", r.id).put("name", r.name)
                        .put("cd", r.climateDevice == null ? JSONObject.NULL : r.climateDevice)
                        .put("hg", r.hmipGroup == null ? JSONObject.NULL : r.hmipGroup)
                        .put("cur", Double.isNaN(r.current) ? JSONObject.NULL : r.current)
                        .put("set", Double.isNaN(r.setpoint) ? JSONObject.NULL : r.setpoint)
                        .put("mode", r.mode == null ? JSONObject.NULL : r.mode));
            }
            act.getSharedPreferences("home", Activity.MODE_PRIVATE).edit().putString(key, a.toString()).apply();
        } catch (Exception ignored) { }
    }

    private List<Bosch.Room> loadRooms(String key) {
        String json = act.getSharedPreferences("home", Activity.MODE_PRIVATE).getString(key, null);
        if (json == null) return null;
        try {
            org.json.JSONArray a = new org.json.JSONArray(json);
            List<Bosch.Room> out = new java.util.ArrayList<>();
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.getJSONObject(k);
                Bosch.Room r = new Bosch.Room();
                r.id = o.getString("id");
                r.name = o.getString("name");
                r.climateDevice = o.isNull("cd") ? null : o.getString("cd");
                r.hmipGroup = o.isNull("hg") ? null : o.getString("hg");
                r.current = o.isNull("cur") ? Double.NaN : o.getDouble("cur");
                r.setpoint = o.isNull("set") ? Double.NaN : o.getDouble("set");
                r.mode = o.isNull("mode") ? null : o.getString("mode");
                out.add(r);
            }
            return out;
        } catch (Exception e) { return null; }
    }

    private Bosch.Room pinnedRoom() {
        String id = act.getSharedPreferences("home", Activity.MODE_PRIVATE).getString("climateRoom", null);
        if (id == null || rooms == null) return null;
        for (Bosch.Room r : rooms) if (id.equals(r.id)) return r;
        return null;
    }

    /** Lets the user choose which room is shown next to the clock. */
    private void pickClimateRoom() {
        if (rooms == null || rooms.isEmpty()) {
            new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Heizung festlegen")
                    .setMessage("Noch keine Heizung verbunden. Sobald Bosch Smart Home oder Homematic IP gekoppelt sind, "
                            + "kannst du hier den Raum wählen, der neben der Uhrzeit angezeigt wird.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String[] names = new String[rooms.size() + 1];
        names[0] = "Durchschnitt aller Räume";
        for (int i = 0; i < rooms.size(); i++) {
            Bosch.Room r = rooms.get(i);
            names[i + 1] = r.name + (r.hmipGroup != null ? "  ·  Homematic" : "  ·  Bosch")
                    + (Double.isNaN(r.current) ? "" : "  ·  " + fmt(r.current) + "°");
        }
        new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Heizung neben der Uhrzeit")
                .setItems(names, (d, w) -> {
                    act.getSharedPreferences("home", Activity.MODE_PRIVATE).edit()
                            .putString("climateRoom", w == 0 ? null : rooms.get(w - 1).id).apply();
                    renderHeader();
                }).show();
    }

    /** 22.0 -> "22", 21.5 -> "21,5" */
    private static String short1(double d) {
        return d == Math.rint(d) ? String.valueOf((int) d) : fmt(d);
    }

    private static String fmt(double d) {
        return String.format(Locale.GERMANY, "%.1f", d);
    }
}

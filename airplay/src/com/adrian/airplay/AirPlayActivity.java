package com.adrian.airplay;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.security.SecureRandom;
import java.util.Locale;

/** AirPlay receiver: discoverable while open, shows the mirrored screen full-screen. */
public class AirPlayActivity extends Activity implements RtspServer.Listener, Player.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private SurfaceView surfaceView;
    private View idle;
    private TextView status;
    private Player player;
    private RtspServer server;
    private Bonjour bonjour;
    private int videoW = 16, videoH = 9;
    private String airplayName;

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        idle = buildIdle();
        root.addView(idle, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        player = new Player(this);
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { player.setSurface(h.getSurface()); }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
            @Override public void surfaceDestroyed(SurfaceHolder h) { player.setSurface(null); }
        });
    }

    @Override protected void onStart() {
        super.onStart();
        // Android's mDNS advertiser rejects an instance name already used by another
        // service type (_androidtvremote2 uses the device name), so add an invisible NBSP.
        String name = deviceName() + " ";
        String id = deviceId();
        SharedPreferences prefs = getSharedPreferences("airplay", MODE_PRIVATE);
        network.setText(networkText());
        player.setAudioEnabled(SettingsActivity.audio(prefs));
        if (!SettingsActivity.enabled(prefs)) {
            airplayName = name;
            status.setText("AirPlay ist ausgeschaltet");
            return;
        }
        try {
            server = new RtspServer(name, id, this, player);
            server.start();
            bonjour = new Bonjour(this, () -> main.post(() -> {
                // Name already used by another mDNS service on the TV: retry with a suffix.
                String alt = deviceName() + " AirPlay";
                airplayName = alt;
                server.setName(alt);
                bonjour.stop();
                bonjour.start(alt, id, RtspServer.PORT);
                status.setText(alt);
            }));
            airplayName = name;
            bonjour.start(name, id, RtspServer.PORT);
            status.setText(name.trim());
            network.setText(networkText());
        } catch (Exception e) {
            Log.e(RtspServer.TAG, "start", e);
            status.setText("Fehler beim Start: " + e.getMessage());
        }
    }

    @Override protected void onStop() {
        super.onStop();
        if (bonjour != null) { bonjour.stop(); bonjour = null; }
        if (server != null) { server.stop(); server = null; }
        player.stopAll();
    }

    private String deviceName() {
        String n = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
        return n == null || n.isEmpty() ? "Google TV" : n;
    }

    /** Stable pseudo MAC used as AirPlay device id. */
    private String deviceId() {
        SharedPreferences p = getSharedPreferences("airplay", MODE_PRIVATE);
        String id = p.getString("id", null);
        if (id == null) {
            byte[] m = new byte[6];
            new SecureRandom().nextBytes(m);
            m[0] = (byte) ((m[0] & 0xFE) | 0x02); // locally administered, unicast
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(i == 0 ? "" : ":").append(String.format(Locale.ROOT, "%02X", m[i]));
            id = sb.toString();
            p.edit().putString("id", id).apply();
        }
        return id;
    }

    // ---------------------------------------------------------------- UI

    private View buildIdle() {
        Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
        Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);

        FrameLayout f = new FrameLayout(this);
        f.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF2B2522, 0xFF1A1716, 0xFF0B0A0A}));
        View glow = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(dp(520));
        g.setColors(new int[]{0x33C9A27E, 0x00000000});
        glow.setBackground(g);
        f.addView(glow, new FrameLayout.LayoutParams(dp(1100), dp(1100), Gravity.TOP | Gravity.END));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(80), 0, dp(72), 0);
        f.addView(row, new FrameLayout.LayoutParams(-1, -1));

        // Left: title, tagline, the two AirPlay glyphs
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = text(72, 0xFFFFFFFF, bold);
        title.setText("AirPlay");
        title.setLetterSpacing(-0.02f);
        title.setGravity(Gravity.CENTER);
        TextView tag = text(20, 0xF2FFFFFF, medium);
        tag.setText("Teile Inhalte kabellos von deinem\niPhone, iPad oder Mac");
        tag.setGravity(Gravity.CENTER);
        tag.setLineSpacing(dp(2), 1f);
        LinearLayout icons = new LinearLayout(this);
        icons.setGravity(Gravity.CENTER_VERTICAL);
        ImageView screen = new ImageView(this);
        screen.setImageResource(R.drawable.ic_airplay);
        ImageView audio = new ImageView(this);
        audio.setImageResource(R.drawable.ic_airplay_audio);
        icons.addView(screen, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(54), dp(54));
        ap.leftMargin = dp(56);
        icons.addView(audio, ap);
        left.addView(title);
        left.addView(tag, lp(0, 18));
        left.addView(icons, lp(0, 34));
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

        // Right: glass info card, settings button, footnote
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0x5C8A96B4);
        cardBg.setCornerRadius(dp(9));
        card.setBackground(cardBg);
        card.setPadding(dp(26), dp(20), dp(26), dp(22));
        TextView netLabel = text(17, 0xD9FFFFFF, medium);
        netLabel.setText("Netzwerk");
        network = text(19, 0xFFFFFFFF, bold);
        View rule = new View(this);
        rule.setBackgroundColor(0x40FFFFFF);
        TextView devLabel = text(17, 0xD9FFFFFF, medium);
        devLabel.setText("Dieses Gerät wählen");
        status = text(19, 0xFFFFFFFF, bold);
        status.setText("Startet …");
        card.addView(netLabel);
        card.addView(network, lp(0, 4));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(1));
        rp.setMargins(0, dp(18), 0, dp(18));
        card.addView(rule, rp);
        card.addView(devLabel);
        card.addView(status, lp(0, 4));
        right.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView settings = text(18, 0xFFFFFFFF, medium);
        settings.setText("AirPlay-Einstellungen");
        settings.setFocusable(true);
        settings.setClickable(true);
        settings.setPadding(dp(24), dp(12), dp(24), dp(12));
        settings.setCompoundDrawablePadding(dp(12));
        GradientDrawable on = new GradientDrawable();
        on.setColor(0xFFF2F2F2);
        on.setCornerRadius(dp(7));
        GradientDrawable off = new GradientDrawable();
        off.setColor(0x1FFFFFFF);
        off.setCornerRadius(dp(7));
        android.graphics.drawable.StateListDrawable sb = new android.graphics.drawable.StateListDrawable();
        sb.addState(new int[]{android.R.attr.state_focused}, on);
        sb.addState(new int[]{}, off);
        settings.setBackground(sb);
        settings.setTextColor(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}}, new int[]{0xFF1C1C1E, 0xFFFFFFFF}));
        settings.setOnClickListener(v -> startActivity(new android.content.Intent(this, SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_NAME, airplayName)
                .putExtra(SettingsActivity.EXTRA_ID, deviceId())
                .putExtra(SettingsActivity.EXTRA_NETWORK, networkText())
                .putExtra(SettingsActivity.EXTRA_IP, localIp())));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(16);
        right.addView(settings, sp);

        TextView foot = text(15, 0xD9FFFFFF, regular);
        foot.setText("Stelle sicher, dass dein iPhone, iPad oder Mac mit demselben WLAN verbunden ist wie dieses Gerät. "
                + "Dann im Kontrollzentrum „Bildschirmsynchronisierung“ wählen.");
        foot.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.setMargins(dp(20), dp(22), dp(10), 0);
        right.addView(foot, fp);
        row.addView(right, new LinearLayout.LayoutParams(dp(400), -2));

        settings.post(settings::requestFocus);
        return f;
    }

    private TextView network;

    private View ipColumn(String label, String value, Typeface medium, Typeface bold) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        TextView l = text(15, 0xD9FFFFFF, medium);
        l.setText(label);
        TextView v = text(18, 0xFFFFFFFF, bold);
        v.setText(value);
        c.addView(l);
        c.addView(v, lp(0, 3));
        return c;
    }

    private String networkText() {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        String type = nc == null ? "Nicht verbunden"
                : nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet"
                : nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ? wifiName() : "Netzwerk";
        return type;
    }

    /** SSID of the current Wi-Fi (needs the location permission), else just "WLAN". */
    @SuppressWarnings("deprecation")
    private String wifiName() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            String ssid = wm.getConnectionInfo().getSSID();
            if (ssid != null && !ssid.contains("unknown ssid")) return ssid.replace("\"", "");
        } catch (Exception ignored) { }
        return "WLAN";
    }

    private static String localIp() {
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (!a.isLoopbackAddress() && a instanceof java.net.Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void showSettings() {
        String msg = "Name:  " + airplayName + "\nGeräte-ID:  " + deviceId() + "\nNetzwerk:  " + networkText() + "\nIP-Adresse:  " + localIp()
                + "\nPort:  " + RtspServer.PORT + "\n\nDer Empfang ist aktiv, solange diese App geöffnet ist.";
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("AirPlay-Einstellungen")
                .setMessage(msg)
                .setPositiveButton("Netzwerk-Einstellungen", (d, w) -> {
                    try { startActivity(new android.content.Intent(Settings.ACTION_WIFI_SETTINGS)); } catch (Exception ignored) { }
                })
                .setNegativeButton("Schließen", null)
                .show();
    }

    private LinearLayout.LayoutParams lp(int l, int t) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.setMargins(dp(l), dp(t), 0, 0);
        return p;
    }

    private TextView text(float sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        return t;
    }

    private void layoutSurface() {
        int W = root.getWidth(), H = root.getHeight();
        if (W == 0 || H == 0) return;
        float s = Math.min((float) W / videoW, (float) H / videoH);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(Math.round(videoW * s), Math.round(videoH * s), Gravity.CENTER);
        surfaceView.setLayoutParams(p);
    }

    // ---------------------------------------------------------------- callbacks

    @Override public void onSessionStart() {
        main.post(() -> {
            status.setText("Verbinde …");
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    @Override public void onSessionEnd() {
        main.post(() -> {
            player.stopAll();
            idle.setVisibility(View.VISIBLE);
            idle.animate().alpha(1f).setDuration(250).start();
            status.setText(airplayName.trim());
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    @Override public void onVolume(float linear) { player.setVolume(linear); }

    @Override public void onVideoSize(int w, int h) {
        main.post(() -> {
            if (w <= 0 || h <= 0 || (w == videoW && h == videoH)) return;
            videoW = w;
            videoH = h;
            layoutSurface();
        });
    }

    @Override public void onFirstFrame() {
        main.post(() -> idle.animate().alpha(0f).setDuration(300).withEndAction(() -> idle.setVisibility(View.GONE)).start());
    }
}

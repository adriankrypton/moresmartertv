package com.adrian.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.view.View;

/**
 * Fire OS sends the Home button to Amazon's ad-filled launcher and neither lets it be replaced
 * nor allows third-party accessibility services. This service watches the foreground app via
 * usage stats (permission granted once with
 * {@code adb shell appops set com.adrian.tvhome GET_USAGE_STATS allow}) and brings
 * MoreSmarterTV back whenever Amazon's home screen comes up.
 */
public class HomeWatch extends Service {
    private static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";
    private static final long INTERVAL_MS = 250;

    private static final Handler MAIN = new Handler(android.os.Looper.getMainLooper());
    private static android.graphics.Bitmap lastFrame;
    private static HomeWatch instance;
    private View overlay;

    private HandlerThread thread;
    private Handler handler;
    private long lastCheck; // timestamp of the last foreground event handled
    private long lastRedirect;

    /** Starts the watcher on Fire TV devices; does nothing elsewhere. */
    static void start(Context c) {
        try {
            c.getPackageManager().getPackageInfo(AMAZON_LAUNCHER, 0);
            c.startService(new Intent(c, HomeWatch.class));
        } catch (PackageManager.NameNotFoundException ignored) { }
    }

    private final Runnable check = new Runnable() {
        @Override public void run() {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            // Overlapping window: events can be recorded slightly late
            // First run looks further back, so an Amazon home already showing at boot is replaced too
            UsageEvents events = usm.queryEvents(lastCheck == 0 ? now - 15 * 60_000 : now - 10_000, now + 1_000);
            String pkg = null, cls = null;
            long ts = 0;
            UsageEvents.Event e = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    pkg = e.getPackageName();
                    cls = e.getClassName();
                    ts = e.getTimeStamp();
                }
            }
            long up = android.os.SystemClock.uptimeMillis();
            if (ts > lastCheck && AMAZON_LAUNCHER.equals(pkg) && cls != null && cls.contains("HomeActivity")
                    && up - lastRedirect > 600) { // debounce, avoids redirect loops
                lastRedirect = up;
                lastCheck = ts;
                android.util.Log.i("TvHome", "Amazon home in front, switching to MoreSmarterTV");
                // Android holds activity starts back ~5 s after Home. Cover the screen right away
                // and let an alarm start the launcher: alarms are fired by system_server, which is
                // exempt from that hold (needs alarm_manager_constants min_futurity=0).
                MAIN.post(HomeWatch.this::showOverlay);
                launchViaAlarm();
            } else if (ts > lastCheck) {
                lastCheck = ts;
            }
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    /** Keeps a picture of the launcher to show while Android delays its return. */
    static void snapshot(View root) {
        try {
            if (root.getWidth() == 0) return;
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(root.getWidth(), root.getHeight(),
                    android.graphics.Bitmap.Config.RGB_565);
            root.draw(new android.graphics.Canvas(b));
            lastFrame = b;
        } catch (Throwable ignored) { }
    }

    static void hideOverlay() {
        MAIN.post(() -> { if (instance != null) instance.removeOverlay(); });
    }

    private void showOverlay() {
        if (overlay != null || !android.provider.Settings.canDrawOverlays(this)) return;
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        iv.setBackgroundColor(0xFF000000);
        if (lastFrame != null) iv.setImageBitmap(lastFrame);

        int type = android.os.Build.VERSION.SDK_INT >= 26
                ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : android.view.WindowManager.LayoutParams.TYPE_PHONE;
        android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(-1, -1, type,
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        // Fire OS 7 lifts the cover quickly and must not hold focus; on Fire OS 6
                        // it stays ~5 s and swallows keys meant for the Amazon home underneath
                        | (android.os.Build.VERSION.SDK_INT >= 28
                                ? android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE : 0),
                android.graphics.PixelFormat.OPAQUE);
        try {
            ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).addView(iv, lp);
            overlay = iv;
            // Backstop if the launch does not land
            MAIN.postDelayed(this::removeOverlay, android.os.Build.VERSION.SDK_INT >= 28 ? 2500 : 7000);
        } catch (Exception ignored) { }
    }

    private void removeOverlay() {
        if (overlay == null) return;
        final View v = overlay;
        overlay = null;
        v.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            try { ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).removeView(v); } catch (Exception ignored) { }
        }).start();
    }

    private void launchViaAlarm() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setComponent(new android.content.ComponentName(this, MainActivity.class))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, flags);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            // Fire OS 7+: alarms fire from system_server, which is exempt from the hold
            ((AlarmManager) getSystemService(ALARM_SERVICE))
                    .setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis(), pi);
        } else {
            // Fire OS 6 (Android 7): no way around the hold without root. The start is queued
            // and lands when the hold ends; until then the overlay shows the launcher.
            startActivity(i);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        // Foreground, so Fire OS does not stop the watcher in the background
        android.app.Notification n = new android.app.Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("MoreSmarterTV")
                .setContentText("Startbildschirm aktiv")
                .setPriority(android.app.Notification.PRIORITY_MIN)
                .build();
        startForeground(1, n);
        thread = new HandlerThread("home-watch");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(check);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        thread.quitSafely();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    /** Restarts the watcher after a reboot. */
    public static class Boot extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) { start(c); }
    }
}

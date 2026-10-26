package com.adrian.tvhome;

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

    private HandlerThread thread;
    private Handler handler;
    private long lastCheck; // timestamp of the last foreground event handled

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
            if (ts > lastCheck && AMAZON_LAUNCHER.equals(pkg) && cls != null && cls.contains("HomeActivity")) {
                lastCheck = ts;
                android.util.Log.i("TvHome", "Amazon home in front, switching to MoreSmarterTV");
                startActivity(new Intent(HomeWatch.this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            } else if (ts > lastCheck) {
                lastCheck = ts;
            }
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
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

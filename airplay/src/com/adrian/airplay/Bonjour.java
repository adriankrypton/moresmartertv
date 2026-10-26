package com.adrian.airplay;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import com.github.serezhka.jap2lib.Pairing;

/** Advertises _airplay._tcp and _raop._tcp via the system mDNS responder. */
final class Bonjour {
    // Feature bits as used by UxPlay (mirroring + audio, legacy pairing)
    static final long FEATURES = 0x5A7FFEE6L;

    private final NsdManager nsd;
    private final Runnable onConflict;
    private NsdManager.RegistrationListener airplayL, raopL;

    Bonjour(Context c, Runnable onConflict) {
        nsd = (NsdManager) c.getSystemService(Context.NSD_SERVICE);
        this.onConflict = onConflict;
    }

    void start(String name, String deviceId, int port) {
        airplayL = new L("airplay", onConflict);
        raopL = new L("raop", null);
        String pk = hex(Pairing.publicKey());

        NsdServiceInfo a = new NsdServiceInfo();
        a.setServiceName(name);
        a.setServiceType("_airplay._tcp");
        a.setPort(port);
        a.setAttribute("deviceid", deviceId);
        a.setAttribute("features", "0x5A7FFEE6,0x0");
        a.setAttribute("flags", "0x4");
        a.setAttribute("model", "AppleTV3,2");
        a.setAttribute("pk", pk);
        a.setAttribute("pi", "2e388006-13ba-4041-9a67-25dd4a43d536");
        a.setAttribute("srcvers", "220.68");
        a.setAttribute("vv", "2");
        nsd.registerService(a, NsdManager.PROTOCOL_DNS_SD, airplayL);

        NsdServiceInfo r = new NsdServiceInfo();
        r.setServiceName(deviceId.replace(":", "") + "@" + name);
        r.setServiceType("_raop._tcp");
        r.setPort(port);
        r.setAttribute("ch", "2");
        r.setAttribute("cn", "0,1,2,3");
        r.setAttribute("da", "true");
        r.setAttribute("et", "0,3,5");
        r.setAttribute("vv", "2");
        r.setAttribute("ft", "0x5A7FFEE6,0x0");
        r.setAttribute("am", "AppleTV3,2");
        r.setAttribute("md", "0,1,2");
        r.setAttribute("rhd", "5.6.0.0");
        r.setAttribute("pw", "false");
        r.setAttribute("sr", "44100");
        r.setAttribute("ss", "16");
        r.setAttribute("sv", "false");
        r.setAttribute("tp", "UDP");
        r.setAttribute("txtvers", "1");
        r.setAttribute("sf", "0x4");
        r.setAttribute("vs", "220.68");
        r.setAttribute("vn", "65537");
        r.setAttribute("pk", pk);
        nsd.registerService(r, NsdManager.PROTOCOL_DNS_SD, raopL);
    }

    void stop() {
        try { nsd.unregisterService(airplayL); } catch (Exception ignored) { }
        try { nsd.unregisterService(raopL); } catch (Exception ignored) { }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static final class L implements NsdManager.RegistrationListener {
        final String what;
        final Runnable onFail;
        L(String what, Runnable onFail) { this.what = what; this.onFail = onFail; }
        @Override public void onServiceRegistered(NsdServiceInfo i) { Log.i(RtspServer.TAG, what + " registered as " + i.getServiceName()); }
        @Override public void onRegistrationFailed(NsdServiceInfo i, int e) {
            Log.w(RtspServer.TAG, what + " registration failed " + e);
            if (onFail != null) onFail.run();
        }
        @Override public void onServiceUnregistered(NsdServiceInfo i) { }
        @Override public void onUnregistrationFailed(NsdServiceInfo i, int e) { }
    }
}

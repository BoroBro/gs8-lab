package com.gs8.lab;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

public class MainActivity extends Activity {

    private static final String TAG = "GS8Lab";
    private static final String HUR_PKG = "com.andrerinas.headunitrevived";
    private static final String HUR_LOCATION_ACTION = "com.andrerinas.headunitrevived.LOCATION_UPDATE";
    private static final int REQ_PERM = 10;

    private LocationManager mLm;
    private TextView mStatus;
    private CheckBox mAutoFeed;
    private final Handler mUi = new Handler(Looper.getMainLooper());

    private boolean mRunning = false;
    private long mStartMs = 0;
    private long mTtffMs = -1;
    private Location mLastFix;
    private String mSatSummary = "спутники: —";

    // ------------------------------------------------------------------ UI
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        mLm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 20, 20, 20);

        TextView title = new TextView(this);
        title.setText("GS8 Lab — GNSS диагностика");
        title.setTextSize(18);
        root.addView(title);

        addBtn(root, "Старт GNSS", v -> start());
        addBtn(root, "Стоп", v -> stop());
        addBtn(root, "Отправить фикс в HUR", v -> feedToHur());

        mAutoFeed = new CheckBox(this);
        mAutoFeed.setText("Авто-отправка каждого фикса в HUR");
        root.addView(mAutoFeed);

        addBtn(root, "Настройки локации", v ->
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));

        mStatus = new TextView(this);
        mStatus.setTextSize(13);
        mStatus.setTypeface(android.graphics.Typeface.MONOSPACE);
        mStatus.setTextColor(Color.parseColor("#00C853"));
        mStatus.setBackgroundColor(Color.BLACK);
        mStatus.setPadding(12, 12, 12, 12);
        ScrollView sv = new ScrollView(this);
        sv.addView(mStatus);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        render();
    }

    private void addBtn(LinearLayout p, String t, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(t); btn.setAllCaps(false); btn.setOnClickListener(l);
        p.addView(btn);
    }

    @Override protected void onResume() { super.onResume(); render(); }
    @Override protected void onDestroy() { super.onDestroy(); stop(); }

    // ------------------------------------------------------------------ start/stop
    private boolean hasPerm() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void start() {
        if (!hasPerm()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_PERM);
            return;
        }
        if (mRunning) return;
        try {
            mLm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, mLocListener);
            mLm.registerGnssStatusCallback(mGnss, mUi);
            mRunning = true;
            mStartMs = SystemClock.elapsedRealtime();
            mTtffMs = -1;
            Log.i(TAG, "GNSS started");
        } catch (SecurityException e) {
            Log.w(TAG, "perm: " + e);
        }
        render();
    }

    private void stop() {
        if (!mRunning) return;
        try { mLm.removeUpdates(mLocListener); } catch (Exception ignore) {}
        try { mLm.unregisterGnssStatusCallback(mGnss); } catch (Exception ignore) {}
        mRunning = false;
        render();
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        if (rc == REQ_PERM && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) start();
        else render();
    }

    // ------------------------------------------------------------------ callbacks
    private final LocationListener mLocListener = new LocationListener() {
        @Override public void onLocationChanged(Location loc) {
            mLastFix = loc;
            if (mTtffMs < 0) mTtffMs = SystemClock.elapsedRealtime() - mStartMs;
            if (mAutoFeed.isChecked()) sendToHur(loc);
            render();
        }
        @Override public void onStatusChanged(String p, int s, Bundle e) {}
        @Override public void onProviderEnabled(String p) { render(); }
        @Override public void onProviderDisabled(String p) { render(); }
    };

    private final GnssStatus.Callback mGnss = new GnssStatus.Callback() {
        @Override public void onSatelliteStatusChanged(GnssStatus st) {
            int n = st.getSatelliteCount();
            int[] seen = new int[8], used = new int[8];
            float cn0Max = 0; int cn0Cnt = 0; float cn0Sum = 0;
            for (int i = 0; i < n; i++) {
                int c = st.getConstellationType(i);
                if (c < 0 || c > 7) c = 0;
                seen[c]++;
                boolean uf = st.usedInFix(i);
                if (uf) used[c]++;
                float cn0 = st.getCn0DbHz(i);
                if (cn0 > 0) { cn0Sum += cn0; cn0Cnt++; if (cn0 > cn0Max) cn0Max = cn0; }
            }
            int totSeen = 0, totUsed = 0;
            for (int c = 0; c < 8; c++) { totSeen += seen[c]; totUsed += used[c]; }
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US, "спутники: видно %d, в фиксе %d\n", totSeen, totUsed));
            sb.append(line("GPS", seen[1], used[1]));
            sb.append(line("GLONASS", seen[3], used[3]));
            sb.append(line("BeiDou", seen[5], used[5]));
            sb.append(line("Galileo", seen[6], used[6]));
            sb.append(line("QZSS", seen[4], used[4]));
            sb.append(line("SBAS", seen[2], used[2]));
            sb.append(String.format(Locale.US, "C/N0: макс %.0f, среднее %.0f dB-Hz",
                    cn0Max, cn0Cnt > 0 ? cn0Sum / cn0Cnt : 0f));
            mSatSummary = sb.toString();
            render();
        }
        @Override public void onFirstFix(int ttffMillis) {
            if (mTtffMs < 0) mTtffMs = ttffMillis;
            render();
        }
    };

    private String line(String name, int seen, int used) {
        if (seen == 0) return String.format(Locale.US, "  %-8s —\n", name);
        return String.format(Locale.US, "  %-8s %d видно / %d в фиксе\n", name, seen, used);
    }

    // ------------------------------------------------------------------ HUR feed
    private void feedToHur() {
        if (mLastFix == null) { toast("Нет фикса — сначала поймайте GPS"); return; }
        sendToHur(mLastFix);
        toast("Отправлено в HUR");
    }

    private void sendToHur(Location loc) {
        try {
            Intent it = new Intent(HUR_LOCATION_ACTION);
            it.setPackage(HUR_PKG);
            it.putExtra(LocationManager.KEY_LOCATION_CHANGED, loc);
            sendBroadcast(it);
            Log.i(TAG, "-> HUR LOCATION_UPDATE " + loc.getLatitude() + "," + loc.getLongitude());
        } catch (Exception e) {
            Log.w(TAG, "feed err: " + e);
        }
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ render
    private void render() {
        StringBuilder sb = new StringBuilder();
        boolean gpsOn = false;
        try { gpsOn = mLm.isProviderEnabled(LocationManager.GPS_PROVIDER); } catch (Exception ignore) {}
        sb.append("Разрешение FINE_LOCATION: ").append(hasPerm() ? "есть ✓" : "НЕТ ✗").append('\n');
        sb.append("GPS провайдер: ").append(gpsOn ? "включён ✓" : "ВЫКЛЮЧЕН ✗").append('\n');
        sb.append("Статус: ").append(mRunning ? "слушаем GNSS…" : "остановлено").append('\n');
        sb.append("HUR установлен: ").append(isInstalled(HUR_PKG) ? "да ✓" : "нет ✗").append('\n');
        sb.append("TTFF: ").append(mTtffMs >= 0 ? (mTtffMs / 1000.0 + " c") : "—").append("\n\n");

        if (mLastFix != null) {
            Location f = mLastFix;
            sb.append(String.format(Locale.US,
                    "ФИКС\n  %.6f, %.6f\n  точность: %.1f м\n  скорость: %.1f км/ч\n  высота: %.0f м\n  провайдер: %s\n\n",
                    f.getLatitude(), f.getLongitude(),
                    f.hasAccuracy() ? f.getAccuracy() : -1f,
                    f.hasSpeed() ? f.getSpeed() * 3.6f : -1f,
                    f.hasAltitude() ? f.getAltitude() : 0.0,
                    f.getProvider()));
        } else {
            sb.append("ФИКС: пока нет\n\n");
        }
        sb.append(mSatSummary);
        mStatus.setText(sb.toString());
    }

    private boolean isInstalled(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }
}

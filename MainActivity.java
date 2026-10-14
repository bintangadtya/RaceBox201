package com.oped.racebox;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.File;
import java.io.FileWriter;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements LocationListener {
    private LocationManager locationManager;
    private TextView gpsStatus, speedView, accuracyView, distanceView, timeView, peakView, resultView, historyView;
    private Spinner modeSpinner;
    private Button armButton, stopButton, resetButton;
    private GraphView graph;

    private boolean armed = false, running = false, finished = false;
    private long runStartElapsedNs = 0L;
    private double distanceM = 0, peakKmh = 0;
    private double targetKmh = 60, targetM = 201;
    private Location previousLocation;
    private double lastRawKmh = 0;
    private long lastFixElapsedNs = 0;
    private final ArrayList<Sample> samples = new ArrayList<>();
    private final ArrayList<String> recentRuns = new ArrayList<>();
    private BufferedWriter csvWriter;
    private File currentCsv;
    private long lastUiUpdateMs = 0;

    private static class Sample {
        final double seconds, speedKmh, distanceM, accuracyM;
        Sample(double t, double s, double d, double a) { seconds=t; speedKmh=s; distanceM=d; accuracyM=a; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        locationManager = (LocationManager)getSystemService(LOCATION_SERVICE);
        buildUi();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 100);
        } else startGps();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 22, 20, 22);
        root.setBackgroundColor(Color.rgb(12,15,20));
        scroll.addView(root);

        TextView title = text("RACEBOX GPS PRO", 25, Color.WHITE);
        title.setGravity(Gravity.CENTER); root.addView(title);
        TextView subtitle = text("Pengukur performa berbasis GNSS Android", 13, Color.LTGRAY);
        subtitle.setGravity(Gravity.CENTER); subtitle.setPadding(0,0,0,14); root.addView(subtitle);

        gpsStatus = text("GPS: menunggu izin", 14, Color.LTGRAY); root.addView(gpsStatus);
        accuracyView = text("Akurasi: —", 14, Color.LTGRAY); root.addView(accuracyView);
        speedView = text("0.0 km/j", 42, Color.rgb(70,220,150));
        speedView.setGravity(Gravity.CENTER); speedView.setPadding(0,12,0,12); root.addView(speedView);

        LinearLayout metrics = new LinearLayout(this);
        distanceView = text("Jarak\n0.0 m", 16, Color.WHITE);
        timeView = text("Waktu\n0.000 s", 16, Color.WHITE);
        metrics.addView(distanceView, new LinearLayout.LayoutParams(0,-2,1));
        metrics.addView(timeView, new LinearLayout.LayoutParams(0,-2,1));
        root.addView(metrics);
        peakView = text("Top speed run: 0.0 km/j", 15, Color.WHITE); root.addView(peakView);

        TextView modeLabel = text("Pilih target", 14, Color.LTGRAY); root.addView(modeLabel);
        modeSpinner = new Spinner(this);
        String[] modes = {"0–60 km/j", "0–100 km/j", "201 meter", "402 meter"};
        modeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, modes));
        root.addView(modeSpinner);
        modeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                targetKmh = pos == 1 ? 100 : 60;
                targetM = pos == 3 ? 402 : 201;
                modeLabel.setText(pos < 2 ? "Target: 0 ke " + (int)targetKmh + " km/j" : "Target: " + (int)targetM + " meter");
            }
        });

        LinearLayout controls = new LinearLayout(this);
        armButton = new Button(this); armButton.setText("ARM");
        armButton.setOnClickListener(v -> armRun());
        stopButton = new Button(this); stopButton.setText("STOP");
        stopButton.setOnClickListener(v -> finishRun("Dihentikan manual"));
        resetButton = new Button(this); resetButton.setText("RESET");
        resetButton.setOnClickListener(v -> resetUi());
        controls.addView(armButton, new LinearLayout.LayoutParams(0,-2,1));
        controls.addView(stopButton, new LinearLayout.LayoutParams(0,-2,1));
        controls.addView(resetButton, new LinearLayout.LayoutParams(0,-2,1));
        root.addView(controls);

        graph = new GraphView(); graph.setMinimumHeight(210); root.addView(graph);
        resultView = text("Pilih mode, tunggu GPS stabil, lalu tekan ARM saat motor berhenti.", 14, Color.WHITE);
        resultView.setPadding(0,12,0,12); root.addView(resultView);
        TextView historyLabel = text("RIWAYAT SESI", 16, Color.WHITE); root.addView(historyLabel);
        historyView = text("Belum ada sesi.", 13, Color.LTGRAY); root.addView(historyView);

        TextView warning = text("Catatan: ini bukan produk resmi RaceBox. Akurasi bergantung pada GPS HP. Gunakan hanya di area tertutup/legal; jangan mengoperasikan HP saat berkendara.", 12, Color.rgb(255,190,90));
        warning.setPadding(0,16,0,0); root.addView(warning);
        setContentView(scroll);
    }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setPadding(0,7,0,7); return t;
    }

    private void startGps() {
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
            // Ask for fastest available updates. Actual rate is controlled by the phone/provider/receiver.
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, this, Looper.getMainLooper());
            gpsStatus.setText("GPS: mencari sinyal…");
        } catch (SecurityException e) {
            gpsStatus.setText("Izin lokasi presisi belum diberikan.");
        } catch (Exception e) {
            gpsStatus.setText("GPS tidak tersedia: " + e.getMessage());
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == 100 && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) startGps();
        else gpsStatus.setText("Izin lokasi ditolak. Aktifkan lokasi presisi di Pengaturan.");
    }

    private void armRun() {
        if (running || armed) return;
        if (previousLocation == null || !previousLocation.hasAccuracy() || previousLocation.getAccuracy() > 12) {
            resultView.setText("Tunggu sinyal GPS stabil. Idealnya akurasi ≤ 5–10 m dan data kecepatan tidak meloncat.");
            return;
        }
        armed = true; running = false; finished = false;
        distanceM = 0; peakKmh = 0; lastRawKmh = 0; runStartElapsedNs = 0;
        samples.clear(); graph.setSamples(samples);
        closeCsv();
        resultView.setText("ARMED — mulai dari berhenti total. Timer mulai saat GPS mendeteksi > 2 km/j.");
        armButton.setEnabled(false);
    }

    @Override public void onLocationChanged(Location loc) {
        long nowNs = loc.getElapsedRealtimeNanos() > 0 ? loc.getElapsedRealtimeNanos() : SystemClock.elapsedRealtimeNanos();
        double accuracy = loc.hasAccuracy() ? loc.getAccuracy() : 999;
        accuracyView.setText(String.format(Locale.US, "Akurasi GPS: %.1f m", accuracy));
        gpsStatus.setText(accuracy <= 8 ? "GPS: baik" : accuracy <= 15 ? "GPS: cukup" : "GPS: lemah — hasil bisa meleset");

        if (accuracy > 60) return;
        double rawSpeed = loc.hasSpeed() ? Math.max(0, loc.getSpeed() * 3.6) : 0;
        if (!loc.hasSpeed() && previousLocation != null) {
            long dtNs = nowNs - previousLocation.getElapsedRealtimeNanos();
            if (dtNs > 0) rawSpeed = previousLocation.distanceTo(loc) / (dtNs / 1e9) * 3.6;
        }
        if (rawSpeed > 350) return;
        speedView.setText(String.format(Locale.US, "%.1f km/j", rawSpeed));

        if (armed || running) {
            if (previousLocation != null) {
                long dtNs = nowNs - previousLocation.getElapsedRealtimeNanos();
                double step = previousLocation.distanceTo(loc);
                // Reject stale fixes, large jumps and low-quality points.
                if (dtNs > 0 && dtNs <= 2_000_000_000L && step <= 35 && accuracy <= 20 && previousLocation.getAccuracy() <= 20) {
                    if (running) distanceM += step;
                }
            }
            if (armed && rawSpeed >= 2.0) {
                armed = false; running = true;
                runStartElapsedNs = nowNs;
                distanceM = 0; peakKmh = rawSpeed;
                samples.clear();
                openCsv();
                resultView.setText("RUNNING — pengukuran aktif.");
            }
            if (running) {
                double seconds = (nowNs - runStartElapsedNs) / 1e9;
                peakKmh = Math.max(peakKmh, rawSpeed);
                Sample sample = new Sample(seconds, rawSpeed, distanceM, accuracy);
                if (samples.isEmpty() || seconds - samples.get(samples.size()-1).seconds >= 0.04) {
                    samples.add(sample);
                    graph.setSamples(samples);
                    writeCsv(sample);
                }
                distanceView.setText(String.format(Locale.US, "Jarak\n%.1f m", distanceM));
                timeView.setText(String.format(Locale.US, "Waktu\n%.3f s", seconds));
                peakView.setText(String.format(Locale.US, "Top speed run: %.1f km/j", peakKmh));

                boolean speedMode = modeSpinner.getSelectedItemPosition() < 2;
                boolean reached = speedMode ? rawSpeed >= targetKmh : distanceM >= targetM;
                if (reached) {
                    // Interpolate between last sample and this sample for an approximate threshold time.
                    finishRun("TARGET TERCAPAI");
                }
            }
        }
        previousLocation = loc;
        lastFixElapsedNs = nowNs;
    }

    private void openCsv() {
        try {
            File dir = new File(getExternalFilesDir(null), "runs");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Tidak bisa membuat folder");
            String name = "run_" + System.currentTimeMillis() + ".csv";
            currentCsv = new File(dir, name);
            csvWriter = new BufferedWriter(new FileWriter(currentCsv));
            csvWriter.write("seconds,speed_kmh,distance_m,gps_accuracy_m\n");
            csvWriter.flush();
        } catch (Exception e) {
            resultView.setText("Run aktif, tetapi file CSV gagal dibuat: " + e.getMessage());
        }
    }

    private void writeCsv(Sample s) {
        if (csvWriter == null) return;
        try {
            csvWriter.write(String.format(Locale.US, "%.3f,%.2f,%.2f,%.2f\n", s.seconds, s.speedKmh, s.distanceM, s.accuracyM));
            csvWriter.flush();
        } catch (IOException ignored) {}
    }

    private void closeCsv() {
        if (csvWriter != null) {
            try { csvWriter.flush(); csvWriter.close(); } catch (IOException ignored) {}
            csvWriter = null;
        }
    }

    private void finishRun(String reason) {
        if (!running && !armed) return;
        double seconds = running ? (SystemClock.elapsedRealtimeNanos() - runStartElapsedNs) / 1e9 : 0;
        boolean wasRunning = running;
        running = false; armed = false; finished = true;
        closeCsv();
        armButton.setEnabled(true);
        String file = currentCsv != null ? currentCsv.getName() : "CSV tidak tersedia";
        String summary = reason + "\nWaktu: " + String.format(Locale.US, "%.3f s", seconds)
            + "\nJarak: " + String.format(Locale.US, "%.1f m", distanceM)
            + "\nTop speed: " + String.format(Locale.US, "%.1f km/j", peakKmh)
            + "\nCSV: " + file
            + "\nHasil estimasi GPS; bukan pengukuran tersertifikasi.";
        resultView.setText(summary);
        if (wasRunning) {
            recentRuns.add(0, String.format(Locale.US, "%s | %.3f s | %.1f m | top %.1f km/j",
                modeSpinner.getSelectedItem().toString(), seconds, distanceM, peakKmh));
            while (recentRuns.size() > 8) recentRuns.remove(recentRuns.size()-1);
            StringBuilder sb = new StringBuilder();
            for (String run : recentRuns) sb.append("• ").append(run).append("\n");
            historyView.setText(sb.toString());
        }
    }

    private void resetUi() {
        if (running || armed) finishRun("Run dibatalkan");
        running = false; armed = false; finished = false;
        distanceM = 0; peakKmh = 0; samples.clear(); graph.setSamples(samples);
        speedView.setText("0.0 km/j"); distanceView.setText("Jarak\n0.0 m"); timeView.setText("Waktu\n0.000 s");
        peakView.setText("Top speed run: 0.0 km/j");
        resultView.setText("Pilih mode, tunggu GPS stabil, lalu tekan ARM saat motor berhenti.");
        armButton.setEnabled(true);
    }

    @Override public void onProviderEnabled(String provider) { gpsStatus.setText("GPS provider aktif"); }
    @Override public void onProviderDisabled(String provider) { gpsStatus.setText("GPS mati — aktifkan Lokasi"); }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override protected void onResume() {
        super.onResume();
        if (locationManager != null && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startGps();
    }

    @Override protected void onPause() {
        super.onPause();
        if (locationManager != null) locationManager.removeUpdates(this);
        closeCsv();
    }

    private class GraphView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private List<Sample> data = new ArrayList<>();
        GraphView() { super(MainActivity.this); setBackgroundColor(Color.rgb(22,26,33)); }
        void setSamples(List<Sample> s) { data = new ArrayList<>(s); invalidate(); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float left=48, right=getWidth()-16, top=18, bottom=getHeight()-34;
            p.setStrokeWidth(1); p.setColor(Color.rgb(65,72,82));
            for (int i=0;i<=4;i++) {
                float y=top+(bottom-top)*i/4f; c.drawLine(left,y,right,y,p);
            }
            p.setColor(Color.LTGRAY); p.setTextSize(11);
            c.drawText("Kecepatan (km/j)", left, 13, p);
            c.drawText("Waktu →", right-48, getHeight()-8, p);
            if (data.size()<2) { c.drawText("Grafik muncul saat pengukuran berjalan", left+8, (top+bottom)/2, p); return; }
            double maxT=Math.max(1,data.get(data.size()-1).seconds);
            double maxS=10;
            for (Sample s:data) maxS=Math.max(maxS,s.speedKmh);
            p.setColor(Color.rgb(70,220,150)); p.setStrokeWidth(3);
            for (int i=1;i<data.size();i++) {
                Sample a=data.get(i-1), b=data.get(i);
                float x1=(float)(left+(right-left)*a.seconds/maxT);
                float x2=(float)(left+(right-left)*b.seconds/maxT);
                float y1=(float)(bottom-(bottom-top)*a.speedKmh/maxS);
                float y2=(float)(bottom-(bottom-top)*b.speedKmh/maxS);
                c.drawLine(x1,y1,x2,y2,p);
            }
            p.setColor(Color.LTGRAY); p.setTextSize(10);
            c.drawText(String.format(Locale.US,"%.0f",maxS), 6, top+10, p);
            c.drawText("0", 22, bottom, p);
        }
    }
}

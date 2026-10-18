package com.vallen.boost;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Mesin "Root": pakai binary `screenrecord` bawaan Android lewat su.
 * Tanpa dialog izin (cocok buat auto-rekam saat game dibuka), tanpa MediaProjection.
 * Batasan bawaan screenrecord: tanpa suara, tanpa jeda, maksimal 180 detik per file
 * (jadi rekaman panjang otomatis disambung jadi beberapa file: _p2, _p3, ...).
 */
final class RecRoot implements RecEngine {
    private static final String DIR = "/sdcard/Movies/VallenBoost";
    private final Context c;
    private final Runnable onStopped;
    private volatile boolean running;
    private volatile String error;
    private volatile Uri last;
    private Thread th;

    RecRoot(Context c, Runnable onStopped) {
        this.c = c.getApplicationContext();
        this.onStopped = onStopped;
    }

    @Override public void start() throws Exception {
        if (!Boost.root && !Boost.isRoot()) throw new IllegalStateException("Akses root tidak ada");
        RootShell.run("mkdir -p " + DIR);
        final StringBuilder args = new StringBuilder();
        if (RecConfig.res(c) != 0) {
            int[] sz = RecConfig.size(c);
            args.append(" --size ").append(sz[0]).append('x').append(sz[1]);
        }
        int[] sz = RecConfig.size(c);
        args.append(" --bit-rate ").append(RecConfig.bitrate(c, sz[0], sz[1], RecConfig.fps(c)));
        final String base = RecConfig.baseName(c);
        running = true;
        th = new Thread(new Runnable() {
            @Override public void run() { loop(args.toString(), base); }
        }, "vb-rec-root");
        th.start();
        // pastikan screenrecord benar-benar jalan (gagal cepat = error)
        for (int i = 0; i < 8; i++) {
            try { Thread.sleep(250); } catch (InterruptedException ignored) {}
            if (error != null) { running = false; throw new IllegalStateException(error); }
            String pid = RootShell.run("pidof screenrecord");
            if (pid != null && !pid.isEmpty()) return;
        }
        if (error != null) { running = false; throw new IllegalStateException(error); }
    }

    private void loop(String args, String base) {
        int part = 1;
        while (running) {
            String path = DIR + "/" + base + (part > 1 ? "_p" + part : "") + ".mp4";
            long t0 = System.currentTimeMillis();
            String out = RootShell.oneShot("screenrecord" + args + " --time-limit 180 '" + path + "'", 200_000);
            if (System.currentTimeMillis() - t0 < 2500 && running) {       // mati sebelum sempat merekam
                error = "screenrecord gagal" + (out == null || out.isEmpty() ? "" : ": " + firstLine(out));
                RootShell.run("rm -f '" + path + "'");
                running = false;
                if (part > 1 && onStopped != null) onStopped.run();
                return;
            }
            scanSync(path);
            if (!running) return;
            part++;
        }
    }

    private void scanSync(String path) {
        final CountDownLatch l = new CountDownLatch(1);
        MediaScannerConnection.scanFile(c, new String[]{path}, new String[]{"video/mp4"},
            new MediaScannerConnection.OnScanCompletedListener() {
                @Override public void onScanCompleted(String p, Uri u) { if (u != null) last = u; l.countDown(); }
            });
        try { l.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    }

    @Override public void stop() {
        running = false;
        // SIGINT = screenrecord menutup file mp4 dengan benar
        RootShell.run("kill -2 $(pidof screenrecord) 2>/dev/null");
        if (th != null) {
            try { th.join(6000); } catch (InterruptedException ignored) {}
            if (th.isAlive()) RootShell.run("kill -9 $(pidof screenrecord) 2>/dev/null");
        }
    }

    @Override public boolean canPause() { return false; }
    @Override public void pause() {}
    @Override public void resume() {}
    @Override public Uri result() { return last; }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).trim();
    }
}

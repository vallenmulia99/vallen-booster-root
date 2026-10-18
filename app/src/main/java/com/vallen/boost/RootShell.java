package com.vallen.boost;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Dua sesi su terpisah, masing-masing sinkron + watchdog timeout:
 *  - run()    = sesi KONTROL: tulis sysfs, restore, setelan. Tidak pernah antre di belakang pembacaan metrik.
 *  - metric() = sesi METRIK: dumpsys, FPS, baca sensor, cari server game. Boleh lambat.
 * Jadi restore darurat karena panas tidak ikut macet kalau dumpsys lagi lambat.
 */
final class RootShell {
    private static final String END = "__VB_END__";
    private static final long T_NORMAL = 8_000;     // perintah biasa
    private static final long T_FIRST = 30_000;     // su baru dibuka: bisa nunggu dialog izin Magisk

    private static final ScheduledExecutorService watchdog =
        Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "vb-su-watchdog");
                t.setDaemon(true);
                return t;
            }
        });

    /** Satu sesi su yang dipakai berulang. */
    private static final class Session {
        private Process proc;
        private DataOutputStream in;
        private BufferedReader out;
        private boolean fresh;

        private boolean ensure() {
            try {
                if (proc != null) {
                    try { proc.exitValue(); proc = null; }          // sudah mati
                    catch (IllegalThreadStateException alive) { return true; }
                }
                proc = new ProcessBuilder("su").redirectErrorStream(true).start();
                in = new DataOutputStream(proc.getOutputStream());
                out = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                fresh = true;
                return true;
            } catch (IOException e) {
                proc = null;
                return false;
            }
        }

        private void drop() {
            try { if (proc != null) proc.destroy(); } catch (Exception ignored) {}
            proc = null;
        }

        synchronized String run(String cmd, long timeoutMs) {
            if (!ensure()) return null;
            final Process p = proc;
            final long limit = fresh ? Math.max(timeoutMs, T_FIRST) : timeoutMs;
            fresh = false;
            ScheduledFuture<?> guard = watchdog.schedule(new Runnable() {
                @Override public void run() { p.destroy(); }      // bikin readLine() balik null
            }, limit, TimeUnit.MILLISECONDS);
            try {
                // "echo;" supaya penanda END selalu di baris sendiri walau output tidak diakhiri newline
                in.writeBytes(cmd + "\necho; echo " + END + "\n");
                in.flush();
                StringBuilder sb = new StringBuilder();
                String line;
                boolean done = false;
                while ((line = out.readLine()) != null) {
                    if (line.equals(END)) { done = true; break; }
                    sb.append(line).append('\n');
                }
                if (!done) { drop(); return null; }               // su mati atau timeout
                return sb.toString().trim();
            } catch (IOException e) {
                drop();
                return null;
            } finally {
                guard.cancel(false);
            }
        }
    }

    private static final Session CTL = new Session();
    private static final Session MET = new Session();

    private RootShell() {}

    /** Sesi kontrol: semua perubahan setelan sistem. Jangan dipakai untuk dumpsys berat. */
    static String run(String cmd) { return CTL.run(cmd, T_NORMAL); }
    static String run(String cmd, long timeoutMs) { return CTL.run(cmd, timeoutMs); }

    /** Sesi metrik: pembacaan (dumpsys, sensor, /proc/net). */
    static String metric(String cmd) { return MET.run(cmd, T_NORMAL); }
    static String metric(String cmd, long timeoutMs) { return MET.run(cmd, timeoutMs); }

    /** Jalankan perintah panjang (compile dsb) di proses su sendiri, tidak mengunci dua sesi di atas. */
    static String oneShot(String cmd, long timeoutMs) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            final Process pp = p;
            ScheduledFuture<?> guard = watchdog.schedule(new Runnable() {
                @Override public void run() { pp.destroy(); }
            }, timeoutMs, TimeUnit.MILLISECONDS);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = r.readLine()) != null) sb.append(l).append('\n');
                return sb.toString().trim();
            } finally {
                guard.cancel(false);
            }
        } catch (IOException e) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }
}

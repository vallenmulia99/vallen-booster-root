package com.vallen.boost;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Jalan selama boost aktif ATAU ada sesi game (game dibuka lewat launcher).
 * - Pantau suhu baterai: lewat batas = boost dikembalikan otomatis.
 * - HUD warna ijo di bawah tengah: judul "Vallen Anti Lag", di bawahnya FPS, ping, CPU, GPU, RAM, baterai.
 * - Game ditutup (proses mati) atau ditinggal > 20 detik = semua dimatikan otomatis.
 */
public class BoostService extends Service {
    static final String UPDATE = "com.vallen.boost.UPDATE";
    static volatile int lastFps = -1, lastPing = -1;
    /** true selama service hidup; dipakai Boost.orphaned() untuk deteksi sisa boost. */
    static volatile boolean running;
    /** Sampel terakhir dari loop service, dipakai MainActivity supaya root tidak dipanggil dobel. */
    static volatile Metrics.Snap lastSnap;
    static volatile long lastSnapAt;

    private static final int GREEN = 0xFF00FF88, AMBER = 0xFFFFB020, RED = 0xFFFF3B5C;
    private static final long AWAY_MS = 20_000, GRACE_MS = 10_000, STEP_MS = 20_000;
    /** Waktu tunggu game cold start (ML + force-stop background bisa lama) sebelum dianggap gagal kebuka. */
    private static final long LAUNCH_MS = 90_000;
    /** Berapa kali cek pidof berturut-turut kosong sebelum game dianggap benar-benar ditutup. */
    private static final int GONE_CHECKS = 3;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() { step(); }
    };
    private final ExecutorService pinger = Executors.newSingleThreadExecutor();
    private final AtomicBoolean pinging = new AtomicBoolean();
    private final int[] pings = new int[5];

    private HandlerThread ht;
    private Handler bg;
    private Metrics mx;
    private NotificationManager nm;
    private WindowManager wm;
    private HudView hud;          // hanya disentuh dari main thread
    private String fg = "";
    private int tickN, pingN;
    private volatile int ping = -1;
    private volatile int jitter = -1;               // selisih ping tertinggi-terendah dari sampel terakhir
    private volatile long spikeUntil;               // sampai kapan HUD menampilkan "LAG KONEKSI"
    private volatile boolean sessLive;              // true kalau game sedang dimainkan (alert hanya saat main)
    private long lastBuzz;
    // pelacakan sesi + laporan
    private String trackSess = "";
    private long trackStart, seenAt;
    private final ArrayList<Integer> fpsLog = new ArrayList<>();
    private int pkCpu, pkGpu, pkBat10, startBat;
    private int thrN, thrTot;     // tick kena throttle sistem / total tick, buat laporan sesi
    private long stepAt;          // waktu step-down tahap 1 (0 = belum)
    private boolean gameSeen;     // game sudah pernah fokus di sesi ini (hanya thread tick)
    private int goneN;            // cek pidof kosong berturut-turut
    private volatile boolean destroyed;
    // ping ke server game (fallback 1.1.1.1)
    private volatile String pingHost = "1.1.1.1";
    private volatile int pingPort = 443;
    private volatile int pingFail;
    private long targetAt;
    private boolean targetFound;
    private final HashSet<String> badHosts = new HashSet<>();
    private volatile int icmpFail;
    private double repAvg, repLow;      // statistik laporan terakhir (buat riwayat)
    // auto boost + grafik sesi
    private long cooldownUntil;
    private final ArrayList<Integer> fpsSeries = new ArrayList<>(), tempSeries = new ArrayList<>();

    @Override public int onStartCommand(Intent i, int flags, int id) {
        nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("boost", "Boost aktif", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel("alert", "Peringatan suhu", NotificationManager.IMPORTANCE_HIGH));
        try {
            startForeground(1, note("Vallen Boost aktif", "Memulai..."));
        } catch (RuntimeException e) {
            // Android 12+ bisa menolak start FGS dari background (mis. restart sticky). Bersihkan lalu berhenti.
            new Thread(new Runnable() {
                @Override public void run() {
                    Boost.isRoot();
                    Boost.restoreAll(BoostService.this);
                    stopSelf();
                }
            }).start();
            return START_NOT_STICKY;
        }
        running = true;
        destroyed = false;
        if (ht == null) {
            ht = new HandlerThread("vb-tick");
            ht.start();
            bg = new Handler(ht.getLooper());
            mx = new Metrics(this);
            Arrays.fill(pings, -1);
            bg.post(new Runnable() {          // service bisa hidup tanpa Activity: pastikan status root diketahui
                @Override public void run() { if (!Boost.root) Boost.isRoot(); }
            });
        }
        if (i != null && i.getBooleanExtra("boot", false)) {
            bg.post(new Runnable() {          // habis reboot: setelan sysfs sudah balik normal, bersihkan sisa prefs lama
                @Override public void run() {
                    if (!Boost.root) Boost.isRoot();
                    Boost.restoreAll(BoostService.this);
                }
            });
        }
        bg.removeCallbacks(tick);
        bg.post(tick);
        main.post(new Runnable() {
            @Override public void run() {
                boolean active = Boost.gameOn(BoostService.this) || Boost.lockOn(BoostService.this)
                    || !Boost.session(BoostService.this).isEmpty();
                if (Boost.hudOn(BoostService.this) && active) showHud(); else hideHud();
            }
        });
        return START_STICKY;      // kalau dibunuh sistem, pemantau suhu hidup lagi
    }

    // ---- loop utama (thread background) ----
    private void step() {
        boolean hudOn = Boost.hudOn(this);
        boolean boostOn = Boost.gameOn(this) || Boost.lockOn(this);
        String sess = Boost.session(this);
        if (!boostOn && sess.isEmpty()) { idleAuto(); return; }

        // satu kali baca root per tick: dipakai guard suhu, HUD, dan laporan
        Metrics.Snap s = (hudOn || Boost.root) ? mx.sample(Boost.root) : null;
        int t = s != null ? s.batTemp10 : Boost.temp(this);
        // hemat panas: lagi dicas = HP lebih cepat panas, batas diturunkan 3 C (baterai) dan 5 C (chip)
        boolean eco = Boost.ecoOn(this) && Boost.charging(this);
        int max = Boost.limit(this) * 10 - (eco ? 30 : 0);
        int chip = s == null ? -1 : Math.max(s.cpuTemp, s.gpuTemp);
        int chipMax = Boost.chipLimit(this) - (eco ? 5 : 0);
        boolean hotBat = t >= max, hotChip = chip >= 0 && chip >= chipMax;

        if (!sess.isEmpty()) track(sess, s);

        if (!(hotBat || hotChip)) stepAt = 0;
        if (boostOn && (hotBat || hotChip)) {
            long now = SystemClock.uptimeMillis();
            String why = hotChip ? "Chip " + chip + "\u00b0C lewat batas " + chipMax + "\u00b0C"
                                 : String.format(Locale.US, "Baterai %.1f\u00b0C lewat batas", t / 10f);
            boolean emergency = t >= max + 20 || (chip >= 0 && chip >= chipMax + 5);
            if ((Boost.lockOn(this) || Boost.gpuMaxActive(this)) && !emergency) {
                // tahap 1: lepas Lock Clock / GPU Max (GPU turun satu OPP), Game Mode tetap jalan
                Boost.stepDown(this);
                stepAt = now;
                alert("Clock diturunkan", why + ", turun satu tahap (Game Mode tetap)");
                sendBroadcast(new Intent(UPDATE).setPackage(getPackageName())
                    .putExtra("msg", why + ", clock diturunkan satu tahap (Game Mode tetap)"));
                boostOn = Boost.gameOn(this);
                if (!boostOn && sess.isEmpty()) { cooldownUntil = now + 120_000; idleAuto(); return; }
            } else if (!emergency && stepAt != 0 && now - stepAt < STEP_MS) {
                // beri waktu suhu turun setelah tahap 1
            } else {
                // tahap 2 (atau darurat): matikan semua
                Boost.restoreAll(this, true);               // resolusi tetap (ganti resolusi pas main bikin game restart)
                Boost.setSession(this, sess);               // sesi lanjut, HUD tetap jalan
                stepAt = 0;
                alert("Boost dimatikan", why + ", HP didinginkan");
                sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()).putExtra("msg", why + ", boost dimatikan"));
                boostOn = false;
                cooldownUntil = now + 120_000;              // jangan auto boost lagi dulu, HP masih panas
                if (sess.isEmpty()) { idleAuto(); return; }
            }
        }

        if (Boost.root && tickN % 2 == 0) fg = sess.isEmpty() ? mx.foreground() : mx.foregroundOf(sess);   // sesi game: tanpa dumpsys
        if (!sess.isEmpty() && Boost.root && sessionOver(sess)) { endSession(sess); return; }

        // lindungi proses game dari LMK begitu prosesnya muncul
        if (!sess.isEmpty() && Boost.root && Boost.gameOn(this) && tickN % 2 == 0 && !Boost.protectedOn(this)) {
            Boost.protectGame(this, sess);
        }

        if (!hudOn && s != null && Boost.root && boostOn && !sess.isEmpty() && sess.equals(fg) && Boost.adaptOn(this)) {
            int f = mx.fps(fg);                                  // HUD mati tapi mode adaptif butuh FPS
            lastFps = f;
            Boost.adaptTick(this, f, chip, chipMax);
        }
        // proteksi daya (PPM) dilepas lagi kalau baterai tipis; dipasang lagi kalau sudah dicas
        if (tickN % 10 == 5 && Boost.root && (Boost.lockOn(this) || !Boost.prefs(this).getString("ppmOff", "").isEmpty())) {
            Boost.mtkPpmSync(this);
        }

        String line;
        if (hudOn && s != null) {
            boolean inGame = sess.isEmpty() ? !fg.isEmpty() && !fg.equals(getPackageName())
                                              && !fg.equals("com.android.systemui")
                                            : sess.equals(fg);
            String fgNow = fg;
            if (!inGame && Boost.root) {                      // cara cepat gagal: cek ulang pakai dumpsys window
                fgNow = mx.foreground();
                if (!fgNow.isEmpty() && !fgNow.equals(getPackageName()) && !fgNow.equals("com.android.systemui")
                    && (sess.isEmpty() || sess.equals(fgNow))) { inGame = true; fg = fgNow; }
            }
            mx.recordFrames = !sess.isEmpty();
            if (!Boost.root) { Metrics.why = "noroot"; s.fps = -1; }
            else if (!inGame) { Metrics.why = "fg:" + tail(fgNow) + " s:" + tail(sess); s.fps = -1; }
            else s.fps = mx.fps(fg);
            if (!sess.isEmpty() && inGame && s.fps > 0) fpsLog.add(s.fps);
            if (boostOn && !sess.isEmpty() && inGame) Boost.adaptTick(this, s.fps, chip, chipMax);
            if (!sess.isEmpty() && inGame) {                  // deret per detik buat grafik sesi
                fpsSeries.add(Math.max(0, s.fps));
                tempSeries.add(chip > 0 ? chip : t / 10);
            }
            sessLive = !sess.isEmpty() && inGame;
            updatePingTarget(sess);
            if (tickN % 2 == 1) pingAsync();
            s.ping = ping;
            lastFps = s.fps;
            lastPing = s.ping;

            final String[] vals = hudValues(s);
            final boolean thr = s.cpuThr || s.gpuThr;
            final int color = (hotBat || hotChip) ? RED
                : (thr || t >= max - 30 || (chip >= 0 && chip >= chipMax - 5)) ? AMBER : GREEN;
            final String flagTxt = thr ? (s.cpuThr && s.gpuThr ? "THROTTLE CPU+GPU" : s.gpuThr ? "THROTTLE GPU" : "THROTTLE CPU")
                : (Boost.pingAlertOn(this) && SystemClock.uptimeMillis() < spikeUntil) ? "LAG KONEKSI" : "";
            // selama game belum pernah fokus (masih loading), HUD tetap tampil; baru disembunyikan kalau pindah app
            final boolean visible = sess.isEmpty() || inGame || fg.isEmpty() || !gameSeen;
            final int pingMs = s.ping;
            main.post(new Runnable() {
                @Override public void run() {
                    // addView bisa gagal / overlay hilang pas game dibuka: pasang ulang tiap tick
                    if (hud == null && !destroyed) showHud();
                    if (hud == null) return;
                    if (hud.stale(BoostService.this)) hud.restyle(BoostService.this);   // ukuran / tema diganti user
                    hud.setFlag(flagTxt);
                    hud.set(vals, color, pingMs);
                    updateHudPos();
                    hud.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
                }
            });
            line = (s.fps >= 0 ? "FPS " + s.fps + "  \u2022  " : "")
                 + String.format(Locale.US, "%.1f\u00b0C  \u2022  RAM %s/%sG", s.batTemp10 / 10f,
                                 Metrics.gb(s.ramUsedMb), Metrics.gb(s.ramTotalMb))
                 + (eco ? "  \u2022  hemat panas" : "");
        } else {
            line = String.format(Locale.US, "Suhu %.1f\u00b0C (batas %d\u00b0C)", t / 10f, max / 10);
        }
        if (s != null) { lastSnap = s; lastSnapAt = SystemClock.uptimeMillis(); }
        if (tickN % 3 == 0) nm.notify(1, note("Vallen Boost aktif", line));
        tickN++;
        bg.postDelayed(tick, hudOn ? 1000 : (sess.isEmpty() ? 3000 : 2000));
    }

    /** Catat puncak suhu; reset kalau game-nya ganti. */
    private void track(String sess, Metrics.Snap s) {
        long now = SystemClock.uptimeMillis();
        if (!sess.equals(trackSess)) {
            trackSess = sess;
            trackStart = now;
            seenAt = now;
            fpsLog.clear();
            mx.frames.clear();
            gameSeen = false;
            goneN = 0;
            fpsSeries.clear();
            tempSeries.clear();
            badHosts.clear();
            targetAt = 0;
            targetFound = false;
            pkCpu = pkGpu = pkBat10 = 0;
            thrN = thrTot = 0;
            startBat = s != null ? s.batPct : Boost.battery(this);
        }
        if (s != null) {
            pkCpu = Math.max(pkCpu, s.cpuTemp);
            pkGpu = Math.max(pkGpu, s.gpuTemp);
            pkBat10 = Math.max(pkBat10, s.batTemp10);
            thrTot++;
            if (s.cpuThr || s.gpuThr) thrN++;
        }
    }

    private String buildReport() {
        long sec = (SystemClock.uptimeMillis() - trackStart) / 1000;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "Durasi %dm %02ds\n", sec / 60, sec % 60));
        int n = fpsLog.size();
        int[] ft = mx.frames.copy();
        repAvg = repLow = 0;
        sb.append(Boost.sessBoost(this) ? "Mode: Boost" + (Boost.adaptOn(this) ? " adaptif (target " + (Boost.fpsTarget(this) <= 0 ? "auto" : Boost.fpsTarget(this) + " FPS") + ")" : "")
                                         : "Mode: TANPA boost (pembanding A/B)").append('\n');
        if (ft.length >= 100) {
            double[] st = appendFrameStats(sb, ft);
            repAvg = st[0]; repLow = st[1];
        } else if (n >= 5) {
            int[] a = new int[n];
            long sum = 0;
            for (int i = 0; i < n; i++) { a[i] = fpsLog.get(i); sum += a[i]; }
            Arrays.sort(a);
            repAvg = (double) sum / n; repLow = a[(int) Math.floor(n * 0.01)];
            sb.append(String.format(Locale.US, "FPS rata-rata %d  \u2022  1%% low %d  \u2022  min %d\n",
                sum / n, a[(int) Math.floor(n * 0.01)], a[0]));
        } else {
            sb.append("FPS tidak terekam (nyalakan HUD & root)\n");
        }
        sb.append(String.format(Locale.US, "Puncak: CPU %s  GPU %s  BAT %.1f\u00b0\n",
            Metrics.deg(pkCpu > 0 ? pkCpu : -1), Metrics.deg(pkGpu > 0 ? pkGpu : -1), pkBat10 / 10f));
        if (thrTot >= 5) {
            if (thrN == 0) sb.append("Throttle sistem: tidak terdeteksi\n");
            else sb.append(String.format(Locale.US, "Throttle sistem: %d%% durasi (clock ditahan kernel/thermal)\n", thrN * 100 / thrTot));
        }
        String gn = Boost.prefs(this).getString("gpuNote", "");
        if (!gn.isEmpty()) sb.append("GPU dikunci: ").append(gn).append('\n');
        int endBat = Boost.battery(this);
        if (startBat >= 0 && endBat >= 0) sb.append("Baterai ").append(startBat).append("% \u2192 ").append(endBat).append('%');
        return sb.toString().trim();
    }

    /**
     * True kalau game sudah ditutup (proses mati) atau ditinggal terlalu lama.
     * Selama game belum pernah fokus (cold start / loading), sesi TIDAK dianggap selesai sampai LAUNCH_MS,
     * dan "proses tidak ada" harus terbaca beberapa kali berturut-turut, supaya HUD tidak mati sendiri pas buka game.
     */
    private boolean sessionOver(String sess) {
        long now = SystemClock.uptimeMillis();
        if (sess.equals(fg)) { gameSeen = true; seenAt = now; goneN = 0; }
        else if (fg.isEmpty()) seenAt = now;   // fg gagal kebaca = anggap masih di game; pidof di bawah yang jadi penentu
        boolean launching = !gameSeen && now - trackStart < LAUNCH_MS;
        if (launching) return false;
        if (now - seenAt > AWAY_MS) return true;
        if (now - trackStart > GRACE_MS && tickN % 2 == 1 && sess.matches("[A-Za-z0-9_.]+")) {
            String pid = RootShell.metric("pidof " + sess);
            if (pid == null) return false;                    // su gagal / timeout: bukan bukti game mati
            if (pid.isEmpty()) return ++goneN >= GONE_CHECKS; // proses game tidak ada, tunggu konfirmasi
            goneN = 0;
        }
        return false;
    }

    private void endSession(String sess) {
        if (sess.equals(trackSess)) {
            Boost.setReport(this, buildReport());
            if (fpsSeries.size() >= 2 || tempSeries.size() >= 2) Boost.setGraph(this, buildGraph());
            long durSec = (SystemClock.uptimeMillis() - trackStart) / 1000;
            if (durSec >= 30 && repAvg > 0) {
                int thrPct = thrTot >= 5 ? thrN * 100 / thrTot : 0;
                Boost.addHistory(this, sess, Boost.sessBoost(this), durSec, repAvg, repLow,
                    Math.max(pkCpu, pkGpu), pkBat10, thrPct, startBat, Boost.battery(this));
            }
        }
        Boost.restoreAll(this);                               // juga mengosongkan sesi
        sendBroadcast(new Intent(UPDATE).setPackage(getPackageName())
            .putExtra("msg", "Game ditutup, boost & HUD dimatikan otomatis"));
        nm.notify(3, new Notification.Builder(this, "boost")
            .setSmallIcon(R.drawable.icon_vallen)
            .setContentTitle("Game ditutup")
            .setContentText("Boost dan HUD dimatikan, setelan dikembalikan")
            .setAutoCancel(true).setTimeoutAfter(6000).build());
        cooldownUntil = SystemClock.uptimeMillis() + 15_000;   // game yang baru ditutup jangan langsung dideteksi lagi
        idleAuto();
    }

    /**
     * Tidak ada boost/sesi. Kalau Auto Boost mati: service berhenti. Kalau nyala: tetap hidup, HUD disembunyikan,
     * dan tiap 3 detik cek app di depan; begitu itu game (launcher / home screen / mana pun) langsung di-boost.
     */
    private void idleAuto() {
        if (!Boost.autoOn(this)) { stopSelf(); return; }
        main.post(new Runnable() {
            @Override public void run() { hideHud(); }
        });
        long now = SystemClock.uptimeMillis();
        if (Boost.root && now >= cooldownUntil) {
            fg = mx.foregroundGame(Boost.gameList(this));          // cek top-app game yang dikenal, tanpa dumpsys window
            if (Boost.isGame(this, fg) && !fg.equals(getPackageName())) {
                final String pkg = fg;
                Boost.prepareLaunch(this, pkg);
                String label = pkg;
                try { label = String.valueOf(getPackageManager().getApplicationInfo(pkg, 0).loadLabel(getPackageManager())); }
                catch (Exception ignored) {}
                StringBuilder how = new StringBuilder("Game Mode ON, CPU & GPU performance");
                if (Boost.profLock(this, pkg)) how.append("\n\u2022 Lock Clock ON");
                if (Boost.profRr(this, pkg)) how.append("\n\u2022 Refresh rate dikunci");
                if (Boost.profDnd(this, pkg)) how.append("\n\u2022 Jangan Ganggu ON");
                how.append(Boost.profKill(this, pkg) ? "\n\u2022 App background dibersihkan" : "\n\u2022 App background tidak disentuh");
                Boost.notifyResult(this, "Auto Boost: " + label, "Game terdeteksi, boost nyala\n\u2022 " + how);
                sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()).putExtra("msg", "Auto Boost \u2192 " + label));
                if (Boost.profKill(this, pkg)) {
                    new Thread(new Runnable() {
                        @Override public void run() { Boost.killBackground(BoostService.this, pkg); }
                    }).start();
                }
                bg.postDelayed(tick, 1000);
                return;
            }
        }
        if (tickN % 10 == 0) nm.notify(1, note("Vallen Boost siap", Boost.root ? "Auto Boost: nunggu game dibuka" : "Auto Boost butuh root"));
        tickN++;
        bg.postDelayed(tick, 3000);
    }

    // ---- ping ke server game ----
    private void setTarget(String host, int port) {
        if (host.equals(pingHost) && port == pingPort) return;
        pingHost = host;
        pingPort = port;
        pingFail = 0;
        icmpFail = 0;
        Arrays.fill(pings, -1);
        ping = -1;
    }

    /** Pilih tujuan ping: koneksi TCP game yang lagi jalan; kalau tidak ada / gagal, pakai 1.1.1.1. */
    private void updatePingTarget(String sess) {
        if (sess.isEmpty() || !Boost.root || !Boost.pingGameOn(this)) {
            targetFound = false;
            setTarget("1.1.1.1", 443);
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (targetFound && pingFail >= 4) {                   // server tidak balas TCP connect: jangan dipakai lagi
            badHosts.add(pingHost);
            targetFound = false;
            setTarget("1.1.1.1", 443);
            targetAt = now;
            return;
        }
        if (now - targetAt < (targetFound ? 30_000 : 10_000)) return;
        targetAt = now;
        String[] t = Boost.findGameServer(this, sess, badHosts);
        if (t != null) {
            boolean changed = !t[0].equals(pingHost);
            targetFound = true;
            setTarget(t[0], Integer.parseInt(t[1]));
            if (changed) sendBroadcast(new Intent(UPDATE).setPackage(getPackageName())
                .putExtra("msg", "Ping ke server game " + t[0] + ":" + t[1]));
        } else {
            targetFound = false;
            setTarget("1.1.1.1", 443);
        }
    }

    // ---- grafik sesi ----
    private String buildGraph() {
        return "f=" + join(down(fpsSeries, 100)) + ";t=" + join(down(tempSeries, 100));
    }

    private static int[] down(ArrayList<Integer> src, int max) {
        int n = src.size();
        if (n <= max) {
            int[] a = new int[n];
            for (int i = 0; i < n; i++) a[i] = src.get(i);
            return a;
        }
        int[] a = new int[max];
        for (int i = 0; i < max; i++) {
            int from = (int) ((long) i * n / max), to = (int) ((long) (i + 1) * n / max);
            if (to <= from) to = from + 1;
            long sum = 0;
            for (int k = from; k < to; k++) sum += src.get(k);
            a[i] = (int) (sum / (to - from));
        }
        return a;
    }

    private static String join(int[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(a[i]);
        }
        return sb.toString();
    }

    private void alert(String title, String text) {
        nm.notify(2, new Notification.Builder(this, "alert")
            .setSmallIcon(R.drawable.icon_vallen)
            .setContentTitle(title)
            .setContentText(text)
            .build());
    }

    /** Statistik dari frametime asli (mikrodetik): rata-rata, 1% low, 0.1% low, jank. */
    private static double[] appendFrameStats(StringBuilder sb, int[] ft) {
        int n = ft.length;
        int[] a = ft.clone();
        Arrays.sort(a);                                       // naik: frame tercepat di depan
        long sum = 0;
        for (int v : a) sum += v;
        double avg = 1e6 * n / sum;
        int median = a[n / 2];
        int w1 = Math.max(1, n / 100);
        long s1 = 0;
        for (int i = n - w1; i < n; i++) s1 += a[i];          // 1% frame terlama
        double low1 = 1e6 * w1 / s1;
        sb.append(String.format(Locale.US, "FPS rata-rata %.0f  \u2022  1%% low %.0f", avg, low1));
        if (n >= 1000) {
            int w2 = Math.max(1, n / 1000);
            long s2 = 0;
            for (int i = n - w2; i < n; i++) s2 += a[i];
            sb.append(String.format(Locale.US, "  \u2022  0.1%% low %.0f", 1e6 * w2 / s2));
        }
        sb.append('\n');
        int jank = 0;
        for (int v : a) if (v > 2 * median) jank++;           // jank = frame > 2x median
        sb.append(String.format(Locale.US, "Jank %d frame (%.1f%%)  \u2022  terburuk %d ms\n",
            jank, 100.0 * jank / n, a[n - 1] / 1000));
        return new double[]{avg, low1};
    }

    private static String tail(String x) { return x == null || x.isEmpty() ? "-" : x.length() > 8 ? x.substring(x.length() - 8) : x; }

    private static volatile String jitterTxt = "";

    private static String[] hudValues(Metrics.Snap s) {
        String[] v = new String[HudView.N];
        v[HudView.FPS] = s.fps < 0 ? (Metrics.why.isEmpty() ? "--" : Metrics.why) : String.valueOf(s.fps);
        v[HudView.PING] = s.ping < 0 ? "--" : s.ping + "ms" + (jitterTxt.isEmpty() ? "" : "|" + jitterTxt);
        v[HudView.CPU] = Metrics.pct(s.cpuPct) + " " + Metrics.freq(s.cpuMhz) + " " + Metrics.deg(s.cpuTemp);
        v[HudView.GPU] = Metrics.pct(s.gpuPct) + " " + Metrics.freq(s.gpuMhz) + " " + Metrics.deg(s.gpuTemp);
        v[HudView.RAM] = Metrics.gb(s.ramUsedMb) + "/" + Metrics.gb(s.ramTotalMb) + "G";
        v[HudView.BAT] = String.format(Locale.US, "%.1f\u00b0 %d%%", s.batTemp10 / 10f, s.batPct);
        return v;
    }

    private static final Pattern PING_MS = Pattern.compile("time[=<]([0-9.]+) ?ms");

    /** ICMP lewat /system/bin/ping (tanpa root). -1 kalau diblok / gagal; setelah 3x gagal untuk host ini tidak dicoba lagi. */
    private int icmpPing(String host) {
        if (icmpFail >= 3 || !host.matches("[0-9.]+")) return -1;
        Process p = null;
        try {
            p = new ProcessBuilder("ping", "-c", "1", "-W", "1", host).redirectErrorStream(true).start();
            if (!p.waitFor(2, TimeUnit.SECONDS)) { icmpFail++; return -1; }
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String l;
            while ((l = r.readLine()) != null) {
                Matcher m = PING_MS.matcher(l);
                if (m.find()) { icmpFail = 0; return Math.max(1, Math.round(Float.parseFloat(m.group(1)))); }
            }
        } catch (Exception ignored) {
        } finally {
            if (p != null) p.destroy();
        }
        icmpFail++;
        return -1;
    }

    /** Ping = ICMP ke server game (atau 1.1.1.1); kalau ICMP diblok, waktu TCP connect. Median 5 sampel terakhir. */
    private void pingAsync() {
        if (!pinging.compareAndSet(false, true)) return;
        final String host = pingHost;
        final int port = pingPort;
        try {
            pinger.execute(new Runnable() {
                @Override public void run() {
                    int v = icmpPing(host);
                    if (v < 0) {
                        try (Socket s = new Socket()) {
                            long t0 = SystemClock.elapsedRealtime();
                            s.connect(new InetSocketAddress(host, port), 1500);
                            v = (int) (SystemClock.elapsedRealtime() - t0);
                        } catch (Exception e) {
                            v = -1;
                        }
                    }
                    pingFail = v < 0 ? pingFail + 1 : 0;
                    int prevMed = ping;                                 // median sebelum sampel ini
                    pings[pingN++ % pings.length] = v;
                    int[] ok = new int[pings.length];
                    int n = 0;
                    for (int p : pings) if (p >= 0) ok[n++] = p;
                    if (n == 0) ping = -1;
                    else { Arrays.sort(ok, 0, n); ping = ok[n / 2]; }
                    jitter = n >= 3 ? ok[n - 1] - ok[0] : -1;
                    jitterTxt = jitter >= 1 ? "PING \u00b1" + jitter : "";
                    boolean spike = prevMed > 0 && ((v >= 120 && v > prevMed * 5 / 2) || (v < 0 && pingFail >= 2));
                    if (spike && sessLive) {
                        long now = SystemClock.uptimeMillis();
                        spikeUntil = now + 4000;
                        if (Boost.pingAlertOn(BoostService.this) && now - lastBuzz > 15_000) {
                            lastBuzz = now;
                            buzz();
                        }
                    }
                    pinging.set(false);
                }
            });
        } catch (Exception e) {
            pinging.set(false);
        }
    }

    /** Getar singkat tanda lag koneksi (bukan lag HP). */
    private void buzz() {
        try {
            android.os.Vibrator vb = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vb != null) vb.vibrate(android.os.VibrationEffect.createOneShot(120, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) { }
    }

    // ---- HUD overlay (main thread) ----
    private void showHud() {
        if (hud != null || !Settings.canDrawOverlays(this)) return;
        wm = getSystemService(WindowManager.class);
        hud = new HudView(this);
        WindowManager.LayoutParams lp = HudView.params(this);
        try {
            wm.addView(hud, lp);
        } catch (Exception e) {
            hud = null;
        }
    }

    /** Ikuti posisi yang disimpan user (dan rotasi layar). Main thread. */
    private void updateHudPos() {
        if (hud == null || wm == null) return;
        WindowManager.LayoutParams cur = (WindowManager.LayoutParams) hud.getLayoutParams();
        if (cur == null) return;
        WindowManager.LayoutParams want = HudView.params(this);
        if (cur.x != want.x || cur.y != want.y) {
            cur.x = want.x;
            cur.y = want.y;
            try { wm.updateViewLayout(hud, cur); } catch (Exception ignored) {}
        }
    }

    private void hideHud() {
        if (hud == null) return;
        try { wm.removeView(hud); } catch (Exception ignored) {}
        hud = null;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private Notification note(String title, String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "boost")
            .setSmallIcon(R.drawable.icon_vallen)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(pi).setOngoing(true).setOnlyAlertOnce(true).build();
    }

    @Override public void onDestroy() {
        destroyed = true;
        if (bg != null) bg.removeCallbacks(tick);
        if (ht != null) ht.quitSafely();
        pinger.shutdownNow();
        hideHud();
        lastFps = -1;
        lastPing = -1;
        lastSnap = null;
        running = false;
    }

    @Override public IBinder onBind(Intent i) { return null; }
}

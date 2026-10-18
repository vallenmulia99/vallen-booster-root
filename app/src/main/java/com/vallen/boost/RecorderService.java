package com.vallen.boost;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.StatFs;
import android.provider.Settings;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pengendali perekam. Hidup selama merekam, atau "siaga" kalau bubble / auto-rekam game dinyalakan.
 * Cara meluncurkan: tombol di app, bubble melayang, tile Quick Settings, aksi notifikasi,
 * auto saat game dibuka lewat launcher Vallen Boost, atau intent (Tasker/shortcut) ke action A_TOGGLE.
 */
public class RecorderService extends Service {
    static final String A_ARM = "com.vallen.boost.REC_ARM";
    static final String A_START = "com.vallen.boost.REC_START";
    static final String A_STOP = "com.vallen.boost.REC_STOP";
    static final String A_PAUSE = "com.vallen.boost.REC_PAUSE";
    static final String A_RESUME = "com.vallen.boost.REC_RESUME";
    static final String A_TOGGLE = "com.vallen.boost.REC_TOGGLE";
    static final String A_PROJ = "com.vallen.boost.REC_PROJ";
    /** Broadcast tiap status berubah / tiap detik saat merekam (dibaca halaman Rekam di MainActivity). */
    static final String STATE = "com.vallen.boost.REC_STATE";

    static final int IDLE = 0, PREP = 1, REC = 2, PAUSED = 3, STOPPING = 4;
    static volatile int state = IDLE;
    static volatile boolean canPause;
    static volatile long elapsedMs;
    static volatile String lastError = "";

    private static final int ID = 21, ID_DONE = 22;

    /** Hidupkan / matikan mode siaga sesuai setelan (dipanggil dari app saat setelan berubah). */
    static void syncArm(Context c) {
        try {
            if (RecConfig.bubble(c) || RecConfig.autoGame(c)) {
                c.startForegroundService(new Intent(c, RecorderService.class).setAction(A_ARM));
            } else if (state == IDLE) {
                c.stopService(new Intent(c, RecorderService.class));
            }
        } catch (Exception ignored) {}
    }

    static void send(Context c, String action) {
        try {
            c.startForegroundService(new Intent(c, RecorderService.class).setAction(action));
        } catch (Exception ignored) {}
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private NotificationManager nm;
    private RecBubble bubble;
    private volatile RecEngine engine;
    private long segStart, accMs;
    private int tickN;
    private boolean sessSeen, autoRec, offRegistered;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (state != REC && state != PAUSED) return;
            elapsedMs = accMs + (state == REC ? SystemClock.elapsedRealtime() - segStart : 0);
            bubble.time(fmt(elapsedMs));
            if (++tickN % 5 == 0 && guards()) return;
            sendBroadcast(new Intent(STATE).setPackage(getPackageName()));
            main.postDelayed(this, 1000);
        }
    };

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            autoPoll();
            main.postDelayed(this, 2000);
        }
    };

    private final Runnable autoStart = new Runnable() {
        @Override public void run() {
            if (!Boost.session(RecorderService.this).isEmpty() && state == IDLE) {
                autoRec = true;
                requestStart();
            }
        }
    };

    private final BroadcastReceiver offRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (RecConfig.stopScreenOff(c)) requestStop("Layar dimatikan");
        }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("rec", "Perekam layar", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel("rec_done", "Hasil rekaman", NotificationManager.IMPORTANCE_DEFAULT));
        bubble = new RecBubble(this, new RecBubble.Cb() {
            @Override public void onToggle() {
                if (state == REC || state == PAUSED) requestStop(null); else requestStart();
            }
            @Override public void onPause() { if (state == REC) pause(); else if (state == PAUSED) resume(); }
            @Override public void onClose() {
                RecConfig.setb(RecorderService.this, "bubble", false);
                bubble.hide();
                if (state == IDLE && !RecConfig.autoGame(RecorderService.this)) { stopForeground(true); stopSelf(); }
            }
        });
        work.execute(new Runnable() {            // sisa show_touches dari proses yang dibunuh pas merekam
            @Override public void run() { restoreTouches(); }
        });
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        String a = i == null || i.getAction() == null ? A_ARM : i.getAction();
        startFg(false, false);      // wajib dipanggil cepat setelah startForegroundService
        switch (a) {
            case A_START: requestStart(); break;
            case A_TOGGLE:
                if (state == REC || state == PAUSED) requestStop(null); else requestStart();
                break;
            case A_STOP: requestStop(null); idleCleanup(); break;
            case A_PAUSE: pause(); idleCleanup(); break;
            case A_RESUME: resume(); idleCleanup(); break;
            case A_PROJ:
                begin(i.getIntExtra("code", 0), (Intent) i.getParcelableExtra("data"));
                break;
            default: armed(); break;
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        bubble.hide();
        if (offRegistered) { try { unregisterReceiver(offRx); } catch (Exception ignored) {} offRegistered = false; }
        final RecEngine e = engine;
        engine = null;
        state = IDLE;
        canPause = false;
        new Thread(new Runnable() {
            @Override public void run() {
                if (e != null) e.stop();
                restoreTouches();
            }
        }).start();
        work.shutdown();
        super.onDestroy();
    }

    /** Aksi nyasar (mis. notifikasi lama) pas tidak merekam dan tidak siaga: matikan service. */
    private void idleCleanup() {
        if (state == IDLE && !needArm()) { stopForeground(true); stopSelf(); }
    }

    private final Runnable idleCheck = new Runnable() {
        @Override public void run() { idleCleanup(); }
    };

    // ---------- siaga + auto-rekam ----------
    private boolean needArm() { return RecConfig.bubble(this) || RecConfig.autoGame(this); }

    private boolean canOverlay() { return Settings.canDrawOverlays(this); }

    private void armed() {
        if (!needArm() && state == IDLE) { stopForeground(true); stopSelf(); return; }
        if (RecConfig.bubble(this) && canOverlay()) { bubble.show(); bubble.setState(state); } else bubble.hide();
        main.removeCallbacks(poll);
        main.post(poll);
    }

    /** Game dibuka lewat launcher app (Boost.session terisi) -> rekam; game ditutup -> stop (kalau rekamannya auto). */
    private void autoPoll() {
        if (!RecConfig.autoGame(this)) { sessSeen = false; return; }
        boolean sess = !Boost.session(this).isEmpty();
        if (sess && !sessSeen) {
            sessSeen = true;
            main.removeCallbacks(autoStart);
            main.postDelayed(autoStart, RecConfig.autoDelay(this) * 1000L);
        } else if (!sess && sessSeen) {
            sessSeen = false;
            main.removeCallbacks(autoStart);
            if (autoRec && (state == REC || state == PAUSED)) requestStop("Game ditutup");
            autoRec = false;
        }
    }

    // ---------- alur rekam ----------
    private void requestStart() {
        if (state != IDLE) return;
        lastError = "";
        work.execute(new Runnable() {
            @Override public void run() {
                // root (screenrecord) tidak bisa merekam suara: kalau user minta audio, selalu pakai MediaProjection
                final int e = RecConfig.audio(RecorderService.this) != 0 ? 2 : RecConfig.engine(RecorderService.this);
                boolean root = false;
                if (e != 2) {
                    root = Boost.root || Boost.isRoot();
                    RecConfig.setRootKnown(RecorderService.this, root);
                    // mesin root (screenrecord) tidak bisa merekam suara: mode Otomatis + user minta audio -> pakai MediaProjection
                    if (e == 0 && RecConfig.audio(RecorderService.this) != 0) root = false;
                }
                final boolean useRoot = root;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (state != IDLE) return;
                        if (useRoot) begin(0, null);
                        else if (e == 1) finish(null, "Mode Root dipilih tapi akses root tidak ada");
                        else launchProjection();
                    }
                });
            }
        });
    }

    private void launchProjection() {
        try {
            startActivity(new Intent(this, RecProjectionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            if (!canOverlay()) {
                android.widget.Toast.makeText(this,
                    "Kalau dialog izin rekam tidak muncul, aktifkan izin 'Tampil di atas app lain' untuk Vallen Boost",
                    android.widget.Toast.LENGTH_LONG).show();
            }
            main.removeCallbacks(idleCheck);
            main.postDelayed(idleCheck, 90_000);      // dialog izin dibatalkan / tidak dijawab
        } catch (Exception e) {
            finish(null, "Tidak bisa membuka izin rekam layar");
        }
    }

    /** code/data = hasil izin MediaProjection; data == null berarti mesin root. */
    private void begin(final int code, final Intent data) {
        if (state != IDLE) return;
        final boolean proj = data != null;
        state = PREP;
        canPause = false;
        final boolean wantMic = proj && RecConfig.audio(this) != 0
            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        final boolean mic = startFg(proj, wantMic);
        if (proj && RecConfig.audio(this) != 0 && !mic) {
            android.widget.Toast.makeText(this,
                "Suara tidak aktif: izin mikrofon belum diberikan / sistem menolak",
                android.widget.Toast.LENGTH_LONG).show();
        }
        sendBroadcast(new Intent(STATE).setPackage(getPackageName()));
        if (RecConfig.bubble(this) && canOverlay()) { bubble.show(); }
        bubble.setState(PREP);
        countdown(RecConfig.countdown(this), new Runnable() {
            @Override public void run() {
                work.execute(new Runnable() {
                    @Override public void run() { startEngine(code, data, mic); }
                });
            }
        });
    }

    private void countdown(final int n, final Runnable done) {
        if (state != PREP) { bubble.countdown(0); return; }          // dibatalkan
        if (n <= 0) { bubble.countdown(0); done.run(); return; }
        bubble.countdown(n);
        main.postDelayed(new Runnable() {
            @Override public void run() { countdown(n - 1, done); }
        }, 1000);
    }

    private void startEngine(int code, Intent data, boolean mic) {
        String err = null;
        RecEngine e = null;
        try {
            long free = freeMb();
            if (free < RecConfig.minFreeMb(this)) {
                throw new IllegalStateException("Ruang penyimpanan tinggal " + free + " MB");
            }
            Runnable stopped = new Runnable() {
                @Override public void run() { main.post(new Runnable() {
                    @Override public void run() { requestStop("Rekaman berhenti"); }
                }); }
            };
            if (data == null) e = new RecRoot(this, stopped);
            else if (mic && RecConfig.internalAudio(this)) e = new RecCodec(this, code, data, RecConfig.audio(this) == 3, stopped);
            else e = new RecProj(this, code, data, mic, stopped);
            e.start();
            engine = e;
        } catch (Exception ex) {
            String m = ex.getMessage();
            err = m == null || m.isEmpty() ? ex.getClass().getSimpleName() : m;
        }
        final String fe = err;
        final RecEngine fEng = e;
        main.post(new Runnable() {
            @Override public void run() {
                if (fe != null) { finish(null, fe); return; }
                if (state != PREP) {                                  // dibatalkan pas mesin masih menyala
                    engine = null;
                    work.execute(new Runnable() { @Override public void run() { fEng.stop(); } });
                    return;
                }
                android.widget.Toast.makeText(RecorderService.this, "Mesin: "
                    + (fEng instanceof RecCodec ? "Codec (audio internal)" : fEng instanceof RecProj ? "Standar" : "Root (tanpa suara)")
                    + " | sumber suara=" + RecConfig.AUDIO_NAMES[RecConfig.audio(RecorderService.this)],
                    android.widget.Toast.LENGTH_LONG).show();
                onStarted();
            }
        });
    }

    private void onStarted() {
        state = REC;
        canPause = engine != null && engine.canPause();
        accMs = 0;
        elapsedMs = 0;
        segStart = SystemClock.elapsedRealtime();
        tickN = 0;
        if (!offRegistered) {
            registerReceiver(offRx, new IntentFilter(Intent.ACTION_SCREEN_OFF));
            offRegistered = true;
        }
        if (RecConfig.showTouches(this) && Boost.root) {
            work.execute(new Runnable() {
                @Override public void run() {
                    String old = RootShell.run("settings get system show_touches");
                    if (old != null && (old.equals("0") || old.equals("1"))) {
                        RecConfig.setTouchesOld(RecorderService.this, old);
                        RootShell.run("settings put system show_touches 1");
                    }
                }
            });
        }
        nm.notify(ID, note());
        bubble.setState(REC);
        sendBroadcast(new Intent(STATE).setPackage(getPackageName()));
        main.removeCallbacks(tick);
        main.postDelayed(tick, 1000);
    }

    private void pause() {
        RecEngine e = engine;
        if (state != REC || e == null || !e.canPause()) return;
        try { e.pause(); } catch (Exception ex) { return; }
        accMs += SystemClock.elapsedRealtime() - segStart;
        state = PAUSED;
        nm.notify(ID, note());
        bubble.setState(PAUSED);
        sendBroadcast(new Intent(STATE).setPackage(getPackageName()));
    }

    private void resume() {
        RecEngine e = engine;
        if (state != PAUSED || e == null) return;
        try { e.resume(); } catch (Exception ex) { return; }
        segStart = SystemClock.elapsedRealtime();
        state = REC;
        nm.notify(ID, note());
        bubble.setState(REC);
        sendBroadcast(new Intent(STATE).setPackage(getPackageName()));
        main.removeCallbacks(tick);
        main.post(tick);
    }

    private void requestStop(final String why) {
        if (state == PREP) {                         // batal sebelum mulai (hitung mundur / dialog izin)
            bubble.countdown(0);
            finish(null, why == null ? "Dibatalkan" : why);
            return;
        }
        if (state != REC && state != PAUSED) return;
        state = STOPPING;
        main.removeCallbacks(tick);
        bubble.setState(STOPPING);
        sendBroadcast(new Intent(STATE).setPackage(getPackageName()));
        final RecEngine e = engine;
        engine = null;
        work.execute(new Runnable() {
            @Override public void run() {
                Uri u = null;
                try {
                    if (e != null) { e.stop(); u = e.result(); }
                } catch (Exception ignored) {}
                restoreTouches();
                final Uri fu = u;
                final String fw = (u == null && why == null)
                    ? "Rekaman gagal disimpan" + (RecCodec.fail.isEmpty() ? "" : " (" + RecCodec.fail + ")") : why;
                main.post(new Runnable() { @Override public void run() { finish(fu, fw); } });
            }
        });
    }

    /** Selesai (sukses kalau u != null). Tampilkan hasil, kembali ke siaga atau matikan service. */
    private void finish(Uri u, String why) {
        main.removeCallbacks(tick);
        state = IDLE;
        canPause = false;
        autoRec = false;
        if (offRegistered) { try { unregisterReceiver(offRx); } catch (Exception ignored) {} offRegistered = false; }
        bubble.countdown(0);
        if (u != null) RecConfig.setLast(this, u.toString());
        if (u == null && why != null) lastError = why;
        postDone(u, why);
        sendBroadcast(new Intent(STATE).setPackage(getPackageName()));
        if (needArm()) {
            startFg(false, false);
            nm.notify(ID, note());
            armed();
        } else {
            bubble.hide();
            stopForeground(true);
            stopSelf();
        }
    }

    private void postDone(Uri u, String why) {
        Notification.Builder b = new Notification.Builder(this, "rec_done")
            .setSmallIcon(R.drawable.icon_vallen).setAutoCancel(true).setTimeoutAfter(20000);
        if (u != null) {
            b.setContentTitle("Rekaman disimpan").setContentText(why == null ? "Ketuk untuk memutar" : why + " \u2022 ketuk untuk memutar");
            Intent v = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            b.setContentIntent(PendingIntent.getActivity(this, 5, v, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else if (why != null && !"Dibatalkan".equals(why)) {
            b.setContentTitle("Rekaman gagal").setContentText(why);
        } else {
            return;
        }
        nm.notify(ID_DONE, b.build());
    }

    // ---------- pengaman saat merekam (dicek tiap 5 detik) ----------
    /** @return true kalau rekaman dihentikan. */
    private boolean guards() {
        int max = RecConfig.maxMin(this);
        if (max > 0 && elapsedMs >= max * 60_000L) { requestStop("Batas durasi tercapai"); return true; }
        if (freeMb() < RecConfig.minFreeMb(this)) { requestStop("Ruang penyimpanan hampir penuh"); return true; }
        if (RecConfig.thermalStop(this)) {
            int t = Boost.temp(this) / 10;
            if (t >= RecConfig.tempStop(this)) { requestStop("Dihentikan: suhu baterai " + t + "\u00B0C"); return true; }
        }
        return false;
    }

    private long freeMb() {
        try {
            return new StatFs(Environment.getExternalStorageDirectory().getPath()).getAvailableBytes() / 1048576L;
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    private void restoreTouches() {
        String old = RecConfig.touchesOld(this);
        if (old.isEmpty()) return;
        RecConfig.setTouchesOld(this, "");
        if (Boost.root || Boost.isRoot()) RootShell.run("settings put system show_touches " + old);
    }

    // ---------- notifikasi ----------
    /**
     * @return true kalau tipe mikrofon ikut dipasang. Android 11+ membisukan mic untuk service tanpa tipe ini
     *         saat app di background (misalnya lagi di dalam game); kalau sistem menolak, jatuh ke tanpa mic.
     */
    private boolean startFg(boolean proj, boolean mic) {
        Notification n = note();
        try {
            if (Build.VERSION.SDK_INT >= 29 && proj) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
                if (mic && Build.VERSION.SDK_INT >= 30) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                try {
                    startForeground(ID, n, type);
                    return mic;
                } catch (RuntimeException e) {
                    startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                    return false;
                }
            }
            startForeground(ID, n);
        } catch (RuntimeException e) {
            stopSelf();
        }
        return false;
    }

    private Notification note() {
        Notification.Builder b = new Notification.Builder(this, "rec")
            .setSmallIcon(R.drawable.icon_vallen).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).putExtra("page", 2)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        int s = state;
        if (s == REC || s == PAUSED) {
            b.setContentTitle(s == REC ? "Merekam layar" : "Rekaman dijeda");
            b.setContentText(s == REC ? "Ketuk Stop untuk menyimpan" : "Ketuk Lanjut untuk meneruskan");
            if (s == REC) b.setUsesChronometer(true).setWhen(System.currentTimeMillis() - elapsedMs);
            else b.setShowWhen(false);
            if (canPause) b.addAction(act(s == REC ? "Jeda" : "Lanjut", s == REC ? A_PAUSE : A_RESUME, 1));
            b.addAction(act("Stop", A_STOP, 2));
        } else if (s == PREP || s == STOPPING) {
            b.setContentTitle(s == PREP ? "Menyiapkan rekaman..." : "Menyimpan rekaman...");
            b.setShowWhen(false);
        } else {
            b.setContentTitle("Perekam siap");
            b.setContentText(RecConfig.autoGame(this) ? "Otomatis merekam saat game dibuka" : "Geser turun dari tepi atas layar untuk panel rekam");
            b.setShowWhen(false);
            b.addAction(act("Rekam", A_START, 3));
        }
        return b.build();
    }

    private Notification.Action act(String title, String action, int rc) {
        PendingIntent pi = PendingIntent.getForegroundService(this, rc,
            new Intent(this, RecorderService.class).setAction(action),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_tile), title, pi).build();
    }

    static String fmt(long ms) {
        long s = ms / 1000;
        return s >= 3600
            ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
            : String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }
}

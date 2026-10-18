package com.vallen.boost;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int BG = 0xFF07080C, CARD = 0xFF11131A, LINE = 0xFF232838,
        FG = 0xFFFFFFFF, DIM = 0xFF8A93A6, GREEN = 0xFF00FF88, CYAN = 0xFF00D4FF,
        RED = 0xFFFF3B5C, AMBER = 0xFFFFB020,
        GOLD = 0xFFFFC83D, GLASS = 0xB3101319;
    /** Warna aksen mengikuti tema pilihan user (lihat applyTheme). */
    private static int ACCENT = 0xFFE8555C, ACC1 = 0xFFC02A33, ACC2 = 0xFF7A1820, ACC_BG = 0xCC1A0E10, ACC_LINE = 0xFF5A2A2E;

    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    static void applyTheme(Context c) {
        int t = Boost.theme(c);
        ACCENT = Boost.THEME_ACCENT[t];
        if (t == 0) {                                       // merah bawaan: persis seperti sebelumnya
            ACC1 = 0xFFC02A33; ACC2 = 0xFF7A1820; ACC_BG = 0xCC1A0E10; ACC_LINE = 0xFF5A2A2E;
            return;
        }
        ACC1 = mix(ACCENT, 0xFF000000, 0.22f);
        ACC2 = mix(ACCENT, 0xFF000000, 0.58f);
        ACC_BG = (mix(ACCENT, 0xFF07080C, 0.9f) & 0x00FFFFFF) | 0xCC000000;
        ACC_LINE = mix(ACCENT, 0xFF07080C, 0.62f);
    }
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT,
        WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    /** nama, package, tag, warna aksen */
    private static final String[][] GAMES = {
        {"Mobile Legends", "com.mobile.legends", "MOBA 5V5", "#FFC83D"},
        {"Free Fire", "com.dts.freefireth", "BATTLE ROYALE", "#FF7A1A"},
        {"Free Fire MAX", "com.dts.freefiremax", "BR  \u2022  HD", "#FF3D5A"},
    };

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuf = new StringBuilder();
    private Metrics mx;

    private TextView rootChip, sysView, logView, limitView, chipView, reportView;
    private TextView vFps, sFps, vPing, sPing, vBat, sBat, vCpu, sCpu, vGpu, sGpu, vRam, sRam;
    private Switch gameSw, lockSw, hudSw, killSw, dndSw, rrSw, autoSw, ecoSw, pingSw, gpuMaxSw, marginSw, capSw;
    private Switch adaptSw, wifiSw, gmodeSw, smartSw, abSw, pingAlertSw, preCheckSw;
    private boolean checking;
    private TextView tgtVal, themeVal, hudThemeVal, hudSizeVal;
    private TextView resVal;
    private GraphView graphView;
    private FrameLayout stage;
    private ImageView bgView;
    private View settingsPage, lobbyView;
    private int page;                 // 0 = Home, 1 = Setting, 2 = Rekam
    private RecPage rec;
    private View recView;
    private LinearLayout gameList;
    private TextView heroName, heroTag, startBtn;
    private TextView[] quickChips;
    private LinearLayout navHome, navTune, navBg, navRec;
    private VideoBg bgVideo;
    private boolean resumed;
    private LoadingOverlay loading;
    private LaunchOverlay launching;
    private final List<String[]> games = new ArrayList<>();   // {nama, package, tag, warna, custom}
    private String selPkg;
    private long startedAt;
    private static final int REQ_BG = 21, REQ_VID = 22;
    private boolean busy;
    private volatile boolean root;

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String m = i.getStringExtra("msg");
            log(m != null ? m : "Suhu lewat batas, boost dimatikan otomatis");
            render();
        }
    };
    private final Runnable loop = new Runnable() {
        @Override public void run() { render(); ui.postDelayed(this, 2000); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        applyTheme(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 28) {      // pakai area poni/cutout di mode landscape
            WindowManager.LayoutParams wa = getWindow().getAttributes();
            wa.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(wa);
        }
        immersive();
        mx = new Metrics(this);
        final boolean orphan = Boost.orphaned(this);   // dicek sebelum service sempat hidup lagi

        LinearLayout col = box(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(20), dp(16), dp(84));   // ruang buat navigasi bawah

        // chip root dipasang di top bar lobby
        rootChip = text("ROOT ...", 10, DIM);
        rootChip.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        rootChip.setPadding(dp(10), dp(5), dp(10), dp(5));
        rootChip.setBackground(shape(0xFF11131A, LINE, 20));

        // ---------- stats live ----------
        LinearLayout stats = box(LinearLayout.VERTICAL);
        stats.setPadding(dp(6), dp(4), dp(6), dp(4));
        stats.setBackground(grad(0xCC151925, 0xB30C0E14, 0x33FFFFFF, 20));
        vFps = big(); sFps = tiny("frame/s");
        vPing = big(); sPing = tiny("ms");
        vBat = big(); sBat = tiny("baterai");
        vCpu = big(); sCpu = tiny("-");
        vGpu = big(); sGpu = tiny("-");
        vRam = big(); sRam = tiny("-");
        LinearLayout r1 = box(LinearLayout.HORIZONTAL), r2 = box(LinearLayout.HORIZONTAL);
        r1.addView(tile("FPS", vFps, sFps), lp(0, WRAP, 1, 0, 0, 0, 0));
        r1.addView(tile("PING", vPing, sPing), lp(0, WRAP, 1, 0, 0, 0, 0));
        r1.addView(tile("SUHU", vBat, sBat), lp(0, WRAP, 1, 0, 0, 0, 0));
        r2.addView(tile("CPU", vCpu, sCpu), lp(0, WRAP, 1, 0, 0, 0, 0));
        r2.addView(tile("GPU", vGpu, sGpu), lp(0, WRAP, 1, 0, 0, 0, 0));
        r2.addView(tile("RAM", vRam, sRam), lp(0, WRAP, 1, 0, 0, 0, 0));
        stats.addView(r1);
        stats.addView(r2);
        col.addView(stats, lp(MATCH, WRAP, 0, 0, 4, 0, 0));
        sysView = text("", 10, DIM);
        sysView.setTypeface(Typeface.MONOSPACE);
        sysView.setGravity(Gravity.CENTER);
        sysView.setPadding(0, dp(8), 0, 0);
        col.addView(sysView, new LinearLayout.LayoutParams(MATCH, WRAP));

        // ---------- tuning ----------
        // ---------- setelan: komponen (dirakit jadi kartu per kategori di bawah) ----------
        gameSw = sw(); lockSw = sw(); hudSw = sw(); killSw = sw(); dndSw = sw(); rrSw = sw();
        autoSw = sw(); ecoSw = sw(); pingSw = sw(); gpuMaxSw = sw(); marginSw = sw(); capSw = sw();
        adaptSw = sw(); wifiSw = sw(); gmodeSw = sw(); smartSw = sw(); abSw = sw(); pingAlertSw = sw(); preCheckSw = sw();
        tgtVal = linkVal(); resVal = linkVal(); themeVal = linkVal(); hudThemeVal = linkVal(); hudSizeVal = linkVal();
        showRes();

        gameSw.setOnCheckedChangeListener((v, on) -> {
            if (!busy) apply(on ? "Game Mode ON" : "Game Mode OFF", () -> Boost.setGame(this, on));
        });
        lockSw.setOnCheckedChangeListener((v, on) -> {
            if (!busy) apply(on ? "Lock Clock ON" : "Lock Clock OFF", () -> Boost.setLock(this, on));
        });
        capSw.setOnCheckedChangeListener((v, on) -> {
            if (!busy) apply(on ? "CPU Cap ON" : "CPU Cap OFF", () -> Boost.setCap(this, on));
        });
        gpuMaxSw.setOnCheckedChangeListener((v, on) -> {
            if (!busy) apply(on ? "GPU Max ON" : "GPU Max OFF", () -> Boost.setGpuMax(this, on));
        });
        marginSw.setOnCheckedChangeListener((v, on) -> {
            if (!busy) apply(on ? "GPU Responsif ON" : "GPU Responsif OFF", () -> Boost.setMargin(this, on));
        });
        hudSw.setOnCheckedChangeListener((v, on) -> {
            if (busy) return;
            if (on && !Settings.canDrawOverlays(this)) {
                busy = true; hudSw.setChecked(false); busy = false;
                askOverlay();
                return;
            }
            apply(on ? "HUD ON" : "HUD OFF", () -> Boost.setHud(this, on));
        });
        killSw.setOnCheckedChangeListener((v, on) -> { if (!busy) Boost.setKill(this, on); });
        rrSw.setOnCheckedChangeListener((v, on) -> { if (!busy) Boost.setRr(this, on); });
        dndSw.setOnCheckedChangeListener((v, on) -> { if (!busy) Boost.setDnd(this, on); });
        ecoSw.setOnCheckedChangeListener((v, on) -> { if (!busy) Boost.setEco(this, on); });
        pingSw.setOnCheckedChangeListener((v, on) -> { if (!busy) Boost.setPingGame(this, on); });
        adaptSw.setOnCheckedChangeListener((v, on) -> { if (!busy) Boost.setAdapt(this, on); });
        pingAlertSw.setOnCheckedChangeListener((v, on) -> { if (!busy) Boost.setPingAlert(this, on); });
        preCheckSw.setOnCheckedChangeListener((v, on) -> { if (!busy) Boost.setPreCheck(this, on); });
        wifiSw.setOnCheckedChangeListener((v, on) -> { if (!busy) Boost.setWifiLl(this, on); });
        gmodeSw.setOnCheckedChangeListener((v, on) -> { if (!busy) Boost.setGmode(this, on); });
        smartSw.setOnCheckedChangeListener((v, on) -> { if (!busy) Boost.setSmartKill(this, on); });
        abSw.setOnCheckedChangeListener((v, on) -> {
            if (busy) return;
            Boost.setAbNext(this, on);
            if (on) toast("Game berikutnya dibuka TANPA boost (sekali saja)");
        });
        autoSw.setOnCheckedChangeListener((v, on) -> {
            if (busy) return;
            if (on && !root) {
                busy = true; autoSw.setChecked(false); busy = false;
                toast("Auto Boost butuh root");
                return;
            }
            if (on && Boost.hudOn(this) && !Settings.canDrawOverlays(this)) askOverlay();
            apply(on ? "Auto Boost ON, nunggu game dibuka" : "Auto Boost OFF", () -> Boost.setAuto(this, on));
        });

        // ---------- batas suhu ----------
        LinearLayout limits = box(LinearLayout.VERTICAL);
        limits.setPadding(dp(14), dp(8), dp(14), dp(12));
        limits.setBackground(shape(GLASS, 0x22FFFFFF, 20));
        limitView = text("", 13, FG);
        limitView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        limitView.setPadding(dp(4), dp(6), 0, dp(2));
        limits.addView(limitView);
        SeekBar bar = new SeekBar(this);
        bar.setMax(15);                                   // 40..55 C
        bar.setProgress(Boost.limit(this) - 40);
        bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        bar.setThumbTintList(ColorStateList.valueOf(ACCENT));
        showLimit();
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                Boost.setLimit(MainActivity.this, 40 + p);
                showLimit();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        limits.addView(bar);

        // ---------- batas suhu chip ----------
        chipView = text("", 13, FG);
        chipView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        chipView.setPadding(dp(4), dp(14), 0, dp(2));
        limits.addView(chipView);
        SeekBar chipBar = new SeekBar(this);
        chipBar.setMax(30);                               // 65..95 C
        chipBar.setProgress(Boost.chipLimit(this) - 65);
        chipBar.setProgressTintList(ColorStateList.valueOf(GOLD));
        chipBar.setThumbTintList(ColorStateList.valueOf(GOLD));
        showChip();
        chipBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                Boost.setChipLimit(MainActivity.this, 65 + p);
                showChip();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        limits.addView(chipBar);

        // ---------- rakit halaman setelan: dua kolom kartu per kategori ----------
        LinearLayout colL = box(LinearLayout.VERTICAL), colR = box(LinearLayout.VERTICAL);
        section(colL, "MODE BOOST");
        colL.addView(card(
            row("Game Mode", "CPU + GPU performance, boost top-app, proteksi game dari kill", gameSw),
            row("Mode Adaptif FPS", "Tuning CPU naik-turun ngikutin FPS dan suhu. Lebih stabil dan dingin. OFF = clock max terus", adaptSw),
            linkRow("Target FPS", "Patokan Mode Adaptif", tgtVal, v -> pickTarget()),
            row("GPU Max", "Kunci GPU di clock tertinggi tanpa kunci CPU. Turun satu tahap kalau panas", gpuMaxSw),
            row("CPU Cap", "Batasi CPU ~75% biar dingin dan GPU tidak ikut throttle. OFF kalau game CPU-bound", capSw),
            row("Lock Clock", "CPU & GPU min = max (panas!)", lockSw),
            row("GPU Responsif (eksperimen)", "Naikin margin DVFS GED supaya GPU cepat naik clock", marginSw)));
        section(colL, "OTOMATIS & SISTEM");
        colL.addView(card(
            row("Auto Boost", "Deteksi game otomatis walau dibuka dari home screen (butuh root)", autoSw),
            row("Hemat Panas Saat Ngecas", "Batas suhu turun 3\u00b0C (baterai) dan 5\u00b0C (chip) pas dicas", ecoSw),
            row("Bunuh App Background", "Matikan app lain setelah game dibuka", killSw),
            row("Bunuh Background Pintar", "am kill, cuma app berat (RAM >= 50 MB). Alarm dan notif aman", smartSw),
            linkRow("Kecualikan App dari Kill", "App yang tidak pernah dibunuh", new TextView(this), v -> pickKeep()),
            row("Mode Jangan Ganggu", "Notif diblok selama main, balik otomatis", dndSw),
            row("Kunci Refresh Rate", "Paksa layar ke Hz tertinggi selama main", rrSw),
            row("Wi-Fi Latensi Rendah", "Mode low-latency selama Game Mode (Android 11+)", wifiSw),
            row("Game Mode Android", "Mode Performance via GameManager (Android 12+)", gmodeSw)));

        section(colR, "HUD");
        colR.addView(card(
            row("HUD Overlay", "FPS, ping, CPU, GPU, RAM di atas game, hilang otomatis pas keluar", hudSw),
            row("Ping ke Server Game", "Ukur ke server game yang terdeteksi, kalau tidak ketemu pakai 1.1.1.1", pingSw),
            row("Alert Lag Koneksi", "Ping spike atau paket hilang: HUD tulis LAG KONEKSI + getar singkat, jadi tahu itu jaringan, bukan HP", pingAlertSw),
            row("Cek Koneksi Sebelum START", "Tes ping singkat sebelum game dibuka, kalau jelek ditanya dulu", preCheckSw),
            linkRow("Posisi & Ukuran HUD", "Geser dan kecilin / gedein HUD dengan pratinjau", hudSizeVal, v -> editHudPos()),
            linkRow("Tema HUD", "Warna panel dan aksen HUD", hudThemeVal, v -> pickHudTheme())));
        section(colR, "TAMPILAN");
        colR.addView(card(
            linkRow("Tema Aplikasi", "Warna aksen seluruh aplikasi", themeVal, v -> pickTheme()),
            linkRow("Resolusi Game", "Diturunin biar ngacir (lewat launcher app ini)", resVal, v -> pickRes())));
        section(colR, "BATAS SUHU");
        colR.addView(limits);
        section(colR, "UJI COBA");
        colR.addView(card(
            row("Sesi Berikutnya Tanpa Boost", "Buat pembanding A/B di Riwayat, otomatis mati setelah satu sesi", abSw)));

        LinearLayout cols = box(LinearLayout.HORIZONTAL);
        cols.setBaselineAligned(false);
        cols.addView(colL, lp(0, WRAP, 1, 0, 0, 6, 0));
        cols.addView(colR, lp(0, WRAP, 1, 6, 0, 0, 0));
        col.addView(cols, new LinearLayout.LayoutParams(MATCH, WRAP));

        // ---------- alat ----------
        section(col, "ALAT");
        col.addView(pair(primaryButton("BERSIHKAN RAM", v -> cleanRam()), button("RESET SEMUA", RED, v -> resetAll())),
            new LinearLayout.LayoutParams(MATCH, WRAP));
        col.addView(pair(button("PRESET", GOLD, v -> pickPreset()), button("RIWAYAT & A/B", GOLD, v -> showHistory())),
            lp(MATCH, WRAP, 0, 0, 10, 0, 0));
        col.addView(pair(button("OPTIMASI GAME", GOLD, v -> optimizeGame()), button("DIAGNOSA SENSOR", GOLD, v -> diagnose())),
            lp(MATCH, WRAP, 0, 0, 10, 0, 0));
        col.addView(pair(button("SALIN BACKUP", GOLD, v -> backupCopy()), button("PULIHKAN BACKUP", GOLD, v -> backupRestore())),
            lp(MATCH, WRAP, 0, 0, 10, 0, 0));
        showTarget();
        showTheme();
        showHudTheme();
        showHudSize();

        // ---------- laporan sesi ----------
        section(col, "SESI TERAKHIR");
        reportView = text("Belum ada sesi. Main dari launcher, laporan muncul pas game ditutup.", 12, DIM);
        reportView.setTypeface(Typeface.MONOSPACE);
        reportView.setPadding(dp(14), dp(10), dp(14), dp(10));
        reportView.setBackground(shape(GLASS, 0x22FFFFFF, 16));
        col.addView(reportView, new LinearLayout.LayoutParams(MATCH, WRAP));
        graphView = new GraphView(this);
        graphView.setBackground(shape(GLASS, 0x22FFFFFF, 16));
        col.addView(graphView, lp(MATCH, WRAP, 0, 0, 8, 0, 0));

        // ---------- log ----------
        section(col, "LOG");
        logView = text("", 11, DIM);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(dp(14), dp(10), dp(14), dp(10));
        logView.setBackground(shape(GLASS, 0x22FFFFFF, 16));
        logView.setMinHeight(dp(60));
        col.addView(logView, new LinearLayout.LayoutParams(MATCH, WRAP));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.addView(col);
        settingsPage = buildSettingsPage(sv);
        settingsPage.setVisibility(View.GONE);

        stage = new FrameLayout(this);
        stage.setBackgroundColor(BG);
        bgView = new ImageView(this);
        bgView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        stage.addView(bgView, new FrameLayout.LayoutParams(MATCH, MATCH));
        bgVideo = new VideoBg(this);
        stage.addView(bgVideo, new FrameLayout.LayoutParams(MATCH, MATCH));
        View lobby = buildLobby();
        lobbyView = lobby;
        stage.addView(lobby, new FrameLayout.LayoutParams(MATCH, MATCH));
        stage.addView(settingsPage, new FrameLayout.LayoutParams(MATCH, MATCH));
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        np.bottomMargin = dp(10);
        View navView = buildNav();
        stage.addView(navView, np);
        navView.setOnApplyWindowInsetsListener((view, ins) -> {     // tengahin nav di area aman (bukan di poni)
            int l = 0, r = 0;
            if (Build.VERSION.SDK_INT >= 28 && ins.getDisplayCutout() != null) {
                l = ins.getDisplayCutout().getSafeInsetLeft();
                r = ins.getDisplayCutout().getSafeInsetRight();
            }
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) view.getLayoutParams();
            if (p.leftMargin != l || p.rightMargin != r) {
                p.leftMargin = l;
                p.rightMargin = r;
                view.setLayoutParams(p);
            }
            return ins;
        });
        loading = new LoadingOverlay(this);
        stage.addView(loading, new FrameLayout.LayoutParams(MATCH, MATCH));
        launching = new LaunchOverlay(this);
        stage.addView(launching, new FrameLayout.LayoutParams(MATCH, MATCH));
        applyBg();
        applyInsets(lobby);
        applyInsets(settingsPage);
        setContentView(stage);
        if (getIntent() != null && getIntent().getIntExtra("page", 0) == 2) stage.post(() -> showPage(2));
        startedAt = SystemClock.uptimeMillis();
        ui.postDelayed(() -> loading.finish(startedAt), 6000);   // jaga-jaga kalau pengecekan root macet
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
        }

        registerReceiver(rx, new IntentFilter(BoostService.UPDATE));
        io.execute(() -> {
            boolean ok = Boost.isRoot();
            root = ok;
            if (orphan) {
                Boost.restoreAll(this);
                log("Sisa boost lama (reboot / service mati) dibersihkan");
            }
            ui.post(() -> {
                if (ok) {
                    rootChip.setText("ROOT \u25cf");
                    rootChip.setTextColor(GREEN);
                    rootChip.setBackground(shape(0x2200FF88, GREEN, 20));
                    render();
                } else {
                    rootChip.setText("NO ROOT");
                    rootChip.setTextColor(RED);
                    rootChip.setBackground(shape(0x22FF3B5C, RED, 20));
                    gameSw.setEnabled(false);
                    lockSw.setEnabled(false);
                    log("Root ditolak. Kasih izin su ke Vallen Boost di Magisk, lalu buka ulang.");
                }
                loading.finish(startedAt);
            });
        });
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (i.getIntExtra("page", 0) == 2) showPage(2);      // dari notifikasi perekam
    }

    @Override public void onBackPressed() {
        if (page != 0) showPage(0);
        else super.onBackPressed();
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (rec != null) rec.onPermResult(req, r);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if ((req == REQ_BG || req == REQ_VID) && res == RESULT_OK && data != null && data.getData() != null) {
            final Uri u = data.getData();
            final boolean video = req == REQ_VID;
            if (video) toast("Menyiapkan video...");
            io.execute(() -> {
                boolean ok = video ? Bg.saveVideo(this, u) : Bg.save(this, u);
                ui.post(() -> {
                    if (ok) { applyBg(); toast(video ? "Live wallpaper dipasang" : "Background diganti"); }
                    else toast(video ? "Gagal: bukan video / lebih dari 200 MB" : "Gagal membaca gambar");
                });
            });
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (page == 2 && rec != null) rec.resume();
        syncVideo();
        immersive();
        buildGames();
        ui.post(this::syncService);      // hidupkan lagi pemantau kalau sesi masih tercatat
        ui.post(() -> RecorderService.syncArm(this));   // perekam siaga (bubble / auto-rekam) kalau dinyalakan
        ui.post(loop);
    }
    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        if (rec != null) rec.pause();
        syncVideo();            // video dilepas pas keluar app / game dibuka
        ui.removeCallbacks(loop);
    }
    @Override protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(rx);
        io.shutdown();
    }

    // ================= logika =================

    /** Jalankan aksi di background, sinkronkan service, lalu segarkan layar. */
    private void apply(String msg, Runnable job) {
        io.execute(() -> {
            job.run();
            log(msg);
            ui.post(this::syncService);
            render();
        });
    }

    private void cleanRam() {
        if (!root) { toast("Butuh root"); return; }
        toast("Membersihkan RAM...");
        io.execute(() -> {
            String r = Boost.cleanRam(this);
            log(r.split("\n")[0]);
            notifyResult("RAM dibersihkan", r);
            ui.post(this::syncService);
            render();
        });
    }

    private void resetAll() {
        io.execute(() -> {
            String r = Boost.resetAll(this);
            log(r.split("\n")[0]);
            notifyResult("Reset selesai", r);
            ui.post(this::syncService);
            render();
        });
    }

    /** Notifikasi jelas (bisa dibuka melebar) + toast, baris 1 = ringkasan, sisanya rincian. */
    private void notifyResult(String title, String text) {
        Boost.notifyResult(this, title, text);
        final String first = text.split("\n")[0];
        ui.post(() -> toast(title + "\n" + first));
    }

    /** Profil per game: setelan yang dipakai khusus pas game ini dibuka (tahan kartu game). */
    private void editProfile(String name, String pkg, boolean custom) {
        final boolean[] on = {Boost.profLock(this, pkg), Boost.profKill(this, pkg),
            Boost.profDnd(this, pkg), Boost.profRr(this, pkg)};
        String[] items = {"Lock Clock (panas!)", "Bunuh App Background", "Jangan Ganggu", "Kunci Refresh Rate"};
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Profil " + name)
            .setMultiChoiceItems(items, on, (d, i, checked) -> on[i] = checked)
            .setPositiveButton("SIMPAN", (d, w) -> {
                Boost.setProf(this, pkg, on[0], on[1], on[2], on[3]);
                toast("Profil " + name + " disimpan");
            })
            .setNegativeButton("Batal", null);
        if (custom) {
            b.setNeutralButton("HAPUS GAME", (d, w) -> {
                Boost.removeCustom(this, pkg);
                buildGames();
                toast(name + " dihapus dari launcher");
            });
        }
        b.show();
    }

    /** Pratinjau HUD melayang yang bisa digeser pakai jari, posisinya disimpan sebagai pecahan layar. */
    private void editHudPos() {
        if (!Settings.canDrawOverlays(this)) { askOverlay(); return; }
        final WindowManager wm = getSystemService(WindowManager.class);
        final HudView pv = new HudView(this);
        pv.set(new String[]{"60", "32ms", "35% 1.8G 41\u00b0", "20% 600M 40\u00b0", "5.1/8.0G", "38.5\u00b0 80%"}, GREEN, 32);
        final WindowManager.LayoutParams lp = HudView.params(this);
        lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;     // pratinjau harus bisa disentuh
        final DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        final float[] st = new float[4];
        pv.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    st[0] = e.getRawX(); st[1] = e.getRawY(); st[2] = lp.x; st[3] = lp.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lp.x = Math.max(-dm.widthPixels / 2, Math.min(dm.widthPixels / 2, (int) (st[2] + e.getRawX() - st[0])));
                    lp.y = Math.max(0, Math.min(dm.heightPixels - v.getHeight(), (int) (st[3] - (e.getRawY() - st[1]))));
                    try { wm.updateViewLayout(v, lp); } catch (Exception ignored) {}
                    return true;
                default:
                    return false;
            }
        });
        try {
            wm.addView(pv, lp);
        } catch (Exception e) {
            toast("Gagal menampilkan HUD");
            return;
        }
        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Posisi & ukuran HUD")
            .setView(sizeBox(pv))
            .setPositiveButton("SIMPAN", (d, w) -> {
                Boost.setHudPos(this, (float) lp.x / dm.widthPixels, (float) lp.y / dm.heightPixels);
                toast("Posisi HUD disimpan");
            })
            .setNeutralButton("TENGAH BAWAH", (d, w) -> {
                Boost.setHudPos(this, 0f, 0.003f);
                toast("Posisi HUD dikembalikan ke tengah bawah");
            })
            .setNegativeButton("Batal", null)
            .setOnDismissListener(d -> { try { wm.removeView(pv); } catch (Exception ignored) {} })
            .create();
        dlg.setCanceledOnTouchOutside(false);
        dlg.show();
    }

    // ---- v2.6: target FPS, preset, riwayat, optimasi ----
    private void showTarget() {
        int t = Boost.fpsTarget(this);
        tgtVal.setText(t <= 0 ? "Auto" : t + " FPS");
        refreshQuick();
    }

    private void pickTarget() {
        final int[] vals = {0, 30, 45, 60, 90, 120};
        String[] names = {"Otomatis (ikut FPS tertinggi yang dicapai game)", "30 FPS (hemat)", "45 FPS", "60 FPS (disarankan)", "90 FPS", "120 FPS"};
        int cur = 3;
        for (int i = 0; i < vals.length; i++) if (vals[i] == Boost.fpsTarget(this)) cur = i;
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Target FPS (Mode Adaptif)")
            .setSingleChoiceItems(names, cur, (d, i) -> {
                Boost.setFpsTarget(this, vals[i]);
                showTarget();
                d.dismiss();
            })
            .setNegativeButton("Tutup", null)
            .show();
    }

    /** Preset cepat: atur beberapa setelan global sekaligus. Lock Clock tetap per-game di profil. */
    private static final String[] PRESET_NAMES = {"Hemat (dingin, 30 FPS)", "Seimbang (disarankan)", "Ekstrem (clock max, panas)"};

    private void pickPreset() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Preset performa")
            .setItems(PRESET_NAMES, (d, i) -> applyPreset(i))
            .setNegativeButton("Batal", null)
            .show();
    }

    /** 0 = Hemat, 1 = Seimbang, 2 = Ekstrem. Dipakai dialog Preset dan Mode Cepat di Lobby. */
    private void applyPreset(int i) {
        apply("Preset " + PRESET_NAMES[i].split(" ")[0] + " dipasang", () -> {
            switch (i) {
                case 0:
                    Boost.setAdapt(this, true); Boost.setFpsTarget(this, 30);
                    Boost.setGpuMax(this, false); Boost.setCap(this, true);
                    break;
                case 1:
                    Boost.setAdapt(this, true); Boost.setFpsTarget(this, 60);
                    Boost.setGpuMax(this, true); Boost.setCap(this, true);
                    break;
                default:
                    Boost.setAdapt(this, false); Boost.setFpsTarget(this, 60);
                    Boost.setGpuMax(this, true); Boost.setCap(this, false);
                    break;
            }
            ui.post(this::showTarget);
        });
    }

    /** Preset yang cocok dengan setelan sekarang, -1 kalau sudah diubah manual. */
    private int currentPreset() {
        boolean ad = Boost.adaptOn(this), gm = Boost.gpuMaxOn(this), cp = Boost.capOn(this);
        int t = Boost.fpsTarget(this);
        if (ad && t == 30 && !gm && cp) return 0;
        if (ad && t == 60 && gm && cp) return 1;
        if (!ad && t == 60 && gm && !cp) return 2;
        return -1;
    }

    /** Sorot chip Mode Cepat yang sedang aktif. */
    private void refreshQuick() {
        if (quickChips == null) return;
        int cur = currentPreset();
        for (int i = 0; i < quickChips.length; i++) {
            boolean on = i == cur;
            quickChips[i].setTextColor(on ? FG : 0xFFCCD2E0);
            quickChips[i].setBackground(on ? grad(ACC1, ACC2, ACCENT, 10) : shape(ACC_BG, ACC_LINE, 10));
        }
    }

    // ---- backup / restore setelan lewat clipboard ----
    private void backupCopy() {
        String j = Boost.exportSettings(this);
        if (j == null) { toast("Gagal bikin backup"); return; }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("vallen-boost-backup", j));
        toast("Backup disalin. Tempel ke catatan / chat buat disimpan");
    }

    private void backupRestore() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        CharSequence cs = null;
        if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0)
            cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        final String txt = cs == null ? "" : cs.toString();
        if (!txt.contains("vallen-boost")) { toast("Salin teks backup dulu, baru tekan PULIHKAN"); return; }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Pulihkan setelan?")
            .setMessage("Tema, HUD, profil game, dan daftar game custom akan diganti dari teks backup di clipboard.")
            .setPositiveButton("PULIHKAN", (d, w) -> {
                int n = Boost.importSettings(this, txt);
                if (n < 0) { toast("Teks backup tidak valid"); return; }
                toast(n + " setelan dipulihkan");
                recreate();
            })
            .setNegativeButton("Batal", null)
            .show();
    }

    private void showHistory() {
        io.execute(() -> {
            final String t = Boost.historyText(this);
            ui.post(() -> {
                TextView tv = text(t, 11, FG);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextIsSelectable(true);
                tv.setPadding(dp(16), dp(8), dp(16), dp(8));
                ScrollView sc = new ScrollView(this);
                sc.addView(tv);
                new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Riwayat sesi & A/B")
                    .setView(sc)
                    .setPositiveButton("SALIN CSV", (dlg, w) -> {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("riwayat", Boost.historyCsv(this)));
                        toast("CSV tersalin, tempel ke spreadsheet");
                    })
                    .setNeutralButton("HAPUS", (dlg, w) -> { Boost.clearHistory(this); toast("Riwayat dihapus"); })
                    .setNegativeButton("Tutup", null)
                    .show();
            });
        });
    }

    /** Compile ART (speed-profile) game yang dipilih di lobby, sekali. Thread sendiri supaya aksi lain tidak ikut macet. */
    private void optimizeGame() {
        if (!root) { toast("Butuh root"); return; }
        final String pkg = selPkg;
        if (pkg == null || pkg.isEmpty()) { toast("Pilih game dulu di Lobby"); return; }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Optimasi game (sekali)")
            .setMessage("Compile ulang kode game (speed-profile) biar stutter dari JIT berkurang. Bisa 1-4 menit dan HP agak hangat. Aman, tidak mengubah data game.")
            .setPositiveButton("START", (d, w) -> {
                toast("Mengoptimasi... boleh ditinggal");
                new Thread(() -> {
                    String r = Boost.compileGame(this, pkg);
                    log("Optimasi game: " + r);
                    notifyResult("Optimasi game", r);
                }).start();
            })
            .setNegativeButton("Batal", null)
            .show();
    }

    // ---- tema + ukuran HUD ----
    private void showTheme() { themeVal.setText(Boost.THEME_NAMES[Boost.theme(this)]); }

    private void showHudTheme() {
        int t = Boost.hudTheme(this);
        hudThemeVal.setText(t < 0 ? "Ikut aplikasi" : Boost.THEME_NAMES[t]);
    }

    private void showHudSize() { hudSizeVal.setText(Math.round(Boost.hudScale(this) * 100) + "%"); }

    /** Nama tema dengan titik berwarna. extra = item tambahan di paling atas (mis. "Ikut tema aplikasi"). */
    private CharSequence[] themeItems(String extra) {
        int off = extra == null ? 0 : 1;
        CharSequence[] out = new CharSequence[Boost.THEME_NAMES.length + off];
        if (extra != null) out[0] = extra;
        for (int i = 0; i < Boost.THEME_NAMES.length; i++) {
            SpannableString sp = new SpannableString("\u25cf  " + Boost.THEME_NAMES[i]);
            sp.setSpan(new ForegroundColorSpan(Boost.THEME_ACCENT[i]), 0, 1, 0);
            out[i + off] = sp;
        }
        return out;
    }

    private void pickTheme() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Tema aplikasi")
            .setSingleChoiceItems(themeItems(null), Boost.theme(this), (d, i) -> {
                d.dismiss();
                if (i == Boost.theme(this)) return;
                Boost.setTheme(this, i);
                recreate();                                     // bangun ulang UI dengan warna baru
            })
            .setNegativeButton("Tutup", null)
            .show();
    }

    private void pickHudTheme() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Tema HUD")
            .setSingleChoiceItems(themeItems("Ikut tema aplikasi"), Boost.hudTheme(this) + 1, (d, i) -> {
                Boost.setHudTheme(this, i - 1);
                showHudTheme();
                d.dismiss();
                toast("Tema HUD diganti" + (BoostService.running ? " (langsung berlaku)" : ""));
            })
            .setNegativeButton("Tutup", null)
            .show();
    }

    /** Isi dialog atur HUD: petunjuk + slider ukuran (60%..160%) yang langsung mengubah pratinjau. */
    private View sizeBox(final HudView pv) {
        LinearLayout b = box(LinearLayout.VERTICAL);
        b.setPadding(dp(20), dp(8), dp(20), dp(4));
        b.addView(text("Geser HUD di layar pakai jari ke posisi yang kamu mau, atur ukurannya, lalu SIMPAN.", 12, DIM));
        final TextView lbl = text("", 13, FG);
        lbl.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        lbl.setPadding(0, dp(14), 0, dp(2));
        b.addView(lbl);
        final SeekBar sb = new SeekBar(this);
        sb.setMax(100);                                          // 60% .. 160%
        sb.setProgress(Math.round((Boost.hudScale(this) - 0.6f) * 100));
        sb.setProgressTintList(ColorStateList.valueOf(ACCENT));
        sb.setThumbTintList(ColorStateList.valueOf(ACCENT));
        lbl.setText("UKURAN HUD  " + Math.round(Boost.hudScale(this) * 100) + "%");
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s2, int p, boolean user) {
                float v = 0.6f + p / 100f;
                Boost.setHudScale(MainActivity.this, v);
                lbl.setText("UKURAN HUD  " + Math.round(v * 100) + "%");
                showHudSize();
                if (pv != null) pv.restyle(MainActivity.this);
            }
            @Override public void onStartTrackingTouch(SeekBar s2) {}
            @Override public void onStopTrackingTouch(SeekBar s2) {}
        });
        b.addView(sb);
        b.addView(button("RESET 100%", GOLD, v -> sb.setProgress(40)), lp(MATCH, dp(40), 0, 0, 10, 0, 0));
        return b;
    }

    private void showRes() {
        int v = Boost.resPct(this);
        resVal.setText(v <= 0 ? "Asli" : v + "%");
    }

    /** Pilih resolusi pas main. Dipasang sebelum game dibuka lewat launcher, dibalikin otomatis pas game ditutup. */
    private void pickRes() {
        final int[] vals = {0, 90, 85, 75};
        String[] names = {"Mati (resolusi asli)", "90%", "85% (disarankan)", "75% (paling ngacir, agak burik)"};
        int cur = 0;
        for (int i = 0; i < vals.length; i++) if (vals[i] == Boost.resPct(this)) cur = i;
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Resolusi pas main")
            .setSingleChoiceItems(names, cur, (d, i) -> {
                Boost.setResPct(this, vals[i]);
                showRes();
                toast("Berlaku kalau game dibuka lewat launcher app ini (Auto Boost tidak ganti resolusi)");
                d.dismiss();
            })
            .setNegativeButton("Tutup", null)
            .show();
    }

    private void syncService() {
        Intent svc = new Intent(this, BoostService.class);
        if (Boost.gameOn(this) || Boost.lockOn(this) || !Boost.session(this).isEmpty() || Boost.autoOn(this)) startForegroundService(svc);
        else stopService(svc);
    }

    private void launch(String pkg, String name) {
        Intent li = getPackageManager().getLaunchIntentForPackage(pkg);
        if (li == null) { toast(name + " belum terpasang"); return; }
        if (Boost.hudOn(this) && !Settings.canDrawOverlays(this)) { askOverlay(); return; }
        if (!Boost.preCheckOn(this)) { doLaunch(pkg, name, li); return; }
        if (checking) return;
        checking = true;
        toast("Cek koneksi...");
        io.execute(() -> {
            final int[] r = netCheck();                      // {rata-rata ms, paket sampai dari 4}
            ui.post(() -> {
                checking = false;
                if (r[1] >= 3 && r[0] <= 120) {
                    toast("Koneksi oke (" + r[0] + " ms)");
                    doLaunch(pkg, name, li);
                    return;
                }
                String why = r[1] == 0 ? "Tidak ada koneksi ke internet."
                    : "Ping " + r[0] + " ms, " + r[1] + " dari 4 paket sampai.";
                new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Koneksi lagi jelek")
                    .setMessage(why + "\nKalau game lag, kemungkinan besar dari jaringan, bukan HP.")
                    .setPositiveButton("MAIN TETAP", (d, w) -> doLaunch(pkg, name, li))
                    .setNegativeButton("BATAL", null)
                    .show();
            });
        });
    }

    /** 4x TCP connect ke 1.1.1.1:443 (tanpa root). Return {rata-rata ms, jumlah yang berhasil}. */
    private int[] netCheck() {
        int ok = 0, sum = 0;
        for (int i = 0; i < 4; i++) {
            try (java.net.Socket s = new java.net.Socket()) {
                long t0 = android.os.SystemClock.elapsedRealtime();
                s.connect(new java.net.InetSocketAddress("1.1.1.1", 443), 1200);
                sum += (int) (android.os.SystemClock.elapsedRealtime() - t0);
                ok++;
            } catch (Exception ignored) { }
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        return new int[]{ok == 0 ? -1 : sum / ok, ok};
    }

    private void doLaunch(String pkg, String name, Intent li) {
        if (root) launching.begin(name);
        else toast("Boost " + name + "...");
        io.execute(() -> {
            if (root) Boost.prepareLaunch(this, pkg, true, new Boost.Prog() {
                @Override public void step(int pct, String label) { launching.step(pct, label); }
                @Override public void phase(String t) { launching.phase(t); }
                @Override public void check(boolean ok, String label) { launching.check(ok, label); }
            });
            log(root ? "Boost siap \u2192 " + name : "Tanpa root, langsung buka " + name);
            Runnable open = () -> {
                syncService();
                getApplicationContext().startActivity(li);
            };
            if (root) launching.end(() -> ui.post(open)); else ui.post(open);
            // bersihin background SETELAH game dibuka, supaya game tidak nunggu puluhan force-stop
            if (root && Boost.profKill(this, pkg)) {
                Boost.killBackground(this, pkg);
                log("App background dibersihkan");
            }
            render();
        });
    }

    /** Laporan sensor mentah (chipset, file GPU, thermal) buat nyocokin pembacaan HUD. */
    private void diagnose() {
        if (!root) { toast("Butuh root"); return; }
        toast("Membaca sensor...");
        io.execute(() -> {
            String rep = mx.diagnose();
            ui.post(() -> {
                TextView t = text(rep, 10, FG);
                t.setTypeface(Typeface.MONOSPACE);
                t.setTextIsSelectable(true);
                t.setPadding(dp(16), dp(8), dp(16), dp(8));
                ScrollView sc = new ScrollView(this);
                sc.addView(t);
                new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Diagnosa sensor")
                    .setView(sc)
                    .setPositiveButton("SALIN", (dlg, which) -> {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("diagnosa", rep));
                        toast("Tersalin, paste ke Claude");
                    })
                    .setNegativeButton("Tutup", null)
                    .show();
            });
        });
    }

    private void askOverlay() {
        toast("Aktifkan izin 'Tampil di atas aplikasi lain', lalu coba lagi");
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getPackageName())));
    }

    private void render() {
        io.execute(() -> {
            Metrics.Snap cached = BoostService.lastSnap;
            Metrics.Snap s = cached != null && SystemClock.uptimeMillis() - BoostService.lastSnapAt < 3500
                ? cached : mx.sample(root);
            int fps = BoostService.lastFps, ping = BoostService.lastPing;
            int max = Boost.limit(this) * 10;
            ui.post(() -> {
                busy = true;
                gameSw.setChecked(Boost.gameOn(this));
                lockSw.setChecked(Boost.lockOn(this));
                hudSw.setChecked(Boost.hudOn(this));
                killSw.setChecked(Boost.killOn(this));
                dndSw.setChecked(Boost.dndOn(this));
                rrSw.setChecked(Boost.rrOn(this));
                autoSw.setChecked(Boost.autoOn(this));
                ecoSw.setChecked(Boost.ecoOn(this));
                pingSw.setChecked(Boost.pingGameOn(this));
                gpuMaxSw.setChecked(Boost.gpuMaxOn(this));
                capSw.setChecked(Boost.capOn(this));
                marginSw.setChecked(Boost.marginOn(this));
                adaptSw.setChecked(Boost.adaptOn(this));
                wifiSw.setChecked(Boost.wifiLlOn(this));
                pingAlertSw.setChecked(Boost.pingAlertOn(this));
                preCheckSw.setChecked(Boost.preCheckOn(this));
                gmodeSw.setChecked(Boost.gmodeOn(this));
                smartSw.setChecked(Boost.smartKillOn(this));
                abSw.setChecked(Boost.abNext(this));
                busy = false;
                String rp = Boost.report(this);
                if (!rp.isEmpty()) { reportView.setText(rp); reportView.setTextColor(FG); }
                String gr = Boost.graph(this);
                if (!gr.isEmpty()) graphView.set(GraphView.parse(gr, "f"), GraphView.parse(gr, "t"));

                vFps.setText(fps < 0 ? "--" : String.valueOf(fps));
                vPing.setText(ping < 0 ? "--" : String.valueOf(ping));
                vBat.setText(String.format(Locale.US, "%.1f\u00b0", s.batTemp10 / 10f));
                vBat.setTextColor(s.batTemp10 >= max ? RED : (s.batTemp10 >= max - 30 ? AMBER : GREEN));
                sBat.setText("baterai " + s.batPct + "%");
                vCpu.setText(Metrics.pct(s.cpuPct));
                sCpu.setText(Metrics.freq(s.cpuMhz) + " " + Metrics.deg(s.cpuTemp));
                vGpu.setText(Metrics.pct(s.gpuPct));
                sGpu.setText(Metrics.freq(s.gpuMhz) + " " + Metrics.deg(s.gpuTemp));
                int pr = s.ramTotalMb > 0 ? s.ramUsedMb * 100 / s.ramTotalMb : -1;
                vRam.setText(Metrics.pct(pr));
                sRam.setText(Metrics.gb(s.ramUsedMb) + "/" + Metrics.gb(s.ramTotalMb) + "G");
                sysView.setText(root ? "GOV " + s.gov + "  \u2022  TCP " + s.tcp : "GOV -  \u2022  TCP -");
            });
        });
    }

    private void log(String m) {
        ui.post(() -> {
            logBuf.insert(0, new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + m + "\n");
            if (logBuf.length() > 700) logBuf.setLength(700);
            logView.setText(logBuf);
        });
    }

    private void showLimit() {
        int v = Boost.limit(this);
        limitView.setText("AUTO-OFF BATERAI  " + v + "\u00b0C" + (v >= 50 ? "  \u26a0 BAHAYA" : ""));
        limitView.setTextColor(v >= 50 ? RED : FG);
    }

    private void showChip() {
        int v = Boost.chipLimit(this);
        chipView.setText("AUTO-OFF CHIP CPU/GPU  " + v + "\u00b0C" + (v >= 90 ? "  \u26a0 BAHAYA" : ""));
        chipView.setTextColor(v >= 90 ? RED : FG);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ================= game launcher =================

    // ================= lobby (tampilan ala game launcher) =================

    private View buildLobby() {
        FrameLayout lobby = new FrameLayout(this);

        // ---- top bar ----
        LinearLayout top = box(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(20), dp(12), dp(20), 0);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.icon_vallen);
        round(logo, 10);
        top.addView(logo, lp(dp(34), dp(34), 0, 0, 0, 10, 0));
        TextView title = text("VALLEN BOOST", 18, FG);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        title.setLetterSpacing(0.14f);
        title.setShadowLayer(dp(6), 0, dp(1), 0xCC000000);
        top.addView(title);
        top.addView(rootChip, lp(WRAP, WRAP, 0, 12, 0, 0, 0));
        lobby.addView(top, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP));

        // ---- daftar game (kiri) ----
        gameList = box(LinearLayout.VERTICAL);
        ScrollView gs = new ScrollView(this);
        gs.setVerticalScrollBarEnabled(false);
        gs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        gs.addView(gameList);
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(dp(270), MATCH, Gravity.START);
        gp.setMargins(dp(20), dp(64), 0, dp(78));
        lobby.addView(gs, gp);

        // ---- hero (kanan bawah): nama game + tombol start ----
        LinearLayout hero = box(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.END);
        heroName = text("", 34, FG);
        heroName.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        heroName.setShadowLayer(dp(8), 0, dp(2), 0xCC000000);
        heroName.setGravity(Gravity.END);
        heroTag = text("", 11, 0xFFCCD2E0);
        heroTag.setLetterSpacing(0.2f);
        heroTag.setGravity(Gravity.END);
        LinearLayout act = box(LinearLayout.HORIZONTAL);
        act.setGravity(Gravity.CENTER_VERTICAL);
        startBtn = text("", 17, FG);
        startBtn.setTypeface(Typeface.DEFAULT_BOLD);
        startBtn.setLetterSpacing(0.15f);
        startBtn.setGravity(Gravity.CENTER);
        startBtn.setPadding(dp(40), dp(14), dp(40), dp(14));
        startBtn.setBackground(grad(ACC1, ACC2, ACCENT, 12));
        startBtn.setOnClickListener(v -> startSelected());
        TextView info = text("\u203a", 26, FG);
        info.setGravity(Gravity.CENTER);
        info.setPadding(dp(18), dp(6), dp(18), dp(6));
        info.setBackground(shape(ACC_BG, ACC_LINE, 12));
        info.setOnClickListener(v -> profileSelected());
        act.addView(startBtn, lp(WRAP, WRAP, 0, 0, 0, 8, 0));
        act.addView(info, new LinearLayout.LayoutParams(WRAP, WRAP));
        hero.addView(heroName, new LinearLayout.LayoutParams(WRAP, WRAP));
        hero.addView(heroTag, lp(WRAP, WRAP, 0, 0, 0, 0, 12));
        LinearLayout quick = box(LinearLayout.HORIZONTAL);
        quick.setGravity(Gravity.END);
        String[] qn = {"HEMAT", "SEIMBANG", "EKSTREM"};
        quickChips = new TextView[qn.length];
        for (int i = 0; i < qn.length; i++) {
            final int idx = i;
            TextView ch = text(qn[i], 11, 0xFFCCD2E0);
            ch.setTypeface(Typeface.DEFAULT_BOLD);
            ch.setLetterSpacing(0.1f);
            ch.setGravity(Gravity.CENTER);
            ch.setPadding(dp(14), dp(7), dp(14), dp(7));
            ch.setOnClickListener(v -> { if (!busy) applyPreset(idx); });
            quickChips[i] = ch;
            quick.addView(ch, lp(WRAP, WRAP, 0, i == 0 ? 0 : 6, 0, 0, 0));
        }
        hero.addView(quick, lp(WRAP, WRAP, 0, 0, 0, 0, 12));
        refreshQuick();
        hero.addView(act, new LinearLayout.LayoutParams(WRAP, WRAP));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.END);
        hp.setMargins(0, 0, dp(28), dp(64));
        lobby.addView(hero, hp);

        return lobby;
    }

    private View buildSettingsPage(View body) {
        LinearLayout page = box(LinearLayout.VERTICAL);
        // wallpaper kelihatan (sama kayak Home), cuma digelapin tipis biar kartu tetap kebaca
        page.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0x22000000, 0x00000000, 0x33000000}));
        LinearLayout bar = box(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(10), dp(16), dp(10));
        bar.setBackgroundColor(0x33000000);
        bar.addView(pill("\u2039  KEMBALI", v -> showSettings(false)));
        View mark = new View(this);
        mark.setBackground(shape(ACCENT, 0, 2));
        bar.addView(mark, lp(dp(3), dp(18), 0, 14, 0, 0, 0));
        TextView t = text("SETTING", 16, FG);
        t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        t.setLetterSpacing(0.2f);
        t.setPadding(dp(10), 0, 0, 0);
        bar.addView(t);
        page.addView(bar, new LinearLayout.LayoutParams(MATCH, WRAP));
        View line = new View(this);
        line.setBackgroundColor(0x22FFFFFF);
        page.addView(line, new LinearLayout.LayoutParams(MATCH, 1));
        page.addView(body, new LinearLayout.LayoutParams(MATCH, 0, 1));
        return page;
    }

    private void showSettings(boolean show) { showPage(show ? 1 : 0); }

    private View pageView(int p) { return p == 0 ? lobbyView : p == 1 ? settingsPage : recView; }

    /** Geser halus antar Home / Setting / Rekam: halaman baru masuk dari samping sambil fade, yang lama keluar. */
    private void showPage(int to) {
        if (to == page) return;
        if (to == 2 && recView == null) {           // halaman Rekam dibangun saat pertama dibuka (startup tetap ringan)
            rec = new RecPage(this, () -> showPage(0));
            recView = rec.build();
            recView.setVisibility(View.GONE);
            stage.addView(recView, stage.indexOfChild(settingsPage) + 1, new FrameLayout.LayoutParams(MATCH, MATCH));
            applyInsets(recView);
        }
        final int from = page;
        page = to;
        updateNav();
        final View in = pageView(to), out = pageView(from);
        final float dx = dp(40) * (to > from ? 1 : -1);
        in.animate().cancel();
        out.animate().cancel();
        if (in.getVisibility() != View.VISIBLE) {
            in.setAlpha(0f);
            in.setTranslationX(dx);
            in.setVisibility(View.VISIBLE);
        }
        in.animate().alpha(1f).translationX(0f).setDuration(340)
            .setInterpolator(new DecelerateInterpolator(1.8f)).withLayer().start();
        out.animate().alpha(0f).translationX(-dx * 0.5f).setDuration(220)
            .setInterpolator(new AccelerateInterpolator(1.2f)).withLayer()
            .withEndAction(() -> {
                out.setVisibility(View.GONE);
                out.setTranslationX(0f);
                out.setAlpha(1f);
            }).start();
        if (to == 1) render();
        if (to == 2) rec.resume();
        else if (from == 2 && rec != null) rec.pause();
    }

    private void buildGames() {
        games.clear();
        for (String[] g : GAMES) games.add(new String[]{g[0], g[1], g[2], g[3], "0"});
        PackageManager pm = getPackageManager();
        for (String[] k : Boost.KNOWN) {                       // game populer lain, tampil kalau terpasang
            if (pm.getLaunchIntentForPackage(k[0]) == null) continue;
            try {
                String label = String.valueOf(pm.getApplicationInfo(k[0], 0).loadLabel(pm));
                games.add(new String[]{label, k[0], k[1], k[2], "0"});
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        for (String pkg : Boost.customs(this)) {
            try {
                String label = String.valueOf(pm.getApplicationInfo(pkg, 0).loadLabel(pm));
                games.add(new String[]{label, pkg, "CUSTOM  \u2022  tahan = profil", "#00D4FF", "1"});
            } catch (PackageManager.NameNotFoundException e) {
                // app sudah dihapus, lewati
            }
        }
        if (selPkg == null) selPkg = getSharedPreferences("lobby", MODE_PRIVATE).getString("sel", null);
        boolean found = false;
        for (String[] g : games) if (g[1].equals(selPkg)) found = true;
        if (!found) {                               // default: game pertama yang terpasang
            selPkg = games.get(0)[1];
            for (String[] g : games) {
                if (pm.getLaunchIntentForPackage(g[1]) != null) { selPkg = g[1]; break; }
            }
        }
        gameList.removeAllViews();
        for (String[] g : games) gameList.addView(gameItem(g));
        gameList.addView(addItem());
        updateHero();
    }

    private String[] selected() {
        for (String[] g : games) if (g[1].equals(selPkg)) return g;
        return null;
    }

    private void updateHero() {
        String[] g = selected();
        if (g == null) return;
        boolean ok = getPackageManager().getLaunchIntentForPackage(g[1]) != null;
        heroName.setText(g[0]);
        heroTag.setText(ok ? g[2] : "BELUM TERPASANG");
        startBtn.setText(ok ? "\u25b6  START" : "BELUM TERPASANG");
        startBtn.setAlpha(ok ? 1f : 0.45f);
    }

    private void startSelected() {
        String[] g = selected();
        if (g != null) launch(g[1], g[0]);
    }

    private void profileSelected() {
        String[] g = selected();
        if (g != null) editProfile(g[0], g[1], "1".equals(g[4]));
    }

    private View gameItem(String[] g) {
        final String name = g[0], pkg = g[1];
        final boolean custom = "1".equals(g[4]);
        final boolean installed = getPackageManager().getLaunchIntentForPackage(pkg) != null;
        final boolean sel = pkg.equals(selPkg);
        int accent = Color.parseColor(g[3]);

        LinearLayout r = box(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(8), dp(8), dp(12), dp(8));
        r.setBackground(sel ? shape(0x40FFFFFF, accent, 14) : shape(0x00000000, 0, 14));

        ImageView ic = new ImageView(this);
        ic.setScaleType(ImageView.ScaleType.CENTER_CROP);
        try {
            ic.setImageDrawable(getPackageManager().getApplicationIcon(pkg));
        } catch (Exception e) {
            ic.setImageResource(R.drawable.icon_vallen);
        }
        if (!installed) ic.setAlpha(0.35f);
        round(ic, 12);
        r.addView(ic, lp(dp(52), dp(52), 0, 0, 0, 12, 0));

        LinearLayout tx = box(LinearLayout.VERTICAL);
        TextView nm = text(name, 15, installed ? FG : DIM);
        nm.setTypeface(Typeface.DEFAULT_BOLD);
        nm.setSingleLine(true);
        nm.setShadowLayer(dp(6), 0, dp(1), 0xCC000000);
        TextView tg = text(installed ? g[2] : "BELUM TERPASANG", 10, DIM);
        tg.setLetterSpacing(0.08f);
        tg.setSingleLine(true);
        tg.setShadowLayer(dp(5), 0, dp(1), 0xCC000000);
        tx.addView(nm);
        tx.addView(tg);
        r.addView(tx, lp(0, WRAP, 1, 0, 0, 0, 0));

        r.setOnClickListener(v -> {
            selPkg = pkg;
            getSharedPreferences("lobby", MODE_PRIVATE).edit().putString("sel", pkg).apply();
            buildGames();
        });
        r.setOnLongClickListener(v -> {
            editProfile(name, pkg, custom);
            return true;
        });
        r.setLayoutParams(lp(MATCH, WRAP, 0, 0, 0, 0, 6));
        return r;
    }

    private View addItem() {
        TextView t = text("+   GAME LAIN", 12, DIM);
        t.setLetterSpacing(0.15f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(14), 0, dp(14));
        t.setBackground(shape(0x00000000, 0x55FFFFFF, 14));
        t.setOnClickListener(v -> pickGame());
        t.setLayoutParams(lp(MATCH, WRAP, 0, 0, 4, 0, 0));
        return t;
    }

    private TextView pill(String s, View.OnClickListener l) {
        TextView t = text(s, 11, FG);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.12f);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        t.setBackground(shape(0x66000000, 0x44FFFFFF, 18));
        t.setOnClickListener(l);
        pressFx(t);
        return t;
    }

    /** Potong sudut gambar/ikon jadi rounded. */
    private void round(View v, int radiusDp) {
        final int r = dp(radiusDp);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }

    // ---- background custom ----

    private void bgMenu() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Background lobby")
            .setItems(new String[]{"Pilih gambar dari galeri", "Pilih video (live wallpaper)", "Kembalikan ke default"}, (d, i) -> {
                if (i == 0) {
                    Intent it = new Intent(Intent.ACTION_GET_CONTENT);
                    it.setType("image/*");
                    it.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(Intent.createChooser(it, "Pilih background"), REQ_BG);
                } else if (i == 1) {
                    Intent it = new Intent(Intent.ACTION_GET_CONTENT);
                    it.setType("video/*");
                    it.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(Intent.createChooser(it, "Pilih video"), REQ_VID);
                } else {
                    Bg.clear(this);
                    applyBg();
                    toast("Background dikembalikan");
                }
            })
            .show();
    }

    private void applyBg() {
        Bitmap b = Bg.load(this);
        if (b != null) {
            bgView.setBackground(null);
            bgView.setImageBitmap(b);
            loading.setBackdrop(b);
        } else {
            bgView.setImageDrawable(null);
            bgView.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0B0F2A, 0xFF2B1655, 0xFF07080C}));
            loading.setBackdrop(null);
        }
        syncVideo();
    }

    /** Video live wallpaper diputar selama app kelihatan (Home & Setting); dilepas pas app di background. */
    private void syncVideo() {
        File v = Bg.video(this);
        if (v != null && resumed) bgVideo.play(v.getAbsolutePath());
        else bgVideo.stopPlayback();
        bgVideo.setVisibility(v != null ? View.VISIBLE : View.GONE);
    }

    // ---- navigasi bawah: Home / Tuning / Background ----

    private View buildNav() {
        LinearLayout nav = box(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(dp(6), dp(5), dp(6), dp(5));
        nav.setBackground(shape(0xB3080A10, 0x33FFFFFF, 24));
        navHome = navItem(R.drawable.ic_nav_home, "Home", v -> showSettings(false));
        navTune = navItem(R.drawable.ic_nav_tuning, "Setting", v -> showSettings(true));
        navBg = navItem(R.drawable.ic_nav_bg, "Background", v -> bgMenu());
        navRec = navItem(R.drawable.ic_nav_rec, "Rekam", v -> showPage(2));
        nav.addView(navHome, lp(WRAP, WRAP, 0, 2, 0, 2, 0));    // urutan: Home, Setting, Rekam, Background
        nav.addView(navTune, lp(WRAP, WRAP, 0, 2, 0, 2, 0));
        nav.addView(navRec, lp(WRAP, WRAP, 0, 2, 0, 2, 0));
        nav.addView(navBg, lp(WRAP, WRAP, 0, 2, 0, 2, 0));
        updateNav();
        return nav;
    }

    /** Ikon di atas, nama di bawah. */
    private LinearLayout navItem(int res, String label, View.OnClickListener l) {
        LinearLayout it = box(LinearLayout.VERTICAL);
        it.setGravity(Gravity.CENTER_HORIZONTAL);
        it.setPadding(dp(12), dp(5), dp(12), dp(4));
        ImageView ic = new ImageView(this);
        ic.setImageResource(res);
        ic.setContentDescription(label);
        it.addView(ic, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView t = text(label, 9, FG);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.06f);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        it.addView(t, lp(WRAP, WRAP, 0, 0, 2, 0, 0));
        it.setOnClickListener(l);
        return it;
    }

    /** Home / Setting / Rekam nyala sesuai halaman yang lagi dibuka. Background cuma tombol aksi. */
    private void updateNav() {
        styleNav(navHome, page == 0);
        styleNav(navTune, page == 1);
        styleNav(navBg, false);
        styleNav(navRec, page == 2);
    }

    private void styleNav(LinearLayout v, boolean on) {
        int c = on ? FG : 0xFF9AA3B8;
        ((ImageView) v.getChildAt(0)).setColorFilter(c);
        ((TextView) v.getChildAt(1)).setTextColor(c);
        v.setBackground(on ? shape(0x33FFFFFF, 0x66FFFFFF, 18) : null);
    }

    // ---- layar penuh landscape ----

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** Geser konten menjauh dari poni kamera di sisi kiri/kanan. */
    private void applyInsets(final View v) {
        v.setOnApplyWindowInsetsListener((view, ins) -> {
            int l = 0, r = 0;
            if (Build.VERSION.SDK_INT >= 28 && ins.getDisplayCutout() != null) {
                l = ins.getDisplayCutout().getSafeInsetLeft();
                r = ins.getDisplayCutout().getSafeInsetRight();
            }
            view.setPadding(l, 0, r, 0);
            return ins;
        });
    }


    /** Semua app yang punya launcher icon, terurut nama: {label, package}. */
    private List<String[]> launcherApps() {
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<String[]> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo r : pm.queryIntentActivities(q, 0)) {
            String p = r.activityInfo.packageName;
            if (p.equals(getPackageName()) || !seen.add(p)) continue;
            items.add(new String[]{String.valueOf(r.loadLabel(pm)), p});
        }
        Collections.sort(items, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        return items;
    }

    /** Pilih app yang tidak boleh di-force-stop saat launch game (WhatsApp, VPN, app OTP, dll). */
    private void pickKeep() {
        final List<String[]> items = launcherApps();
        final Set<String> keep = Boost.keeps(this);
        String[] names = new String[items.size()];
        boolean[] on = new boolean[items.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = items.get(i)[0];
            on[i] = keep.contains(items.get(i)[1]);
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Jangan dibunuh")
            .setMultiChoiceItems(names, on, (d, i, checked) -> {
                if (checked) keep.add(items.get(i)[1]); else keep.remove(items.get(i)[1]);
            })
            .setPositiveButton("SIMPAN", (d, w) -> {
                Boost.setKeeps(this, keep);
                toast(keep.size() + " app dikecualikan");
            })
            .setNegativeButton("Batal", null)
            .show();
    }

    private void pickGame() {
        List<String[]> items = launcherApps();
        String[] names = new String[items.size()];
        for (int i = 0; i < names.length; i++) names[i] = items.get(i)[0];
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Pilih game")
            .setItems(names, (d, i) -> {
                Boost.addCustom(this, items.get(i)[1]);
                buildGames();
            }).show();
    }

    private static String initials(String name) {
        StringBuilder sb = new StringBuilder();
        for (String w : name.trim().split("\\s+")) {
            if (!w.isEmpty() && sb.length() < 3) sb.append(Character.toUpperCase(w.charAt(0)));
        }
        return sb.toString();
    }

    // ================= helper UI =================

    private LinearLayout box(int orientation) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(orientation);
        return l;
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private GradientDrawable shape(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private GradientDrawable grad(int c1, int c2, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private TextView big() {
        TextView t = text("--", 22, GREEN);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView tiny(String s) {
        TextView t = text(s, 10, DIM);
        t.setTypeface(Typeface.MONOSPACE);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        return t;
    }

    private LinearLayout tile(String label, TextView v, TextView s) {
        LinearLayout t = box(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(dp(2), dp(12), dp(2), dp(10));
        TextView l = text(label, 10, DIM);
        l.setLetterSpacing(0.2f);
        t.addView(l);
        t.addView(v, new LinearLayout.LayoutParams(MATCH, WRAP));
        t.addView(s, new LinearLayout.LayoutParams(MATCH, WRAP));
        return t;
    }

    private void section(LinearLayout col, String s) {
        SpannableString sp = new SpannableString("\u258c " + s);
        sp.setSpan(new ForegroundColorSpan(ACCENT), 0, 1, 0);
        TextView t = text("", 11, DIM);
        t.setText(sp);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.25f);
        t.setPadding(dp(2), dp(22), 0, dp(8));
        col.addView(t);
    }

    /** Kartu berisi baris-baris dengan pemisah. */
    private LinearLayout card(View... rows) {
        LinearLayout c = box(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(2), dp(14), dp(2));
        c.setBackground(shape(GLASS, 0x22FFFFFF, 20));
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) c.addView(divider());
            c.addView(rows[i]);
        }
        return c;
    }

    private TextView linkVal() {
        TextView t = text("", 12, ACCENT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Baris yang bisa diklik: judul + keterangan di kiri, nilai sekarang + panah di kanan. */
    private View linkRow(String title, String desc, TextView val, View.OnClickListener l) {
        LinearLayout r = box(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(12), 0, dp(12));
        LinearLayout tx = box(LinearLayout.VERTICAL);
        tx.addView(text(title, 14, FG));
        tx.addView(text(desc, 11, DIM));
        r.addView(tx, lp(0, WRAP, 1, 0, 0, 8, 0));
        r.addView(val);
        TextView chev = text("\u203a", 20, DIM);
        chev.setPadding(dp(8), 0, 0, 0);
        r.addView(chev);
        r.setOnClickListener(l);
        pressFx(r);
        return r;
    }

    /** Dua tombol sejajar. */
    private LinearLayout pair(View a, View b2) {
        LinearLayout p = box(LinearLayout.HORIZONTAL);
        p.addView(a, lp(0, dp(46), 1, 0, 0, 6, 0));
        p.addView(b2, lp(0, dp(46), 1, 6, 0, 0, 0));
        return p;
    }

    private Switch sw() {
        Switch w = new Switch(this);
        w.setThumbTintList(new ColorStateList(
            new int[][]{{android.R.attr.state_checked}, {}}, new int[]{ACCENT, 0xFF6B7280}));
        w.setTrackTintList(new ColorStateList(
            new int[][]{{android.R.attr.state_checked}, {}}, new int[]{(ACCENT & 0x00FFFFFF) | 0x66000000, 0x33FFFFFF}));
        return w;
    }

    private View row(String title, String desc, Switch w) {
        LinearLayout r = box(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(12), 0, dp(12));
        LinearLayout tx = box(LinearLayout.VERTICAL);
        tx.addView(text(title, 14, FG));
        tx.addView(text(desc, 11, DIM));
        r.addView(tx, lp(0, WRAP, 1, 0, 0, 8, 0));
        r.addView(w);
        return r;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(MATCH, 1));
        return v;
    }

    private Button button(String s, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setLetterSpacing(0.1f);
        b.setTextColor(color);
        b.setStateListAnimator(null);
        b.setBackground(shape((color & 0x00FFFFFF) | 0x22000000, (color & 0x00FFFFFF) | 0x99000000, 14));
        b.setOnClickListener(l);
        pressFx(b);
        return b;
    }

    /** Tombol utama: merah gradasi, sama kayak START di Home. */
    private Button primaryButton(String s, View.OnClickListener l) {
        Button b = button(s, FG, l);
        b.setBackground(grad(ACC1, ACC2, ACCENT, 14));
        return b;
    }

    /** Efek tekan: mengecil sedikit pas disentuh, balik halus pas dilepas. */
    private void pressFx(View v) {
        v.setOnTouchListener((view, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(0.96f).scaleY(0.96f).alpha(0.85f).setDuration(90).start();
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(160).start();
            }
            return false;
        });
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}

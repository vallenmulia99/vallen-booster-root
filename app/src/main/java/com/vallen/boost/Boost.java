package com.vallen.boost;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Semua operasi root. Cuma nulis ke /sys, /proc, dan perintah am/cmd. Tidak menyentuh file game. */
final class Boost {
    static final String TCP = "/proc/sys/net/ipv4/tcp_congestion_control";
    private static final String EACH = "for d in /sys/devices/system/cpu/cpu[0-9]*/cpufreq; do ";
    private static final String KGSL = "/sys/class/kgsl/kgsl-3d0";
    /** Cari folder devfreq GPU yang bisa ditulis (Adreno / Mali / lainnya). */
    private static final String GPU_FIND =
        "for d in " + KGSL + "/devfreq /sys/class/devfreq/*kgsl* /sys/class/devfreq/*mali* /sys/class/devfreq/*gpu*; do "
      + "[ -w $d/governor ] && echo $d && break; done";

    // grup top-app (tempat game jalan) + memori
    private static final String TA_BOOST = "/dev/stune/top-app/schedtune.boost";
    private static final String TA_IDLE = "/dev/stune/top-app/schedtune.prefer_idle";
    private static final String TA_CLAMP = "/dev/cpuctl/top-app/cpu.uclamp.min";
    private static final String SWAP = "/proc/sys/vm/swappiness";

    /** Diisi isRoot(); dibaca service supaya tidak spam su kalau tidak ada root. */
    static volatile boolean root;

    private Boost() {}

    // ---- state ----
    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("boost", Context.MODE_PRIVATE);
    }
    static boolean gameOn(Context c) { return prefs(c).getBoolean("game", false); }
    static boolean lockOn(Context c) { return prefs(c).getBoolean("lock", false); }
    static boolean hudOn(Context c) { return prefs(c).getBoolean("hud", true); }
    static boolean killOn(Context c) { return prefs(c).getBoolean("kill", true); }
    static boolean dndOn(Context c) { return prefs(c).getBoolean("dnd", false); }
    /** Package game yang lagi dimainkan (sesi dari launcher). Kosong = tidak ada sesi. */
    static String session(Context c) { return prefs(c).getString("session", ""); }
    static void setSession(Context c, String pkg) {
        prefs(c).edit().putString("session", pkg == null ? "" : pkg).commit();   // state kritis: harus sampai disk
    }
    static boolean rrOn(Context c) { return prefs(c).getBoolean("rr", true); }
    static void setRr(Context c, boolean on) { prefs(c).edit().putBoolean("rr", on).apply(); }
    /** Batas suhu chip (CPU/GPU) dalam derajat C. */
    static int chipLimit(Context c) { return prefs(c).getInt("chip", 85); }
    static void setChipLimit(Context c, int v) { prefs(c).edit().putInt("chip", v).apply(); }
    static String report(Context c) { return prefs(c).getString("report", ""); }
    static void setReport(Context c, String s) { prefs(c).edit().putString("report", s).apply(); }
    static boolean protectedOn(Context c) { return prefs(c).getBoolean("protected", false); }
    static int limit(Context c) { return prefs(c).getInt("limit", 46); }
    static void setLimit(Context c, int v) { prefs(c).edit().putInt("limit", v).apply(); }
    static void setHud(Context c, boolean on) { prefs(c).edit().putBoolean("hud", on).apply(); }
    static void setKill(Context c, boolean on) { prefs(c).edit().putBoolean("kill", on).apply(); }
    static void setDnd(Context c, boolean on) { prefs(c).edit().putBoolean("dnd", on).apply(); }

    // ---- v2.6: mode adaptif, target FPS, wifi low-latency, GameManager, smart kill, A/B ----
    static boolean adaptOn(Context c) { return prefs(c).getBoolean("adapt", true); }
    static void setAdapt(Context c, boolean on) { prefs(c).edit().putBoolean("adapt", on).apply(); }
    /** Target FPS mode adaptif. 0 = otomatis (ikut FPS tertinggi yang dicapai game). */
    static int fpsTarget(Context c) { return prefs(c).getInt("fpsTarget", 60); }
    static void setFpsTarget(Context c, int v) { prefs(c).edit().putInt("fpsTarget", v).apply(); }
    static boolean wifiLlOn(Context c) { return prefs(c).getBoolean("wifiLl", true); }
    static void setWifiLl(Context c, boolean on) { prefs(c).edit().putBoolean("wifiLl", on).apply(); }
    static boolean gmodeOn(Context c) { return prefs(c).getBoolean("gmode", true); }
    static void setGmode(Context c, boolean on) { prefs(c).edit().putBoolean("gmode", on).apply(); }
    /** Bunuh background versi pintar: `am kill` (bukan force-stop) hanya untuk app yang lagi jalan dan makan RAM. */
    static boolean smartKillOn(Context c) { return prefs(c).getBoolean("smartKill", true); }
    static void setSmartKill(Context c, boolean on) { prefs(c).edit().putBoolean("smartKill", on).apply(); }
    /** A/B: sesi game berikutnya dijalankan TANPA boost (cuma HUD + laporan) buat pembanding. */
    static boolean abNext(Context c) { return prefs(c).getBoolean("abNext", false); }
    static void setAbNext(Context c, boolean on) { prefs(c).edit().putBoolean("abNext", on).apply(); }

    // ---- fitur baru: auto boost, hemat panas, ping server game ----
    static boolean autoOn(Context c) { return prefs(c).getBoolean("auto", false); }
    static void setAuto(Context c, boolean on) { prefs(c).edit().putBoolean("auto", on).apply(); }
    static boolean ecoOn(Context c) { return prefs(c).getBoolean("eco", true); }
    static void setEco(Context c, boolean on) { prefs(c).edit().putBoolean("eco", on).apply(); }
    /** Alert lag koneksi di HUD + getar singkat pas ping spike / paket hilang. */
    static boolean pingAlertOn(Context c) { return prefs(c).getBoolean("pingAlert", true); }
    static void setPingAlert(Context c, boolean on) { prefs(c).edit().putBoolean("pingAlert", on).apply(); }
    /** Cek koneksi singkat sebelum START; kalau jelek, tanya dulu. */
    static boolean preCheckOn(Context c) { return prefs(c).getBoolean("preCheck", true); }
    static void setPreCheck(Context c, boolean on) { prefs(c).edit().putBoolean("preCheck", on).apply(); }
    static boolean pingGameOn(Context c) { return prefs(c).getBoolean("pingGame", true); }
    static void setPingGame(Context c, boolean on) { prefs(c).edit().putBoolean("pingGame", on).apply(); }
    /** Posisi HUD sebagai pecahan layar: x dari tengah (-0.5..0.5), y dari bawah (0..1). */
    static float hudX(Context c) { return prefs(c).getFloat("hudX", 0f); }
    static float hudY(Context c) { return prefs(c).getFloat("hudY", 0.003f); }
    static void setHudPos(Context c, float x, float y) { prefs(c).edit().putFloat("hudX", x).putFloat("hudY", y).apply(); }
    // ---- tema + ukuran HUD ----
    static final String[] THEME_NAMES = {"Merah", "Cyan", "Hijau Neon", "Ungu", "Emas", "Biru"};
    static final int[] THEME_ACCENT = {0xFFE8555C, 0xFF00D4FF, 0xFF00E676, 0xFFB46BFF, 0xFFFFC83D, 0xFF4D8DFF};
    /** Tema aplikasi (indeks THEME_*). */
    static int theme(Context c) {
        int t = prefs(c).getInt("theme", 0);
        return t < 0 || t >= THEME_ACCENT.length ? 0 : t;
    }
    static void setTheme(Context c, int t) { prefs(c).edit().putInt("theme", t).apply(); }
    /** Tema HUD: -1 = ikut tema aplikasi. */
    static int hudTheme(Context c) {
        int t = prefs(c).getInt("hudTheme", -1);
        return t >= THEME_ACCENT.length ? -1 : t;
    }
    static void setHudTheme(Context c, int t) { prefs(c).edit().putInt("hudTheme", t).apply(); }
    static int hudAccent(Context c) {
        int t = hudTheme(c);
        return THEME_ACCENT[t >= 0 ? t : theme(c)];
    }
    /** Skala HUD 0.6 .. 1.6 (1.0 = ukuran bawaan). */
    static float hudScale(Context c) {
        float v = prefs(c).getFloat("hudScale", 1f);
        return v < 0.6f ? 0.6f : v > 1.6f ? 1.6f : v;
    }
    static void setHudScale(Context c, float v) { prefs(c).edit().putFloat("hudScale", v).apply(); }

    /** Data grafik sesi terakhir: "f=1,2,3;t=40,41,42". */
    static String graph(Context c) { return prefs(c).getString("graph", ""); }
    static void setGraph(Context c, String g) { prefs(c).edit().putString("graph", g).apply(); }

    // ---- resolusi game + CPU cap ----
    /** Persen resolusi layar selama main (0 = mati). Default 85. Cuma dipakai kalau game dibuka lewat launcher app ini. */
    static int resPct(Context c) { return prefs(c).getInt("resPct", 85); }
    static void setResPct(Context c, int v) { prefs(c).edit().putInt("resPct", v).apply(); }
    static boolean resApplied(Context c) { return prefs(c).getBoolean("resApplied", false); }
    /** CPU Cap: batasi clock max CPU ~75% selama Game Mode (panas turun, GPU tidak ikut throttle). Default nyala. */
    static boolean capOn(Context c) { return prefs(c).getBoolean("cap", true); }
    static void setCap(Context c, boolean on) {
        prefs(c).edit().putBoolean("cap", on).apply();
        capSync(c);
    }
    private static boolean capActive(Context c) { return capOn(c) && gameOn(c); }

    // ---- GPU Max (floor GPU 680 tanpa kunci CPU), margin GED (eksperimen) ----
    static boolean gpuMaxOn(Context c) { return prefs(c).getBoolean("gpuMax", true); }
    static void setGpuMax(Context c, boolean on) {
        prefs(c).edit().putBoolean("gpuMax", on).apply();
        if (gameOn(c) || lockOn(c)) mtkGpuSync(c);
    }
    static boolean marginOn(Context c) { return prefs(c).getBoolean("margin", false); }
    static void setMargin(Context c, boolean on) {
        prefs(c).edit().putBoolean("margin", on).apply();
        mtkMargin(c);
    }
    /** True selama GPU dikunci max lewat Game Mode saja (tanpa Lock) dan belum diturunkan karena panas. */
    static boolean gpuMaxActive(Context c) {
        return gameOn(c) && !lockOn(c) && gpuMaxOn(c) && !prefs(c).getBoolean("gpuHot", false);
    }
    /** Tahap 1 panas: lepas Lock (kalau ada) dan turunkan floor GPU satu OPP. Game Mode tetap jalan. */
    static void stepDown(Context c) {
        prefs(c).edit().putBoolean("gpuHot", true).apply();
        if (lockOn(c)) setLock(c, false); else mtkGpuSync(c);
    }

    static boolean charging(Context c) {
        Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return i != null && i.getIntExtra("plugged", 0) != 0;
    }

    // ---- profil per game (tahan kartu game di launcher) ----
    static final String[] BUILTIN = {"com.mobile.legends", "com.dts.freefireth", "com.dts.freefiremax"};

    /** Game populer lain: otomatis muncul di Lobby kalau terpasang. {package, nama tag, warna aksen} */
    static final String[][] KNOWN = {
        {"com.tencent.ig", "PUBG MOBILE", "#F2A900"},
        {"com.pubg.imobile", "BATTLEGROUNDS MOBILE", "#F2A900"},
        {"com.activision.callofduty.shooter", "FPS  \u2022  CALL OF DUTY", "#7BD34A"},
        {"com.garena.game.codm", "FPS  \u2022  CODM GARENA", "#7BD34A"},
        {"com.miHoYo.GenshinImpact", "OPEN WORLD RPG", "#4DD0E1"},
        {"com.HoYoverse.hkrpgoversea", "TURN-BASED RPG", "#B46BFF"},
        {"com.HoYoverse.Nap", "ACTION RPG", "#FF9A3D"},
        {"com.kurogame.wutheringwaves.global", "OPEN WORLD ACTION", "#5AA9FF"},
        {"com.riotgames.league.wildrift", "MOBA  \u2022  WILD RIFT", "#3D8BFF"},
        {"com.levelinfinite.sgameGlobal", "MOBA  \u2022  HONOR OF KINGS", "#E8555C"},
        {"com.garena.game.df", "FPS  \u2022  DELTA FORCE", "#8BC34A"},
    };

    static boolean isKnown(String pkg) {
        for (String[] k : KNOWN) if (k[0].equals(pkg)) return true;
        return false;
    }

    static boolean isGame(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        for (String b : BUILTIN) if (b.equals(pkg)) return true;
        if (isKnown(pkg)) return true;
        return customs(c).contains(pkg);
    }
    private static String pk(String pkg, String k) { return "pf_" + pkg + "_" + k; }
    /** Lock Clock: default mati (panas), kecuali profil game bilang nyala. */
    static boolean profLock(Context c, String pkg) { return prefs(c).getBoolean(pk(pkg, "lock"), false); }
    /** Sisanya default ikut setelan global. */
    static boolean profKill(Context c, String pkg) { return prefs(c).getBoolean(pk(pkg, "kill"), killOn(c)); }
    static boolean profDnd(Context c, String pkg) { return prefs(c).getBoolean(pk(pkg, "dnd"), dndOn(c)); }
    static boolean profRr(Context c, String pkg) { return prefs(c).getBoolean(pk(pkg, "rr"), rrOn(c)); }
    static void setProf(Context c, String pkg, boolean lock, boolean kill, boolean dnd, boolean rr) {
        prefs(c).edit().putBoolean(pk(pkg, "lock"), lock).putBoolean(pk(pkg, "kill"), kill)
            .putBoolean(pk(pkg, "dnd"), dnd).putBoolean(pk(pkg, "rr"), rr).apply();
    }

    /** Notifikasi hasil aksi: baris 1 = ringkasan, sisanya muncul pas notifnya dibuka melebar. */
    static void notifyResult(Context c, String title, String text) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("result", "Hasil aksi", NotificationManager.IMPORTANCE_HIGH));
        nm.notify(4, new Notification.Builder(c, "result")
            .setSmallIcon(R.drawable.icon_vallen)
            .setContentTitle(title)
            .setContentText(text.split("\n")[0])
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true).setTimeoutAfter(15000).build());
    }

    // ---- game custom ----
    static Set<String> customs(Context c) {
        return new HashSet<>(prefs(c).getStringSet("custom", new HashSet<String>()));
    }
    static void addCustom(Context c, String pkg) {
        Set<String> s = customs(c);
        s.add(pkg);
        prefs(c).edit().putStringSet("custom", s).apply();
    }
    static void removeCustom(Context c, String pkg) {
        Set<String> s = customs(c);
        s.remove(pkg);
        prefs(c).edit().putStringSet("custom", s).apply();
    }

    // ---- app yang tidak boleh dibunuh ----
    static Set<String> keeps(Context c) {
        return new HashSet<>(prefs(c).getStringSet("keep", new HashSet<String>()));
    }
    static void setKeeps(Context c, Set<String> s) {
        prefs(c).edit().putStringSet("keep", new HashSet<>(s)).apply();
    }

    // ---- baterai (tanpa root) ----
    /** Suhu baterai dalam persepuluh derajat (404 = 40.4 C). */
    static int temp(Context c) {
        Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return i == null ? 0 : i.getIntExtra("temperature", 0);
    }
    static int battery(Context c) {
        Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return -1;
        int l = i.getIntExtra("level", -1), s = i.getIntExtra("scale", 100);
        return l < 0 || s <= 0 ? -1 : l * 100 / s;
    }

    /** Sesi sekarang pakai boost? (false = sesi A/B pembanding). */
    static boolean sessBoost(Context c) { return prefs(c).getBoolean("sessBoost", true); }

    static boolean isRoot() {
        root = "0".equals(RootShell.run("id -u"));
        return root;
    }

    /** True kalau prefs bilang boost aktif tapi service pemantau tidak jalan (reboot / proses dibunuh). */
    static boolean orphaned(Context c) {
        // lagi proses buka game (resolusi baru diganti, activity bisa dibuat ulang): jangan dianggap sisa boost
        if (System.currentTimeMillis() - prefs(c).getLong("launchAt", 0) < 120_000) return false;
        return !BoostService.running && (gameOn(c) || lockOn(c) || !session(c).isEmpty() || resApplied(c));
    }

    // ---- aksi ----
    /** Dipanggil sebelum game dibuka (cepat): governor, refresh rate, DND. Kill background dipanggil terpisah. */
    static void prepareLaunch(Context c, String gamePkg) { prepareLaunch(c, gamePkg, false); }

    /** allowRes = true cuma dari launcher app (game belum dibuka). Ganti resolusi pas game jalan bikin game restart. */
    static void prepareLaunch(Context c, String gamePkg, boolean allowRes) { prepareLaunch(c, gamePkg, allowRes, null); }

    /** Callback progres buat layar loading di app (pct 0..100). */
    interface Prog { void step(int pct, String label); void phase(String title); void check(boolean ok, String label); }
    private static void st(Prog p, int pct, String label) { if (p != null) p.step(pct, label); }

    static void prepareLaunch(Context c, String gamePkg, boolean allowRes, Prog pg) {
        st(pg, 5, "Menyiapkan sesi game");
        prefs(c).edit().putLong("launchAt", System.currentTimeMillis()).commit();   // penanda: lagi proses buka game
        // A/B: sesi ini sengaja TANPA boost (HUD + laporan tetap jalan) buat dibandingkan di Riwayat
        boolean noBoost = abNext(c);
        prefs(c).edit().putBoolean("sessBoost", !noBoost).putBoolean("abNext", false).commit();
        if (!noBoost && allowRes) { st(pg, 15, "Menyesuaikan resolusi"); resApply(c); }
        setSession(c, gamePkg);
        prefs(c).edit().remove("protected").commit();
        if (noBoost) { st(pg, 100, "Mode A/B: tanpa boost"); return; }
        st(pg, 35, "CPU & GPU ke mode performa");
        setGame(c, true);
        if (profLock(c, gamePkg)) { st(pg, 55, "Mengunci clock"); setLock(c, true); }
        if (profRr(c, gamePkg)) { st(pg, 68, "Kunci refresh rate"); rrApply(c); }
        if (profDnd(c, gamePkg)) { st(pg, 78, "Mode jangan ganggu"); dndApply(c); }
        st(pg, 88, "Game mode");
        gameModeApply(c, gamePkg);
        st(pg, 92, "Merapikan memori");
        RootShell.run("echo 1 > /proc/sys/vm/compact_memory 2>/dev/null");
        st(pg, 100, "Memeriksa hasil boost");
        verifyLaunch(c, gamePkg, allowRes, pg);
        // drop_caches sengaja tidak dipanggil: bikin load game lebih lambat. killBackground dipanggil dari launcher.
    }

    /** Baca ulang setelan sistem (satu panggilan root) dan laporkan mana yang beneran nyala. Cuma item yang diaktifkan di profil. */
    private static void verifyLaunch(Context c, String pkg, boolean allowRes, Prog pg) {
        if (pg == null) return;
        pg.phase("MEMERIKSA BOOST");
        boolean lock = profLock(c, pkg), rr = profRr(c, pkg), dnd = profDnd(c, pkg);
        boolean res = allowRes && resApplied(c);
        String[] g = gpu(c);
        String big = "mx=0; for d in /sys/devices/system/cpu/cpu[0-9]*/cpufreq; do v=$(cat $d/cpuinfo_max_freq 2>/dev/null || echo 0); "
            + "[ \"$v\" -gt \"$mx\" ] && mx=$v; done; bd=; for d in /sys/devices/system/cpu/cpu[0-9]*/cpufreq; do "
            + "[ \"$(cat $d/cpuinfo_max_freq 2>/dev/null)\" = \"$mx\" ] && { bd=$d; break; }; done; ";
        StringBuilder cmd = new StringBuilder(big);
        cmd.append("echo CPU=$(cat $bd/scaling_governor 2>/dev/null); ");
        cmd.append("echo LOCK=$(cat $bd/scaling_min_freq 2>/dev/null)/$(cat $bd/scaling_max_freq 2>/dev/null); ");
        if (g != null) cmd.append("echo GPU=$(cat '").append(g[0]).append("/governor' 2>/dev/null); ");
        cmd.append("echo GM=$(cmd -l 2>/dev/null | grep -c '^ *game$'); ");
        cmd.append("echo RR=$(settings get system min_refresh_rate)/$(settings get system peak_refresh_rate); ");
        cmd.append("echo DND=$(settings get global zen_mode); ");
        cmd.append("echo WM=$(wm size | grep -c Override)");
        String o = RootShell.run(cmd.toString());
        if (o == null) { pg.check(false, "Gagal membaca hasil (root timeout)"); return; }
        java.util.HashMap<String, String> kv = new java.util.HashMap<>();
        for (String l : o.split("\n")) {
            int i = l.indexOf('=');
            if (i > 0) kv.put(l.substring(0, i).trim(), l.substring(i + 1).trim());
        }
        String cpu = kv.get("CPU");
        if (cpu != null && !cpu.isEmpty())
            pg.check(cpu.equals("performance") || capActive(c), "CPU big: " + cpu + (capActive(c) ? " (CPU cap)" : ""));
        if (g != null) {
            String gv = kv.get("GPU");
            pg.check("performance".equals(gv), "GPU: " + (gv == null || gv.isEmpty() ? "tidak kebaca" : gv));
        }
        pg.check("1".equals(kv.get("GM")), "Game mode: perintah dikirim");
        if (lock && !capActive(c)) {
            String lk = kv.get("LOCK") == null ? "" : kv.get("LOCK");
            String[] ab = lk.split("/");
            pg.check(ab.length == 2 && !ab[0].isEmpty() && ab[0].equals(ab[1]), "Lock clock: " + lk.replace('/', '-'));
        }
        if (rr) {
            if (prefs(c).getBoolean("rrApplied", false)) {
                String v = kv.get("RR") == null ? "" : kv.get("RR");
                String[] ab = v.split("/");
                pg.check(ab.length == 2 && ab[0].equals(ab[1]) && ab[0].matches("[0-9.]+"), "Refresh rate: " + v.replace("/", " / "));
            } else {
                pg.check(true, "Refresh rate: layar 60Hz, tidak perlu");
            }
        }
        if (dnd) {
            String z = kv.get("DND");
            pg.check(z != null && z.matches("[1-9]"), "Jangan ganggu: " + ("0".equals(z) ? "mati" : "aktif"));
        }
        if (res) pg.check("1".equals(kv.get("WM")), "Resolusi: " + resPct(c) + "%");
    }

    static void setGame(Context c, boolean on) {
        if (on) {
            prefs(c).edit().remove("gpuHot").commit();
            snapshot(c);
            adReset();
            int lvl = adaptOn(c) ? 2 : 3;                           // adaptif: mulai di tengah, lalu dituning dari FPS
            prefs(c).edit().putInt("tuneLvl", lvl).commit();
            // performance cuma di cluster tercepat (big); cluster kecil tetap governor asli biar tidak boros panas
            String cmd = "mx=0; for d in /sys/devices/system/cpu/cpu[0-9]*/cpufreq; do v=$(cat $d/cpuinfo_max_freq 2>/dev/null || echo 0); "
                + "[ \"$v\" -gt \"$mx\" ] && mx=$v; done; "
                + "for d in /sys/devices/system/cpu/cpu[0-9]*/cpufreq; do [ \"$(cat $d/cpuinfo_max_freq 2>/dev/null)\" = \"$mx\" ] "
                + "&& echo performance > $d/scaling_governor; done; echo cubic > " + TCP
                + "; " + tuneCmd(lvl) + "; echo 1 > " + TA_IDLE + " 2>/dev/null"
                // swappiness diturunkan ke 20 selama main (nilai asli disimpan snapshot, dibalikin pas boost mati)
                + "; sw=$(cat " + SWAP + " 2>/dev/null); [ \"${sw:-0}\" -gt 20 ] && echo 20 > " + SWAP + " 2>/dev/null";
            String[] g = gpu(c);
            if (g != null) cmd += "; echo performance > '" + g[0] + "/governor' 2>/dev/null";
            RootShell.run(cmd);
            if (wifiLlOn(c)) {
                prefs(c).edit().putBoolean("wllApplied", true).commit();
                RootShell.run("cmd wifi force-low-latency-mode enabled 2>/dev/null");
            }
        } else {
            restoreGovernor(c);
            if (prefs(c).getBoolean("wllApplied", false)) {
                RootShell.run("cmd wifi force-low-latency-mode disabled 2>/dev/null");
                prefs(c).edit().putBoolean("wllApplied", false).commit();
            }
        }
        prefs(c).edit().putBoolean("game", on).commit();
        capSync(c);
        mtkGpuSync(c);
        mtkPpmSync(c);
        mtkGedMode(c);
        mtkMargin(c);
        clearIfIdle(c);
    }

    /** Lock: CPU min = max, GPU min = max, dan paksa clock/bus/rail Adreno tetap nyala. */
    static void setLock(Context c, boolean on) {
        if (on) {
            snapshot(c);
            // CPU Cap aktif = CPU tidak dikunci max (bertentangan), cuma GPU yang dikunci
            String cmd = capActive(c) ? ":" : EACH + "cat $d/scaling_max_freq > $d/scaling_min_freq; done";
            String[] g = gpu(c);
            if (g != null) cmd += "; cat '" + g[0] + "/max_freq' > '" + g[0] + "/min_freq' 2>/dev/null";
            cmd += "; for f in force_clk_on force_bus_on force_rail_on; do echo 1 > " + KGSL + "/$f 2>/dev/null; done";
            RootShell.run(cmd);
        } else {
            restoreMinFreq(c);
        }
        prefs(c).edit().putBoolean("lock", on).commit();
        mtkGpuSync(c);
        mtkPpmSync(c);
        mtkGedMode(c);
        mtkMargin(c);
        clearIfIdle(c);
    }

    // ---- tuning grup top-app per level (0 = ringan ... 3 = max seperti versi lama) ----
    private static final int[][] LV = {{10, 30}, {30, 50}, {60, 75}, {100, 100}};

    private static String tuneCmd(int lvl) {
        int l = Math.max(0, Math.min(3, lvl));
        String clamp = l == 3 ? "max" : LV[l][1] + ".00";
        return "echo " + LV[l][0] + " > " + TA_BOOST + " 2>/dev/null; echo " + clamp + " > " + TA_CLAMP + " 2>/dev/null";
    }
    static int tuneLevel(Context c) { return prefs(c).getInt("tuneLvl", 2); }
    static void setTuneLevel(Context c, int lvl) {
        lvl = Math.max(0, Math.min(3, lvl));
        prefs(c).edit().putInt("tuneLvl", lvl).commit();
        RootShell.run(tuneCmd(lvl));
    }

    // ---- mode adaptif: naik/turun level berdasarkan FPS vs target + suhu chip ----
    private static int adLow, adHigh, adCool, adPeak;
    private static void adReset() { adLow = adHigh = adCool = adPeak = 0; }

    /** Dipanggil service tiap detik selama game jalan. Cepat: cuma nulis sysfs kalau level berubah. */
    static void adaptTick(Context c, int fps, int chip, int chipMax) {
        if (!adaptOn(c) || !gameOn(c) || lockOn(c)) return;
        if (adCool > 0) adCool--;
        if (fps <= 5) { adLow = adHigh = 0; return; }                // menu / loading: jangan dijadikan patokan
        adPeak = Math.max(adPeak, fps);
        int t = fpsTarget(c);
        int target = t <= 0 ? adPeak : Math.min(t, adPeak);           // game yang di-cap 30 tidak dikejar ke 60
        int lvl = tuneLevel(c);
        boolean warm = chip >= 0 && chip >= chipMax - 4;
        if (warm && lvl > 0 && adCool == 0) {
            setTuneLevel(c, lvl - 1);
            adCool = 6; adLow = adHigh = 0;
            return;
        }
        if (fps < target * 0.92f) { adLow++; adHigh = 0; }
        else if (fps >= target * 0.97f) { adHigh++; adLow = 0; }
        else { adLow = adHigh = 0; }
        if (adLow >= 3 && adCool == 0 && lvl < 3 && !warm) {
            setTuneLevel(c, lvl + 1);
            adCool = 4; adLow = 0;
        } else if (adHigh >= 12 && adCool == 0 && lvl > 0) {
            setTuneLevel(c, lvl - 1);
            adCool = 4; adHigh = 0;
        }
    }

    // ---- GameManager Android 12+ ----
    private static void gameModeApply(Context c, String pkg) {
        if (!gmodeOn(c) || !pkg.matches("[A-Za-z0-9_.]+")) return;
        prefs(c).edit().putString("gmPkg", pkg).commit();
        RootShell.run("cmd game mode performance " + pkg + " 2>/dev/null || cmd game set --mode performance " + pkg + " 2>/dev/null");
    }
    private static void gameModeRestore(Context c) {
        String pkg = prefs(c).getString("gmPkg", "");
        if (pkg.isEmpty() || !pkg.matches("[A-Za-z0-9_.]+")) return;
        RootShell.run("cmd game mode standard " + pkg + " 2>/dev/null || cmd game set --mode standard " + pkg + " 2>/dev/null");
        prefs(c).edit().remove("gmPkg").commit();
    }

    /** Optimasi sekali (compile ART speed-profile) buat kurangi stutter. Lama: jalan di proses su sendiri. */
    static String compileGame(Context c, String pkg) {
        if (!pkg.matches("[A-Za-z0-9_.]+")) return "Nama paket tidak valid";
        String o = RootShell.oneShot("cmd package compile -m speed-profile -f " + pkg, 240_000);
        if (o == null) return "Gagal (su tidak tersedia)";
        return o.isEmpty() ? "Selesai" : o.split("\n")[0];
    }

    /** Matikan semua boost dan akhiri sesi. */
    static void restoreAll(Context c) { restoreAll(c, false); }

    /** keepRes = true: resolusi tidak dikembalikan (dipakai pas boost dimatiin karena panas tapi game masih jalan). */
    static void restoreAll(Context c, boolean keepRes) {
        prefs(c).edit().remove("gpuHot").commit();
        String g = session(c);
        if (!g.isEmpty()) unprotectGame(c, g);
        gameModeRestore(c);
        rrRestore(c);
        setSession(c, "");
        setLock(c, false);
        setGame(c, false);
        dndRestore(c);
        if (!keepRes) resRestore(c);
    }

    static void dropCaches() { RootShell.run("sync; echo 3 > /proc/sys/vm/drop_caches"); }

    /** RAM kosong (MB), sama seperti angka "tersedia" di Pengaturan. */
    static int availMb(Context c) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
        return (int) (mi.availMem >> 20);
    }

    /** Bersihkan RAM, balikin ringkasan yang jelas buat notifikasi (baris 1 = hasil utama). */
    static String cleanRam(Context c) {
        int before = availMb(c);
        String sess = session(c);
        StringBuilder how = new StringBuilder();
        RootShell.run("echo 1 > /proc/sys/vm/compact_memory 2>/dev/null");
        how.append("\u2022 Memori didefrag (compact)");
        if (sess.isEmpty()) {
            dropCaches();
            how.append("\n\u2022 Cache sistem dibuang");
        } else {
            how.append("\n\u2022 Cache tidak dibuang (lagi main, biar tidak patah-patah)");
        }
        if (killOn(c)) {
            killBackground(c, sess);
            how.append(smartKillOn(c)
                ? "\n\u2022 App background berat dimatikan pintar (am kill, bukan force-stop; kecuali game, launcher, keyboard, daftar pengecualian)"
                : "\n\u2022 App background di-force-stop (kecuali game, launcher, keyboard, daftar pengecualian)");
        } else {
            how.append("\n\u2022 Bunuh App Background OFF di setelan, app lain tidak disentuh");
        }
        try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
        int after = availMb(c);
        int d = after - before;
        String head = String.format(Locale.US, "RAM kosong %.1f GB \u2192 %.1f GB (%s%.1f GB)",
            before / 1024f, after / 1024f, d >= 0 ? "+" : "-", Math.abs(d) / 1024f);
        return head + "\n" + how;
    }

    /** Matikan semua boost + kembalikan setelan asli, balikin daftar apa saja yang dikembalikan. */
    static String resetAll(Context c) {
        SharedPreferences p = prefs(c);
        List<String> done = new ArrayList<>();
        if (gameOn(c)) done.add("Game Mode dimatikan, governor CPU/GPU dikembalikan");
        if (lockOn(c)) done.add("Lock Clock dilepas, frekuensi min dikembalikan");
        if (p.getBoolean("rrApplied", false)) done.add("Refresh rate dikembalikan");
        if (p.getBoolean("dndActive", false)) done.add("Mode Jangan Ganggu dikembalikan");
        if (resApplied(c)) done.add("Resolusi layar dikembalikan");
        if (!p.getString("capOrig", "").isEmpty()) done.add("CPU Cap dilepas, clock max dikembalikan");
        if (!session(c).isEmpty()) done.add("Sesi game diakhiri, proteksi game dilepas, HUD ikut mati");
        restoreAll(c);
        if (done.isEmpty()) return "Tidak ada boost yang aktif\nSemua sudah normal, tidak ada yang perlu dikembalikan";
        StringBuilder sb = new StringBuilder("Semua dikembalikan ke normal");
        for (String l : done) sb.append("\n\u2022 ").append(l);
        return sb.toString();
    }

    /**
     * Bersihkan app background. Mode pintar (default): `am kill` (bukan force-stop, jadi alarm / notif / job app tidak ikut
     * hilang), cuma untuk app yang lagi jalan dan makan RAM >= 50 MB. Mode lama: force-stop semua app pihak ketiga.
     * Pengecualian: game, launcher, keyboard, app ini, manager root, dan daftar pengecualian user.
     */
    static void killBackground(Context c, String keep) {
        StringBuilder pat = new StringBuilder("*magisk*|*kernelsu*|*apatch*|*supersu*");
        List<String> protect = new ArrayList<>(Arrays.asList(c.getPackageName(), keep, launcherPkg(c), imePkg(c)));
        protect.addAll(keeps(c));
        for (String p : protect) {
            if (p != null && p.matches("[A-Za-z0-9_.]+")) pat.append('|').append(p);
        }
        String body;
        if (smartKillOn(c)) {
            body = "pid=$(pidof $p | cut -d' ' -f1); [ -z \"$pid\" ] && continue; "
                 + "kb=$(grep -m1 VmRSS /proc/$pid/status 2>/dev/null | tr -dc 0-9); "
                 + "[ \"${kb:-0}\" -ge 51200 ] && am kill $p";
        } else {
            body = "am force-stop $p";
        }
        RootShell.metric("for p in $(pm list packages -3 | cut -d: -f2); do case \"$p\" in "
            + pat + ") ;; *) " + body + " ;; esac; done; am kill-all", 45_000);   // sesi metrik: jangan menahan sesi kontrol (restore darurat)
    }

    // ---- DND ----
    private static void dndApply(Context c) {
        if (prefs(c).getBoolean("dndActive", false)) return;
        String z = RootShell.run("settings get global zen_mode");
        prefs(c).edit().putString("zen", z == null ? "0" : z.trim()).putBoolean("dndActive", true).commit();
        RootShell.run("cmd notification set_dnd priority");
    }
    private static void dndRestore(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("dndActive", false)) return;
        if ("0".equals(p.getString("zen", "0"))) RootShell.run("cmd notification set_dnd off");
        p.edit().putBoolean("dndActive", false).commit();
    }

    // ---- snapshot & restore ----
    /** Simpan governor + min freq asli (CPU dan GPU) sekali, supaya bisa dibalikin persis. */
    private static void snapshot(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getString("orig", "").isEmpty()) return;
        String s = RootShell.run(EACH + "echo \"$d $(cat $d/scaling_governor) $(cat $d/scaling_min_freq)\"; done");
        String t = RootShell.run("cat " + TCP);
        String gline = "";
        String dir = RootShell.run(GPU_FIND);
        if (dir != null && safeP(dir)) {
            String gv = RootShell.run("cat '" + dir + "/governor' 2>/dev/null");
            String mn = RootShell.run("cat '" + dir + "/min_freq' 2>/dev/null");
            if (gv != null && !gv.isEmpty()) gline = dir + " " + gv.trim() + " " + (mn == null ? "" : mn.trim());
        }
        String tune = RootShell.run("echo B $(cat " + TA_BOOST + " 2>/dev/null); echo I $(cat " + TA_IDLE
            + " 2>/dev/null); echo U $(cat " + TA_CLAMP + " 2>/dev/null); echo S $(cat " + SWAP + " 2>/dev/null)");
        p.edit().putString("orig", s).putString("tcp", t).putString("gpu", gline)
            .putString("tune", tune == null ? "" : tune).commit();
    }

    private static void clearIfIdle(Context c) {
        if (!gameOn(c) && !lockOn(c)) {
            prefs(c).edit().remove("orig").remove("tcp").remove("gpu").remove("tune").commit();
        }
    }

    /** {dir, governor, minfreq?} atau null. */
    private static String[] gpu(Context c) {
        String[] f = prefs(c).getString("gpu", "").trim().split(" ");
        return f.length >= 2 && safeP(f[0]) && safe(f[1]) ? f : null;
    }

    private static boolean safe(String s) { return s.matches("[\\w/.\\-]+"); }
    /** Path devfreq boleh mengandung , : @ (contoh 3d00000.qcom,kgsl-3d0). */
    private static boolean safeP(String s) { return s.matches("[\\w/.,:@\\-]+"); }

    private static void restoreGovernor(Context c) {
        SharedPreferences p = prefs(c);
        String orig = p.getString("orig", "");
        if (orig.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (String l : orig.split("\n")) {
            String[] f = l.trim().split(" ");
            if (f.length < 3 || !safe(f[0]) || !safe(f[1])) continue;
            if (f[1].equals("performance")) {
                // governor awal performance = sisa script lama: balik ke governor normal yang TERSEDIA di kernel ini
                String d = f[0];
                sb.append("a=\" $(cat ").append(d).append("/scaling_available_governors 2>/dev/null) \"; ")
                  .append("case \"$a\" in *\" schedutil \"*) g=schedutil;; *\" walt \"*) g=walt;; *\" sched \"*) g=sched;; ")
                  .append("*\" interactive \"*) g=interactive;; *\" ondemand \"*) g=ondemand;; *) g=performance;; esac; ")
                  .append("echo $g > ").append(d).append("/scaling_governor; ");
            } else {
                sb.append("echo ").append(f[1]).append(" > ").append(f[0]).append("/scaling_governor; ");
            }
        }
        String[] g = gpu(c);
        if (g != null && !g[1].equals("performance")) {
            sb.append("echo ").append(g[1]).append(" > '").append(g[0]).append("/governor' 2>/dev/null; ");
        }
        for (String l : p.getString("tune", "").split("\n")) {
            String[] f = l.trim().split(" ");
            if (f.length < 2 || !safe(f[1])) continue;
            String file = f[0].equals("B") ? TA_BOOST : f[0].equals("I") ? TA_IDLE
                        : f[0].equals("U") ? TA_CLAMP : f[0].equals("S") ? SWAP : null;
            if (file != null) sb.append("echo ").append(f[1]).append(" > ").append(file).append(" 2>/dev/null; ");
        }
        String tcp = p.getString("tcp", "cubic");
        if (safe(tcp)) sb.append("echo ").append(tcp).append(" > ").append(TCP);
        RootShell.run(sb.toString());
    }

    private static void restoreMinFreq(Context c) {
        String orig = prefs(c).getString("orig", "");
        if (orig.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (String l : orig.split("\n")) {
            String[] f = l.trim().split(" ");
            if (f.length < 3 || !safe(f[0]) || !safe(f[2])) continue;
            sb.append("echo ").append(f[2]).append(" > ").append(f[0]).append("/scaling_min_freq; ");
        }
        String[] g = gpu(c);
        if (g != null && g.length >= 3 && g[2].matches("\\d+")) {
            sb.append("echo ").append(g[2]).append(" > '").append(g[0]).append("/min_freq' 2>/dev/null; ");
        }
        sb.append("for f in force_clk_on force_bus_on force_rail_on; do echo 0 > ")
          .append(KGSL).append("/$f 2>/dev/null; done");
        RootShell.run(sb.toString());
    }

    // ---- GPU MediaTek (GED) ----
    /** Lantai frekuensi GPU (kHz). Default = OPP terendah; kalau dinaikkan, GPU tidak turun di bawahnya. */
    private static final String GED_FLOOR = "/sys/module/ged/parameters/gpu_cust_boost_freq";
    private static final String GPU_OPP = "/proc/gpufreq/gpufreq_opp_dump";
    private static final Pattern OPP_FREQ = Pattern.compile("freq = (\\d+)");

    /** Daftar OPP GPU (kHz), terbesar dulu. Kosong kalau bukan MediaTek. */
    private static List<Integer> mtkOpps() {
        List<Integer> l = new ArrayList<>();
        String o = RootShell.run("cat " + GPU_OPP + " 2>/dev/null");
        if (o == null) return l;
        Matcher m = OPP_FREQ.matcher(o);
        while (m.find()) {
            try { l.add(Integer.parseInt(m.group(1))); } catch (NumberFormatException ignored) {}
        }
        Collections.sort(l, Collections.reverseOrder());
        return l;
    }

    private static final String GED_HAL_BOOST = "/sys/kernel/ged/hal/custom_boost_gpu_freq";
    private static final String GPU_KEEP = "/proc/gpufreq/gpufreq_opp_freq";
    private static final String GPU_VAR = "/proc/gpufreq/gpufreq_var_dump";

    /** Lock Clock = lantai GPU di OPP tertinggi, Game Mode = satu OPP di bawahnya, mati = nilai asli. */
    private static void mtkGpuSync(Context c) {
        SharedPreferences p = prefs(c);
        // Lock = OPP tertinggi; Game Mode = OPP tertinggi juga kalau GPU Max aktif (belum kepanasan), kalau tidak satu di bawahnya
        int level = lockOn(c) ? 0 : gameOn(c) ? (gpuMaxOn(c) && !p.getBoolean("gpuHot", false) ? 0 : 1) : -1;
        if (level < 0) {
            gpuRelease(c);
            return;
        }
        List<Integer> opp = mtkOpps();
        if (opp.isEmpty()) return;
        if (p.getString("mtkFloor", "").isEmpty()) {                 // simpan nilai asli sekali
            String cur = RootShell.run("cat " + GED_FLOOR + " 2>/dev/null");
            if (cur == null || !cur.trim().matches("\\d+")) return;
            p.edit().putString("mtkFloor", cur.trim()).commit();
        }
        int f = opp.get(Math.min(level, opp.size() - 1));
        if (f < 100_000) return;
        String how = gpuForce(c, f, opp);
        p.edit().putString("gpuNote", how + " " + (f / 1000) + "MHz").commit();
    }

    /** Clock GPU aktual (kHz) dari var_dump, -1 kalau tidak kebaca. */
    private static int curGpuKhz() {
        String o = RootShell.run("grep -ihoE 'g_cur_opp_freq *= *[0-9]+' " + GPU_VAR + " 2>/dev/null | head -n 1");
        if (o == null) return -1;
        Matcher m = Pattern.compile("(\\d+)\\s*$").matcher(o.trim());
        try { return m.find() ? Integer.parseInt(m.group(1)) : -1; } catch (NumberFormatException e) { return -1; }
    }

    /** Tunggu sebentar lalu cek clock GPU aktual benar-benar sudah di f atau lebih. */
    private static boolean gpuAtLeast(int f) {
        try { Thread.sleep(350); } catch (InterruptedException ignored) {}
        return curGpuKhz() >= f - 1000;
    }

    /**
     * Paksa GPU minimal di f kHz dan BUKTIKAN lewat clock aktual. Urutan: (1) param GED, (2) node hal GED (index OPP),
     * (3) keep-OPP /proc/gpufreq. Di HP ini param GED saja terbukti tidak menahan clock, jadi langkah 2-3 jadi cadangan.
     * Return nama metode yang berhasil ("param" / "hal" / "keep") atau "gagal" kalau semuanya tidak mempan.
     */
    private static String gpuForce(Context c, int f, List<Integer> opp) {
        SharedPreferences p = prefs(c);
        RootShell.run("echo " + f + " > " + GED_FLOOR + " 2>/dev/null");
        if (p.getBoolean("gpuKeep", false)) {                        // keep-OPP sudah aktif: tinggal ganti angkanya
            RootShell.run("echo " + f + " > " + GPU_KEEP + " 2>/dev/null");
            return "keep";
        }
        if (gpuAtLeast(f)) return "param";
        int idx = opp.indexOf(f);
        if (idx >= 0) {
            if (p.getString("mtkHal", "").isEmpty()) {
                String h = RootShell.run("cat " + GED_HAL_BOOST + " 2>/dev/null");
                if (h != null && h.trim().matches("\\d+")) p.edit().putString("mtkHal", h.trim()).commit();
            }
            if (!p.getString("mtkHal", "").isEmpty()) {
                RootShell.run("echo " + idx + " > " + GED_HAL_BOOST + " 2>/dev/null; echo " + f + " > " + GED_FLOOR + " 2>/dev/null");
                if (gpuAtLeast(f)) return "hal";
            }
        }
        p.edit().putBoolean("gpuKeep", true).commit();                // simpan dulu supaya pasti dilepas nanti
        RootShell.run("echo " + f + " > " + GPU_KEEP + " 2>/dev/null");
        return gpuAtLeast(f) ? "keep" : "gagal";
    }

    /** Lepas semua paksaan GPU dan balikin nilai asli. */
    private static void gpuRelease(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getBoolean("gpuKeep", false)) {
            RootShell.run("echo 0 > " + GPU_KEEP + " 2>/dev/null");
            p.edit().remove("gpuKeep").commit();
        }
        String hal = p.getString("mtkHal", "");
        if (hal.matches("\\d+")) RootShell.run("echo " + hal + " > " + GED_HAL_BOOST + " 2>/dev/null");
        String orig = p.getString("mtkFloor", "");
        if (orig.matches("\\d+")) RootShell.run("echo " + orig + " > " + GED_FLOOR + " 2>/dev/null");
        p.edit().remove("mtkHal").remove("mtkFloor").remove("gpuNote").commit();
    }

    // ---- MediaTek: PPM + GED tambahan ----
    private static final String PPM_STATUS = "/proc/ppm/policy_status";
    private static final String GED_GAME_MODE = "/sys/module/ged/parameters/gx_game_mode";

    /**
     * Lock Clock nyala: matikan policy PPM yang cuma motong daya (3 = PWR_THRO, 5 = DLPT).
     * Policy THERMAL (4) dan PTPOD (0) SENGAJA tetap nyala. Dilepas lagi pas Lock mati / boost direstore.
     */
    static void mtkPpmSync(Context c) {
        SharedPreferences p = prefs(c);
        String done = p.getString("ppmOff", "");
        // proteksi daya jangan dimatikan kalau baterai tipis dan tidak dicas (HP bisa mati mendadak pas peak)
        int bat = battery(c);
        boolean lowBat = bat >= 0 && bat < 20 && !charging(c);
        if (lockOn(c) && !lowBat) {
            if (!done.isEmpty()) return;
            String st = RootShell.run("cat " + PPM_STATUS + " 2>/dev/null");
            if (st == null || st.isEmpty()) return;
            StringBuilder off = new StringBuilder();
            for (String idx : new String[]{"3", "5"}) {
                if (st.matches("(?s).*\\[" + idx + "\\] \\S+: enabled.*")) {
                    RootShell.run("echo " + idx + " 0 > " + PPM_STATUS + " 2>/dev/null");
                    off.append(idx);
                }
            }
            if (off.length() > 0) p.edit().putString("ppmOff", off.toString()).commit();
        } else if (!done.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (char ch : done.toCharArray()) {
                if (Character.isDigit(ch)) sb.append("echo ").append(ch).append(" 1 > ").append(PPM_STATUS).append(" 2>/dev/null; ");
            }
            RootShell.run(sb.toString());
            p.edit().remove("ppmOff").commit();
        }
    }

    /** gx_game_mode = 1 selama Game Mode / Lock nyala (GED lebih agresif naikin clock GPU), balik ke nilai asli pas mati. */
    private static void mtkGedMode(Context c) {
        SharedPreferences p = prefs(c);
        String orig = p.getString("gedMode", "");
        boolean want = gameOn(c) || lockOn(c);
        if (want && orig.isEmpty()) {
            String cur = RootShell.run("cat " + GED_GAME_MODE + " 2>/dev/null");
            if (cur == null || !cur.trim().matches("\\d+")) return;   // bukan MediaTek GED
            p.edit().putString("gedMode", cur.trim()).commit();
            RootShell.run("echo 1 > " + GED_GAME_MODE + " 2>/dev/null");
        } else if (!want && !orig.isEmpty()) {
            if (orig.matches("\\d+")) RootShell.run("echo " + orig + " > " + GED_GAME_MODE + " 2>/dev/null");
            p.edit().remove("gedMode").commit();
        }
    }

    /**
     * EKSPERIMEN (default mati): naikin margin DVFS GED sedikit (maks +30) supaya GPU lebih cepat naik clock.
     * Nilai asli disimpan dan dibalikin persis. Cuma jalan kalau isi file angka biasa 1..300.
     */
    private static final String[][] MARGINS = {
        {"mgA", "/sys/kernel/ged/hal/dvfs_margin_value"},
        {"mgB", "/sys/kernel/ged/hal/timer_base_dvfs_margin"}};

    private static void mtkMargin(Context c) {
        SharedPreferences p = prefs(c);
        boolean want = marginOn(c) && (gameOn(c) || lockOn(c));
        for (String[] m : MARGINS) {
            String orig = p.getString(m[0], "");
            if (want && orig.isEmpty()) {
                String cur = RootShell.run("cat " + m[1] + " 2>/dev/null");
                if (cur == null || !cur.trim().matches("\\d{1,3}")) continue;
                long v = Long.parseLong(cur.trim());
                if (v < 1 || v > 300) continue;
                p.edit().putString(m[0], cur.trim()).commit();
                RootShell.run("echo " + (v + Math.min(30, Math.max(1, v / 4))) + " > " + m[1] + " 2>/dev/null");
            } else if (!want && !orig.isEmpty()) {
                if (orig.matches("\\d+")) RootShell.run("echo " + orig + " > " + m[1] + " 2>/dev/null");
                p.edit().remove(m[0]).commit();
            }
        }
    }

    // ---- CPU Cap ----
    private static final String CAP_SCRIPT =
        "for d in /sys/devices/system/cpu/cpu[0-9]*/cpufreq; do "
      + "mx=$(cat $d/scaling_max_freq 2>/dev/null); lim=$(( $(cat $d/cpuinfo_max_freq 2>/dev/null || echo 0) * 75 / 100 )); best=0; "
      + "for f in $(cat $d/scaling_available_frequencies 2>/dev/null); do [ \"$f\" -le \"$lim\" ] && [ \"$f\" -gt \"$best\" ] && best=$f; done; "
      + "[ \"$best\" -gt 0 ] && echo \"$d $mx $best\"; done";

    /** Game Mode + CPU Cap: scaling_max tiap cluster = freq tersedia terbesar yang <= 75% max. Nilai asli disimpan, dibalikin pas mati. */
    private static void capSync(Context c) {
        SharedPreferences p = prefs(c);
        boolean want = capActive(c);
        String orig = p.getString("capOrig", "");
        if (want && orig.isEmpty()) {
            String o = RootShell.run(CAP_SCRIPT);
            if (o == null) return;
            StringBuilder cmd = new StringBuilder(), save = new StringBuilder();
            for (String l : o.split("\n")) {
                String[] f = l.trim().split(" ");
                if (f.length < 3 || !safe(f[0]) || !f[1].matches("\\d+") || !f[2].matches("\\d+")) continue;
                if (Long.parseLong(f[2]) >= Long.parseLong(f[1])) continue;
                cmd.append("mn=$(cat ").append(f[0]).append("/scaling_min_freq); [ \"$mn\" -gt ").append(f[2])
                   .append(" ] && echo ").append(f[2]).append(" > ").append(f[0]).append("/scaling_min_freq; echo ")
                   .append(f[2]).append(" > ").append(f[0]).append("/scaling_max_freq; ");
                save.append(f[0]).append(' ').append(f[1]).append(' ').append(f[2]).append('\n');   // path, max asli, cap
            }
            if (save.length() == 0) return;
            p.edit().putString("capOrig", save.toString()).commit();   // simpan dulu supaya aman kalau proses mati
            RootShell.run(cmd.toString());
        } else if (!want && !orig.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String l : orig.split("\n")) {
                String[] f = l.trim().split(" ");
                if (f.length < 2 || !safe(f[0]) || !f[1].matches("\\d+")) continue;
                sb.append("echo ").append(f[1]).append(" > ").append(f[0]).append("/scaling_max_freq; ");
            }
            RootShell.run(sb.toString());
            p.edit().remove("capOrig").commit();
        }
    }

    // ---- resolusi layar (wm size + wm density, density ikut diskala supaya ukuran UI sama) ----
    private static int even(int v) { return v & ~1; }

    private static void resApply(Context c) {
        SharedPreferences p = prefs(c);
        int pct = resPct(c);
        if (pct < 50 || pct >= 100 || p.getBoolean("resApplied", false)) return;
        String o = RootShell.run("wm size; wm density");
        if (o == null) return;
        Matcher ps = Pattern.compile("Physical size: (\\d+)x(\\d+)").matcher(o);
        Matcher os = Pattern.compile("Override size: (\\d+)x(\\d+)").matcher(o);
        Matcher pd = Pattern.compile("Physical density: (\\d+)").matcher(o);
        Matcher od = Pattern.compile("Override density: (\\d+)").matcher(o);
        if (!ps.find() || !pd.find()) return;
        boolean ovS = os.find(), ovD = od.find();
        Matcher sz = ovS ? os : ps;
        int w = Integer.parseInt(sz.group(1)), h = Integer.parseInt(sz.group(2));
        int d = Integer.parseInt((ovD ? od : pd).group(1));
        int nw = even(w * pct / 100), nh = even(h * pct / 100), nd = d * pct / 100;
        if (nw < 360 || nh < 640 || nd < 100) return;
        // simpan setelan asli DULU (reset = tidak ada override sebelumnya), baru ganti
        p.edit().putString("resSize", ovS ? w + "x" + h : "reset")
            .putString("resDens", ovD ? String.valueOf(d) : "reset")
            .putBoolean("resApplied", true).commit();
        RootShell.run("wm size " + nw + "x" + nh + "; wm density " + nd);
        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}   // kasih waktu sistem selesai ganti konfigurasi
    }

    /** Balikin resolusi asli. Aman dipanggil kapan saja (tidak ngapa-ngapain kalau tidak ada yang diganti). */
    static void resRestore(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("resApplied", false)) return;
        String sz = p.getString("resSize", "reset"), dn = p.getString("resDens", "reset");
        String r = RootShell.run((sz.matches("\\d+x\\d+") ? "wm size " + sz : "wm size reset") + "; "
            + (dn.matches("\\d+") ? "wm density " + dn : "wm density reset"));
        if (r != null) p.edit().putBoolean("resApplied", false).commit();   // su gagal = coba lagi nanti
    }

    // ---- refresh rate ----
    /** Kunci min & peak refresh rate ke rate layar tertinggi. HP 60Hz tidak diapa-apakan. */
    private static void rrApply(Context c) {
        if (prefs(c).getBoolean("rrApplied", false)) return;
        String fp = RootShell.run("dumpsys display | grep -o 'fps=[0-9.]*'");
        float max = 0;
        if (fp != null) {
            Matcher m = Pattern.compile("fps=([0-9.]+)").matcher(fp);
            while (m.find()) {
                try { max = Math.max(max, Float.parseFloat(m.group(1))); } catch (Exception ignored) {}
            }
        }
        if (max < 61 || max > 240) return;
        String mn = RootShell.run("settings get system min_refresh_rate");
        String pk = RootShell.run("settings get system peak_refresh_rate");
        prefs(c).edit().putString("rrMin", mn == null ? "null" : mn.trim())
            .putString("rrPeak", pk == null ? "null" : pk.trim()).putBoolean("rrApplied", true).commit();
        int hz = Math.round(max);
        RootShell.run("settings put system min_refresh_rate " + hz + "; settings put system peak_refresh_rate " + hz);
    }

    private static void rrRestore(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("rrApplied", false)) return;
        StringBuilder sb = new StringBuilder();
        String[][] keys = {{"rrMin", "min_refresh_rate"}, {"rrPeak", "peak_refresh_rate"}};
        for (String[] k : keys) {
            String v = p.getString(k[0], "null");
            if (v.matches("[0-9.]+")) sb.append("settings put system ").append(k[1]).append(' ').append(v).append("; ");
            else sb.append("settings delete system ").append(k[1]).append("; ");
        }
        RootShell.run(sb.toString());
        p.edit().putBoolean("rrApplied", false).commit();
    }

    // ---- proteksi proses game ----
    /** Lindungi game dari LMK (oom_score_adj) + prioritas IO. True kalau proses ketemu. */
    static boolean protectGame(Context c, String pkg) {
        if (!pkg.matches("[A-Za-z0-9_.]+")) return false;
        String o = RootShell.run("p=$(pidof " + pkg + " | cut -d' ' -f1); if [ -n \"$p\" ]; then "
            + "echo OOM $(cat /proc/$p/oom_score_adj); echo -800 > /proc/$p/oom_score_adj; "
            + "ionice -c 2 -n 0 -p $p 2>/dev/null; echo OK $p; fi");
        if (o == null || !o.contains("OK ")) return false;
        String oom = "0";
        for (String l : o.split("\n")) {
            if (l.startsWith("OOM ") && l.substring(4).trim().matches("-?\\d+")) oom = l.substring(4).trim();
        }
        prefs(c).edit().putBoolean("protected", true).putString("oom", oom).commit();
        return true;
    }

    private static void unprotectGame(Context c, String pkg) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("protected", false) || !pkg.matches("[A-Za-z0-9_.]+")) return;
        String oom = p.getString("oom", "0");
        if (!oom.matches("-?\\d+")) oom = "0";
        RootShell.run("p=$(pidof " + pkg + " | cut -d' ' -f1); [ -n \"$p\" ] && echo " + oom + " > /proc/$p/oom_score_adj");
        p.edit().putBoolean("protected", false).commit();
    }

    // ---- ping ke server game ----
    /**
     * Cari koneksi TCP ESTABLISHED milik game ke IP publik lewat /proc/net/tcp(6).
     * Port non-web (bukan 80/443) diprioritaskan karena biasanya server game/lobby, bukan CDN.
     * Return {ip, port} atau null. Butuh root. Koneksi UDP (battle) tidak kebaca.
     */
    static String[] findGameServer(Context c, String pkg, Set<String> exclude) {
        int uid;
        try { uid = c.getPackageManager().getApplicationInfo(pkg, 0).uid; } catch (Exception e) { return null; }
        String o = RootShell.metric("cat /proc/net/tcp /proc/net/tcp6 2>/dev/null; echo @@UDP@@; cat /proc/net/udp /proc/net/udp6 2>/dev/null");
        if (o == null || o.isEmpty()) return null;
        String want = String.valueOf(uid);
        // urutan prioritas: UDP terhubung (battle) > TCP non-web (lobby) > TCP web (CDN)
        String udp = null, game = null, web = null;
        int udpPort = 0, gamePort = 0, webPort = 0;
        boolean inUdp = false;
        for (String line : o.split("\n")) {
            if (line.contains("@@UDP@@")) { inUdp = true; continue; }
            String[] f = line.trim().split("\\s+");
            if (f.length < 8 || !f[7].equals(want) || !f[3].equals("01")) continue;   // TCP ESTABLISHED / UDP yang sudah connect()
            int colon = f[2].indexOf(':');
            if (colon < 0) continue;
            int port;
            try { port = Integer.parseInt(f[2].substring(colon + 1), 16); } catch (NumberFormatException e) { continue; }
            String ip = hexIp(f[2].substring(0, colon));
            if (ip == null || port <= 0 || !publicIp(ip) || (exclude != null && exclude.contains(ip))) continue;
            if (inUdp) { if (udp == null) { udp = ip; udpPort = port; } }
            else if (port != 443 && port != 80) { if (game == null) { game = ip; gamePort = port; } }
            else if (web == null) { web = ip; webPort = port; }
        }
        if (udp != null) return new String[]{udp, String.valueOf(udpPort)};
        if (game != null) return new String[]{game, String.valueOf(gamePort)};
        if (web != null) return new String[]{web, String.valueOf(webPort)};
        return null;
    }

    private static String hexIp(String h) {
        try {
            if (h.length() == 8) return v4(h);
            if (h.length() == 32 && h.startsWith("0000000000000000FFFF0000")) return v4(h.substring(24));
        } catch (Exception ignored) {}
        return null;                                          // IPv6 murni tidak dipakai
    }

    /** /proc/net menulis IPv4 sebagai hex little-endian. */
    private static String v4(String h) {
        return Integer.parseInt(h.substring(6, 8), 16) + "." + Integer.parseInt(h.substring(4, 6), 16) + "."
             + Integer.parseInt(h.substring(2, 4), 16) + "." + Integer.parseInt(h.substring(0, 2), 16);
    }

    private static boolean publicIp(String ip) {
        String[] p = ip.split("\\.");
        int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
        if (a == 0 || a == 10 || a == 127 || a >= 224) return false;
        if (a == 169 && b == 254) return false;
        if (a == 172 && b >= 16 && b <= 31) return false;
        if (a == 192 && b == 168) return false;
        if (a == 100 && b >= 64 && b <= 127) return false;   // CGNAT
        return true;
    }

    // ---- daftar game dikenal (buat Auto Boost tanpa dumpsys) ----
    static List<String> gameList(Context c) {
        List<String> l = new ArrayList<>(Arrays.asList(BUILTIN));
        for (String[] k : KNOWN) l.add(k[0]);
        l.addAll(customs(c));
        return l;
    }

    // ---- riwayat sesi (20 terakhir) + perbandingan A/B ----
    /** Baris: waktu|pkg|boost(1/0)|detik|avgFps|low1|puncakChip|puncakBat10|throttle%|bat0|bat1 */
    static void addHistory(Context c, String pkg, boolean boost, long sec, double avg, double low,
                           int chipPeak, int bat10, int thrPct, int bat0, int bat1) {
        if (pkg == null || !pkg.matches("[A-Za-z0-9_.]+")) return;
        String line = System.currentTimeMillis() + "|" + pkg + "|" + (boost ? 1 : 0) + "|" + sec + "|"
            + Math.round(avg) + "|" + Math.round(low) + "|" + chipPeak + "|" + bat10 + "|" + thrPct + "|" + bat0 + "|" + bat1;
        String old = prefs(c).getString("hist", "");
        String[] rows = old.isEmpty() ? new String[0] : old.split("\n");
        StringBuilder sb = new StringBuilder(line);
        for (int i = 0; i < rows.length && i < 19; i++) sb.append('\n').append(rows[i]);
        prefs(c).edit().putString("hist", sb.toString()).commit();
    }

    static void clearHistory(Context c) { prefs(c).edit().remove("hist").commit(); }

    private static String label(Context c, String pkg) {
        try {
            return String.valueOf(c.getPackageManager().getApplicationInfo(pkg, 0).loadLabel(c.getPackageManager()));
        } catch (Exception e) {
            int i = pkg.lastIndexOf('.');
            return i >= 0 ? pkg.substring(i + 1) : pkg;
        }
    }

    /** Teks riwayat + ringkasan A/B per game (kalau ada sesi dengan DAN tanpa boost). */
    static String historyText(Context c) {
        String h = prefs(c).getString("hist", "");
        if (h.isEmpty()) return "Belum ada riwayat. Main minimal 30 detik dari launcher / Auto Boost dengan HUD nyala.";
        java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("dd/MM HH:mm", Locale.US);
        StringBuilder sb = new StringBuilder();
        java.util.Map<String, double[]> on = new java.util.LinkedHashMap<>(), off = new java.util.LinkedHashMap<>();
        for (String l : h.split("\n")) {
            String[] f = l.split("\\|");
            if (f.length < 11) continue;
            try {
                long sec = Long.parseLong(f[3]);
                sb.append(df.format(new java.util.Date(Long.parseLong(f[0])))).append("  ").append(label(c, f[1]))
                  .append(f[2].equals("1") ? "  BOOST" : "  TANPA BOOST").append('\n')
                  .append(String.format(Locale.US, "   %dm%02ds  avg %s  low %s  chip %s\u00b0  bat %.1f\u00b0  throttle %s%%  %s%%\u2192%s%%\n",
                      sec / 60, sec % 60, f[4], f[5], f[6], Integer.parseInt(f[7]) / 10f, f[8], f[9], f[10]));
                java.util.Map<String, double[]> m = f[2].equals("1") ? on : off;
                double[] a = m.get(f[1]);
                if (a == null) { a = new double[5]; m.put(f[1], a); }
                a[0] += Double.parseDouble(f[4]); a[1] += Double.parseDouble(f[5]);
                a[2] += Double.parseDouble(f[6]); a[3] += Double.parseDouble(f[8]); a[4] += 1;
            } catch (Exception ignored) {}
        }
        StringBuilder ab = new StringBuilder();
        for (String pkg : on.keySet()) {
            double[] x = on.get(pkg), y = off.get(pkg);
            if (y == null || x[4] == 0 || y[4] == 0) continue;
            double xa = x[0] / x[4], ya = y[0] / y[4], xl = x[1] / x[4], yl = y[1] / y[4];
            ab.append("A/B ").append(label(c, pkg)).append(" (").append((int) x[4]).append(" boost vs ").append((int) y[4]).append(" tanpa)\n")
              .append(String.format(Locale.US, "   FPS avg %.0f vs %.0f (%s%.0f%%)\n", xa, ya, xa >= ya ? "+" : "", ya > 0 ? 100 * (xa - ya) / ya : 0))
              .append(String.format(Locale.US, "   1%% low %.0f vs %.0f (%s%.0f%%)\n", xl, yl, xl >= yl ? "+" : "", yl > 0 ? 100 * (xl - yl) / yl : 0))
              .append(String.format(Locale.US, "   puncak chip %.0f\u00b0 vs %.0f\u00b0  throttle %.0f%% vs %.0f%%\n",
                  x[2] / x[4], y[2] / y[4], x[3] / x[4], y[3] / y[4]));
        }
        return (ab.length() > 0 ? ab + "\n" : "Tips A/B: nyalakan 'Sesi berikutnya tanpa boost', main sekali, lalu main lagi dengan boost, supaya ada pembanding.\n\n")
            + sb.toString().trim();
    }

    /** CSV buat disalin ke spreadsheet. */
    static String historyCsv(Context c) {
        StringBuilder sb = new StringBuilder("waktu,paket,boost,detik,fps_avg,fps_1low,chip_puncak,bat_puncak_x10,throttle_pct,bat_awal,bat_akhir\n");
        String h = prefs(c).getString("hist", "");
        if (!h.isEmpty()) for (String l : h.split("\n")) sb.append(l.replace('|', ',')).append('\n');
        return sb.toString();
    }

    // ---- backup / restore setelan (JSON lewat clipboard) ----
    /** Key setelan murni yang aman dipulihkan (tanpa efek ke sistem). Toggle yang menyentuh sistem tidak ikut. */
    private static final String[] BK_KEYS = {
        "hud", "kill", "dnd", "rr", "chip", "limit", "adapt", "fpsTarget", "wifiLl", "gmode", "smartKill",
        "eco", "pingGame", "pingAlert", "preCheck", "hudX", "hudY", "theme", "hudTheme", "hudScale", "resPct", "custom", "keep"};
    private static final Set<String> BK_BOOL = new HashSet<>(Arrays.asList(
        "hud", "kill", "dnd", "rr", "adapt", "wifiLl", "gmode", "smartKill", "eco", "pingGame", "pingAlert", "preCheck"));
    private static final Pattern BK_PROF = Pattern.compile("pf_[A-Za-z0-9_.]+_(lock|kill|dnd|rr)");
    private static final Pattern BK_PKG = Pattern.compile("[A-Za-z0-9_.]+");

    private static boolean bkAllowed(String k) {
        for (String a : BK_KEYS) if (a.equals(k)) return true;
        return BK_PROF.matcher(k).matches();
    }

    /** Semua setelan + profil game jadi satu teks JSON. */
    static String exportSettings(Context c) {
        try {
            org.json.JSONObject root = new org.json.JSONObject();
            org.json.JSONObject p = new org.json.JSONObject();
            for (java.util.Map.Entry<String, ?> e : prefs(c).getAll().entrySet()) {
                String k = e.getKey();
                Object v = e.getValue();
                if (!bkAllowed(k) || v == null) continue;
                org.json.JSONArray a = new org.json.JSONArray();
                if (v instanceof Boolean) { a.put("b"); a.put(v); }
                else if (v instanceof Integer) { a.put("i"); a.put(v); }
                else if (v instanceof Float) { a.put("f"); a.put((double) (Float) v); }
                else if (v instanceof Set) {
                    org.json.JSONArray s = new org.json.JSONArray();
                    for (Object o : (Set<?>) v) s.put(String.valueOf(o));
                    a.put("s"); a.put(s);
                } else continue;
                p.put(k, a);
            }
            root.put("app", "vallen-boost");
            root.put("v", 1);
            root.put("p", p);
            return root.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Pulihkan dari teks JSON hasil exportSettings. Return jumlah setelan yang dipulihkan, -1 kalau teks tidak valid. */
    static int importSettings(Context c, String json) {
        try {
            org.json.JSONObject root = new org.json.JSONObject(json.trim());
            if (!"vallen-boost".equals(root.optString("app"))) return -1;
            org.json.JSONObject p = root.getJSONObject("p");
            SharedPreferences.Editor ed = prefs(c).edit();
            int n = 0;
            java.util.Iterator<String> it = p.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (!bkAllowed(k)) continue;
                org.json.JSONArray a = p.optJSONArray(k);
                if (a == null || a.length() < 2) continue;
                String t = a.optString(0);
                if (t.equals("b") && !BK_PROF.matcher(k).matches() && !BK_BOOL.contains(k)) continue;
                if (t.equals("b")) ed.putBoolean(k, a.getBoolean(1));
                else if (t.equals("i")) {
                    int v = a.getInt(1);
                    if (k.equals("theme") && (v < 0 || v >= THEME_ACCENT.length)) continue;
                    if (k.equals("hudTheme") && (v < -1 || v >= THEME_ACCENT.length)) continue;
                    if (k.equals("resPct") && (v < 50 || v > 100)) continue;
                    if (k.equals("fpsTarget") && (v < 0 || v > 240)) continue;
                    if ((k.equals("chip") || k.equals("limit")) && (v < 30 || v > 110)) continue;
                    ed.putInt(k, v);
                } else if (t.equals("f")) {
                    float v = (float) a.getDouble(1);
                    if (k.equals("hudScale")) v = Math.max(0.6f, Math.min(1.6f, v));
                    else if (v < -0.1f || v > 1.1f) continue;      // hudX / hudY = fraksi layar
                    ed.putFloat(k, v);
                } else if (t.equals("s")) {
                    org.json.JSONArray s = a.getJSONArray(1);
                    Set<String> out = new HashSet<>();
                    for (int i = 0; i < s.length() && i < 500; i++) {
                        String pkg = s.getString(i);
                        if (BK_PKG.matcher(pkg).matches()) out.add(pkg);
                    }
                    ed.putStringSet(k, out);
                } else continue;
                n++;
            }
            ed.apply();
            return n;
        } catch (Exception e) {
            return -1;
        }
    }

    // ---- helper package ----
    private static String launcherPkg(Context c) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = c.getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        return ri == null ? null : ri.activityInfo.packageName;
    }
    private static String imePkg(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        int i = s == null ? -1 : s.indexOf('/');
        return i > 0 ? s.substring(0, i) : null;
    }
}

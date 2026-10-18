package com.vallen.boost;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Statistik live: CPU, GPU, RAM, suhu, FPS. Satu instance dipakai satu thread saja. */
final class Metrics {
    static final class Snap {
        int cpuPct = -1, cpuMhz = -1, cpuTemp = -1;
        int gpuPct = -1, gpuMhz = -1, gpuTemp = -1;
        int ramUsedMb, ramTotalMb;
        int batTemp10, batPct;
        int fps = -1, ping = -1;
        boolean cpuThr, gpuThr;            // ditahan sistem (thermal / power limit)
        String gov = "-", tcp = "-";
    }

    private static final Pattern NUM = Pattern.compile("\\d+");
    private static final Pattern FOCUS = Pattern.compile("([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+\\}");

    private static final String GPU_FREQ =
        "/sys/class/kgsl/kgsl-3d0/gpuclk /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq /sys/class/kgsl/kgsl-3d0/clock_mhz "
      + "/sys/class/devfreq/*kgsl*/cur_freq /sys/class/devfreq/*mali*/cur_freq /sys/class/devfreq/*gpu*/cur_freq "
      + "/sys/devices/platform/*mali*/devfreq/*/cur_freq /sys/kernel/gpu/gpu_clock /sys/kernel/ged/hal/current_freq";
    private static final String GPU_LOAD =
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage /sys/class/kgsl/kgsl-3d0/devfreq/gpu_load "
      + "/sys/class/devfreq/*mali*/load /sys/kernel/gpu/gpu_busy /sys/kernel/ged/hal/gpu_utilization "
      + "/sys/module/ged/parameters/gpu_loading /proc/mali/utilization /sys/devices/platform/*mali*/utilization";
    private static final String FUNCS =
        "first() { for f in \"$@\"; do [ -r \"$f\" ] || continue; v=$(cat \"$f\" 2>/dev/null); "
      +     "[ -n \"$v\" ] && { echo \"$v\"; return; }; done; }; "
      + "firstnz() { for f in \"$@\"; do [ -r \"$f\" ] || continue; v=$(cat \"$f\" 2>/dev/null); "
      +     "[ -n \"$v\" ] && [ \"$v\" != 0 ] && { echo \"$v\"; return; }; done; }; ";

    /** Satu panggilan root = semua data. Tiap baris diawali tag 1 huruf. */
    private static final String SCRIPT = FUNCS
      + "echo S $(head -n 1 /proc/stat); "
      + "echo G $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null); "
      + "echo P $(cat " + Boost.TCP + " 2>/dev/null); "
      + "for d in /sys/devices/system/cpu/cpu[0-9]*/cpufreq; do echo F $(cat $d/scaling_cur_freq 2>/dev/null); done; "
      + "for z in /sys/class/thermal/thermal_zone*; do echo T $(cat $z/type 2>/dev/null)=$(cat $z/temp 2>/dev/null); done; "
      + "echo C $(firstnz " + GPU_FREQ + "); "
      + "echo L $(first " + GPU_LOAD + "); "
      + "echo M $(grep -ihoE 'g_cur_opp_freq *= *[0-9]+' /proc/gpufreq/gpufreq_var_dump 2>/dev/null | head -n 1); "
      + "echo K $(first /sys/class/kgsl/kgsl-3d0/temp); "
      + "echo B $(cat /sys/class/kgsl/kgsl-3d0/gpubusy 2>/dev/null); "
      + "echo Q $(grep -ihoE 'g_max_limited_idx *= *[0-9]+' /proc/gpufreq/gpufreq_var_dump 2>/dev/null | head -n 1); "
      + "for d in /sys/devices/system/cpu/cpu[0-9]*/cpufreq; do echo W $d $(cat $d/scaling_max_freq 2>/dev/null) $(cat $d/cpuinfo_max_freq 2>/dev/null); done";

    private final Context ctx;
    private long prevTotal, prevIdle;
    static volatile String why = "";      // alasan FPS kosong, ditampilkan di HUD buat debug
    private String layer, layerPkg;
    private final java.util.ArrayList<String> cands = new java.util.ArrayList<>();
    private int candIdx;
    private long layerTry;
    private long frameLast;                 // timestamp frame terakhir yang sudah dicatat
    boolean recordFrames;                  // diisi service: true hanya saat ada sesi game
    final FrameLog frames = new FrameLog();

    /** Log frametime (mikrodetik) untuk satu sesi. Aman dipakai lintas thread. */
    static final class FrameLog {
        private int[] a = new int[4096];
        private int n;
        synchronized void add(int us) {
            if (n == a.length) {
                if (n >= 1_000_000) return;
                a = Arrays.copyOf(a, n * 2);
            }
            a[n++] = us;
        }
        synchronized void clear() { n = 0; }
        synchronized int[] copy() { return Arrays.copyOf(a, n); }
    }

    Metrics(Context c) { ctx = c.getApplicationContext(); }

    // ---- sampel utama ----
    Snap sample(boolean useRoot) {
        Snap s = new Snap();
        ram(s);
        s.batTemp10 = Boost.temp(ctx);
        s.batPct = Boost.battery(ctx);
        if (!useRoot) return s;

        String r = RootShell.metric(SCRIPT);
        if (r == null) return s;
        long maxKhz = 0;
        int cpuT = -1, socT = -1, gpuT = -1, kT = -1, busyPct = -1, gpuLim = 0;
        boolean cpuThr = false;
        java.util.Map<String, Long> caps = capMap();
        String mLine = null;
        for (String l : r.split("\n")) {
            if (l.length() < 3 || l.charAt(1) != ' ') continue;
            char k = l.charAt(0);
            String v = l.substring(2).trim();
            if (v.isEmpty()) continue;
            switch (k) {
                case 'S': cpuLoad(v, s); break;
                case 'G': s.gov = v; break;
                case 'P': s.tcp = v; break;
                case 'F': maxKhz = Math.max(maxKhz, num(v)); break;
                case 'C': s.gpuMhz = gpuMhz(maxNum(v)); break;
                case 'L': { long p = firstNum(v); if (p >= 0 && p <= 100) s.gpuPct = (int) p; break; }
                case 'M': mLine = v; break;
                case 'Q': gpuLim = (int) maxNum(v); break;      // g_max_limited_idx > 0 = GPU dibatasi kernel
                case 'W': {                                       // scaling_max di bawah yang seharusnya = CPU dibatasi sistem
                    String[] q = v.split("\\s+");
                    if (q.length < 3) break;
                    long cur = num(q[1]), top = num(q[2]);
                    Long cap = caps.get(q[0]);                    // CPU Cap buatan app ini bukan throttle sistem
                    if (cap != null && cap > 0) top = cap;
                    if (cur > 0 && top > 0 && cur < top) cpuThr = true;
                    break;
                }
                case 'K': kT = celsius(num(v)); break;
                case 'B': {
                    String[] q = v.split("\\s+");
                    if (q.length >= 2) {
                        long a = num(q[0]), bb = num(q[1]);
                        if (bb > 0) busyPct = (int) Math.min(100, a * 100 / bb);
                    }
                    break;
                }
                case 'T': {
                    int i = v.lastIndexOf('=');
                    if (i <= 0) break;
                    String type = v.substring(0, i).toLowerCase(Locale.US);
                    int t = celsius(num(v.substring(i + 1)));
                    if (t < 0) break;
                    if (type.contains("gpu")) gpuT = Math.max(gpuT, t);
                    else if (type.contains("cpu") || type.contains("cluster") || type.contains("big")) cpuT = Math.max(cpuT, t);
                    else if (type.contains("soc") || type.startsWith("tsens") || type.equals("mtktsap")) socT = Math.max(socT, t);
                    break;
                }
                default: break;
            }
        }
        if (maxKhz > 0) s.cpuMhz = (int) (maxKhz / 1000);
        if (mLine != null) {                     // MediaTek: var_dump paling bisa dipercaya
            int m = gpuMhz(maxNum(mLine));
            if (m > 0) s.gpuMhz = m;
        }
        s.cpuTemp = cpuT >= 0 ? cpuT : socT;
        s.gpuTemp = gpuT >= 0 ? gpuT : kT >= 0 ? kT : socT;   // MT6765 tidak punya sensor GPU, pakai suhu SoC (mtktsAP)
        if (s.gpuPct < 0 && busyPct >= 0) s.gpuPct = busyPct;
        s.cpuThr = cpuThr;
        s.gpuThr = gpuLim > 0;
        return s;
    }

    /** Cap CPU buatan app ini: path cpufreq -> clock max yang kita tetapkan (dari prefs capOrig). */
    private java.util.Map<String, Long> capMap() {
        java.util.Map<String, Long> m = new java.util.HashMap<>();
        for (String l : Boost.prefs(ctx).getString("capOrig", "").split("\n")) {
            String[] f = l.trim().split(" ");
            if (f.length >= 3) m.put(f[0], num(f[2]));
        }
        return m;
    }

    /** RAM terpakai = MemTotal - MemAvailable (sama seperti angka di Pengaturan). */
    private void ram(Snap s) {
        long total = 0, avail = 0;
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String l;
            int got = 0;
            while (got < 2 && (l = br.readLine()) != null) {
                if (l.startsWith("MemTotal:")) { total = firstNum(l); got++; }
                else if (l.startsWith("MemAvailable:")) { avail = firstNum(l); got++; }
            }
        } catch (Exception ignored) {}
        if (total > 0 && avail > 0) {
            s.ramTotalMb = (int) (total / 1024);
            s.ramUsedMb = (int) ((total - avail) / 1024);
            return;
        }
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
        s.ramTotalMb = (int) (mi.totalMem >> 20);
        s.ramUsedMb = (int) ((mi.totalMem - mi.availMem) >> 20);
    }

    /** Laporan sensor mentah: chipset, file GPU yang ada + isinya, daftar thermal zone. */
    String diagnose() {
        String r = RootShell.metric(
            "echo '== chip'; getprop ro.product.model; getprop ro.board.platform; getprop ro.hardware; "
          + "getprop ro.soc.model; getprop ro.build.version.release; "
          + "echo '== gpu freq'; for f in " + GPU_FREQ + "; do [ -e $f ] && echo \"$f = $(cat $f 2>&1 | head -c 60)\"; done; "
          + "echo '== gpu load'; for f in " + GPU_LOAD + "; do [ -e $f ] && echo \"$f = $(cat $f 2>&1 | head -c 60)\"; done; "
          + "echo '== kgsl'; for f in temp gpubusy; do [ -e /sys/class/kgsl/kgsl-3d0/$f ] && echo \"$f = $(cat /sys/class/kgsl/kgsl-3d0/$f 2>&1 | head -c 40)\"; done; "
          + "echo '== devfreq'; ls /sys/class/devfreq 2>&1; "
          + "echo '== mtk ls'; for d in /proc/gpufreq /proc/cpufreq /proc/ppm /proc/ppm/policy /sys/kernel/ged "
          +     "/sys/kernel/ged/hal /sys/module/ged/parameters; do [ -d $d ] && { echo \"[$d]\"; ls $d 2>&1; }; done; "
          + "echo '== gpufreq_var_dump'; head -n 30 /proc/gpufreq/gpufreq_var_dump 2>&1; "
          + "echo '== gpufreq_opp_freq'; head -n 15 /proc/gpufreq/gpufreq_opp_freq 2>&1; "
          + "echo '== ppm'; for f in /proc/ppm/policy/userlimit_min_cpu_freq /proc/ppm/policy/userlimit_max_cpu_freq "
          +     "/proc/ppm/policy/hard_userlimit_min_cpu_freq /proc/ppm/policy/hard_userlimit_max_cpu_freq "
          +     "/proc/ppm/policy_status /proc/ppm/enabled; do echo \"[$f]\"; head -n 12 $f 2>&1; done; "
          + "echo '== cpufreq'; for p in 0 4; do d=/sys/devices/system/cpu/cpu$p/cpufreq; "
          +     "echo \"cpu$p gov=$(cat $d/scaling_governor 2>&1) min=$(cat $d/scaling_min_freq 2>&1)\"; "
          +     "echo \"max=$(cat $d/scaling_max_freq 2>&1) avail=$(cat $d/scaling_available_frequencies 2>&1)\"; done; "
          + "echo '== ged'; for f in /sys/kernel/ged/hal/current_freqency /sys/kernel/ged/hal/total_gpu_freq_level_count "
          +     "/sys/kernel/ged/hal/custom_boost_gpu_freq /sys/kernel/ged/hal/custom_upbound_gpu_freq "
          +     "/sys/kernel/ged/hal/gpu_boost_level /sys/module/ged/parameters/gpu_cust_boost_freq "
          +     "/sys/module/ged/parameters/gpu_cust_upbound_freq /sys/module/ged/parameters/gx_game_mode "
          +     "/sys/kernel/ged/hal/dvfs_margin_value /sys/kernel/ged/hal/timer_base_dvfs_margin "
          +     "/sys/module/ged/parameters/gx_fb_dvfs_margin /proc/sys/vm/swappiness; "
          +     "do echo \"$f = $(cat $f 2>&1 | head -c 60)\"; done; "
          + "echo '== gpufreq_opp_dump'; head -n 20 /proc/gpufreq/gpufreq_opp_dump 2>&1; "
          + "echo '== power'; for d in /sys/class/power_supply/*; do "
          +     "echo \"[$d] type=$(cat $d/type 2>&1) status=$(cat $d/status 2>&1) online=$(cat $d/online 2>&1)\"; done; "
          + "echo '== battery uevent'; head -n 25 /sys/class/power_supply/battery/uevent 2>&1; "
          + "echo '== battery ls'; ls /sys/class/power_supply/battery 2>&1; "
          + "echo '== charge nodes'; for f in /sys/class/power_supply/battery/input_suspend "
          +     "/sys/class/power_supply/battery/charging_enabled /sys/class/power_supply/battery/stop_charging "
          +     "/sys/class/power_supply/battery/store_mode /sys/class/power_supply/battery/batt_slate_mode "
          +     "/sys/class/power_supply/battery/battery_cmd/en_power_path /sys/class/power_supply/battery/battery_cmd/power_path_en "
          +     "/sys/class/power_supply/battery/battery_cmd/current_cmd /sys/class/power_supply/battery/battery_cmd/input_cmd "
          +     "/sys/devices/platform/charger/sw_jeita /sys/devices/platform/charger/charger_log_level; "
          +     "do [ -e $f ] && echo \"$f = $(cat $f 2>&1 | head -c 40)\"; done; "
          + "echo '== mtk_battery_cmd'; ls /proc/mtk_battery_cmd 2>&1; "
          + "echo '== charger platform'; ls /sys/devices/platform/charger /sys/devices/platform/battery 2>&1 | head -n 40; "
          + "echo '== wm'; wm size; wm density; "
          + "echo '== thermal'; for z in /sys/class/thermal/thermal_zone*; do echo \"$(cat $z/type 2>/dev/null)=$(cat $z/temp 2>/dev/null)\"; done");
        return r == null ? "su tidak tersedia" : r;
    }

    private void cpuLoad(String v, Snap s) {
        String[] t = v.split("\\s+");
        if (t.length < 5) return;
        long total = 0;
        for (int i = 1; i < Math.min(t.length, 9); i++) total += num(t[i]);
        long idle = num(t[4]) + (t.length > 5 ? num(t[5]) : 0);
        if (prevTotal > 0 && total > prevTotal) {
            double busy = (total - prevTotal) - (idle - prevIdle);
            s.cpuPct = (int) Math.max(0, Math.min(100, Math.round(100.0 * busy / (total - prevTotal))));
        }
        prevTotal = total;
        prevIdle = idle;
    }

    // ---- FPS (butuh root: dumpsys SurfaceFlinger) ----
    /**
     * Cara cepat tanpa dumpsys: app yang lagi di depan pasti proses-nya ada di cpuset "top-app".
     * pkg != "" : return pkg kalau game itu di top-app, "?" kalau tidak (atau prosesnya belum ada), "" kalau tidak kebaca.
     */
    String foregroundOf(String pkg) {
        if (pkg == null || !pkg.matches("[A-Za-z0-9_.]+")) return "";
        String o = RootShell.metric("p=$(pidof " + pkg + " | cut -d' ' -f1); if [ -z \"$p\" ]; then echo NOPID; else "
            + "c=$(cat /proc/$p/cpuset 2>/dev/null); case \"$c\" in *top-app*) echo TOP;; \"\") echo UNK;; *) echo BG;; esac; fi");
        if (o == null) return "";
        if (o.equals("TOP")) return pkg;
        if (o.equals("BG") || o.equals("NOPID")) return "?";
        return foreground();                                  // cpuset tidak kebaca di kernel ini: pakai dumpsys
    }

    /** Dari daftar game yang dikenal, mana yang lagi di top-app (satu panggilan root). "" kalau tidak ada / gagal. */
    String foregroundGame(java.util.Collection<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) if (p != null && p.matches("[A-Za-z0-9_.]+")) sb.append(p).append(' ');
        if (sb.length() == 0) return "";
        String o = RootShell.metric("for p in " + sb + "; do pid=$(pidof $p | cut -d' ' -f1); "
            + "[ -n \"$pid\" ] && grep -q top-app /proc/$pid/cpuset 2>/dev/null && { echo $p; break; }; done");
        if (o == null) return "";
        o = o.trim();
        if (!o.isEmpty()) return o;
        // cpuset tidak ada di kernel ini? jangan buta: pakai cara lama
        String t = RootShell.metric("[ -r /proc/self/cpuset ] && echo Y");
        return t != null && t.trim().equals("Y") ? "" : foreground();
    }

    /** Paket app yang lagi fokus, "" kalau gagal. */
    String foreground() {
        String o = RootShell.metric("dumpsys window | grep mCurrentFocus | grep -v null | head -n 1");
        if (o == null) return "";
        Matcher m = FOCUS.matcher(o);
        return m.find() ? m.group(1) : "";
    }

    /** FPS layer game milik pkg. -1 = layer tidak ketemu, 0 = layer diam. */
    int fps(String pkg) {
        if (pkg == null || !pkg.matches("[A-Za-z0-9_.]+")) { layer = null; why = "nopkg"; return -1; }
        if (!pkg.equals(layerPkg)) { layer = null; layerPkg = pkg; layerTry = 0; frameLast = 0; cands.clear(); candIdx = 0; }
        long now = SystemClock.uptimeMillis();
        if (layer == null) {
            if (now - layerTry < 1500) return -1;      // why tetap dari percobaan terakhir
            layerTry = now;
            frameLast = 0;
            if (candIdx >= cands.size()) { findLayers(pkg, cands); candIdx = 0; }
            if (cands.isEmpty()) { why = "nolyr"; return -1; }
            layer = cands.get(candIdx++);
        }
        String o = RootShell.metric("dumpsys SurfaceFlinger --latency '" + layer + "'");
        if (o == null) { why = "su?"; return -1; }
        String[] lines = o.split("\n");
        long[] ts = new long[lines.length];
        int n = 0;
        for (int i = 1; i < lines.length; i++) {             // baris 0 = refresh period
            String[] p = lines[i].trim().split("\\s+");
            if (p.length < 3) continue;
            long a = num(p[1]);                               // actualPresentTime
            if (a <= 0 || a == Long.MAX_VALUE) continue;
            ts[n++] = a;
        }
        if (n < 2) { layer = null; why = "dat" + candIdx + "/" + cands.size(); return -1; }
        Arrays.sort(ts, 0, n);
        if (recordFrames) {                                   // frametime asli, tanpa duplikat antar tick
            for (int i = 0; i < n; i++) {
                if (ts[i] <= frameLast) continue;
                if (frameLast > 0) {
                    long dt = ts[i] - frameLast;
                    if (dt < 500_000_000L) frames.add((int) (dt / 1000));   // jeda > 0,5 dtk = diam (loading / menu)
                }
                frameLast = ts[i];
            }
        }
        long last = 0;
        for (int i = 0; i < n; i++) last = Math.max(last, ts[i]);
        long nowNs = SystemClock.uptimeMillis() * 1_000_000L;
        why = "";
        if (nowNs - last > 1_500_000_000L) return 0;          // frame terakhir sudah basi
        long first = last;
        int cnt = 0;
        for (int i = 0; i < n; i++) {
            if (ts[i] >= last - 1_000_000_000L) { cnt++; first = Math.min(first, ts[i]); }
        }
        if (cnt < 2 || last == first) return 0;
        return (int) Math.min(240, Math.round((cnt - 1) * 1e9 / (last - first)));
    }

    private static void findLayers(String pkg, java.util.List<String> out) {
        out.clear();
        String o = RootShell.metric("dumpsys SurfaceFlinger --list | grep -F '" + pkg + "'");
        if (o == null) return;
        java.util.ArrayList<String> a = new java.util.ArrayList<>(), b = new java.util.ArrayList<>(), c = new java.util.ArrayList<>();
        for (String raw : o.split("\n")) {
            String l = raw.trim();
            if (l.isEmpty() || l.startsWith("Background") || l.indexOf('\'') >= 0) continue;
            if (l.contains("SurfaceView") && l.contains("BLAST")) a.add(l);
            else if (l.contains("SurfaceView")) b.add(l);
            else c.add(l);
        }
        out.addAll(a); out.addAll(b); out.addAll(c);
    }

    // ---- util parsing ----
    private static long num(String s) {
        try { return Long.parseLong(s.trim().split("\\s+")[0]); }
        catch (Exception e) { return 0; }
    }
    private static long firstNum(String s) {
        Matcher m = NUM.matcher(s);
        if (!m.find()) return -1;
        try { return Long.parseLong(m.group()); } catch (Exception e) { return -1; }
    }
    private static long maxNum(String s) {
        Matcher m = NUM.matcher(s);
        long mx = 0;
        while (m.find()) {
            try { mx = Math.max(mx, Long.parseLong(m.group())); } catch (Exception ignored) {}
        }
        return mx;
    }
    /** Sensor kadang milidegree (45000), kadang derajat (45). */
    private static int celsius(long raw) {
        if (raw >= 1000) raw /= 1000;
        return raw >= 10 && raw <= 120 ? (int) raw : -1;
    }
    /** Hz / kHz / MHz -> MHz. */
    private static int gpuMhz(long raw) {
        if (raw <= 0) return -1;
        if (raw >= 10_000_000L) return (int) (raw / 1_000_000L);
        if (raw >= 10_000L) return (int) (raw / 1000L);
        return (int) raw;
    }

    // ---- format teks ----
    static String pct(int v) { return v < 0 ? "--" : v + "%"; }
    static String deg(int c) { return c < 0 ? "-" : c + "\u00b0"; }
    static String freq(int mhz) {
        if (mhz < 0) return "-";
        return mhz >= 1000 ? String.format(Locale.US, "%.1fG", mhz / 1000f) : mhz + "M";
    }
    static String gb(int mb) { return String.format(Locale.US, "%.1f", mb / 1024f); }
}

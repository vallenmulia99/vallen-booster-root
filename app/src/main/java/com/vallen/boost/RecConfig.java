package com.vallen.boost;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Semua setelan perekam layar (prefs "rec") + tabel pilihan + hitung ukuran/bitrate. */
final class RecConfig {
    private RecConfig() {}

    // ---- tabel pilihan (indeks disimpan di prefs) ----
    static final String[] ENGINE_NAMES = {"Otomatis (root kalau ada)", "Root (screenrecord)", "Standar (MediaProjection)"};
    static final String[] RES_NAMES = {"Asli layar", "1080p", "720p", "540p", "480p"};
    static final int[] RES_SHORT = {0, 1080, 720, 540, 480};
    static final String[] FPS_NAMES = {"24 FPS", "30 FPS", "45 FPS", "60 FPS"};
    static final int[] FPS_VALUES = {24, 30, 45, 60};
    static final String[] BITRATE_NAMES = {"Otomatis", "2 Mbps", "4 Mbps", "6 Mbps", "8 Mbps", "12 Mbps", "16 Mbps", "20 Mbps"};
    static final int[] BITRATE_MBPS = {0, 2, 4, 6, 8, 12, 16, 20};
    static final String[] CODEC_NAMES = {"H.264 (aman, ringan)", "HEVC / H.265 (file kecil, lebih berat)"};
    static final String[] AUDIO_NAMES = {"Tanpa suara", "Mikrofon", "Audio internal HP", "Audio internal + Mikrofon"};
    static final String[] ABR_NAMES = {"64 kbps", "96 kbps", "128 kbps", "192 kbps"};
    static final int[] ABR_VALUES = {64, 96, 128, 192};
    static final String[] SAMPLE_NAMES = {"44.1 kHz", "48 kHz"};
    static final int[] SAMPLE_VALUES = {44100, 48000};
    static final String[] COUNTDOWN_NAMES = {"Langsung", "3 detik", "5 detik", "10 detik"};
    static final int[] COUNTDOWN_VALUES = {0, 3, 5, 10};
    static final String[] MAXMIN_NAMES = {"Tanpa batas", "5 menit", "10 menit", "20 menit", "30 menit", "60 menit"};
    static final int[] MAXMIN_VALUES = {0, 5, 10, 20, 30, 60};
    static final String[] MINFREE_NAMES = {"200 MB", "500 MB", "1 GB", "2 GB"};
    static final int[] MINFREE_VALUES = {200, 500, 1000, 2000};
    static final String[] BUBBLE_NAMES = {"Tampil biasa", "Samar", "Sembunyi"};
    static final String[] NAME_NAMES = {"NamaGame_tanggal_jam", "REC_tanggal_jam", "Vallen_tanggal_jam"};
    static final String[] AUTODELAY_NAMES = {"Langsung", "5 detik", "10 detik", "20 detik", "30 detik", "60 detik"};
    static final int[] AUTODELAY_VALUES = {0, 5, 10, 20, 30, 60};
    static final String[] TEMP_NAMES = {"44\u00B0C", "46\u00B0C", "48\u00B0C", "50\u00B0C", "52\u00B0C"};
    static final int[] TEMP_VALUES = {44, 46, 48, 50, 52};
    static final String[] PRESET_NAMES = {
        "Ringan (480p, 30 FPS, 2 Mbps)", "Seimbang (720p, 30 FPS, otomatis)",
        "Tajam (1080p, 30 FPS, otomatis)", "Maksimal (1080p, 60 FPS, 16 Mbps)"};

    // ---- prefs ----
    static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("rec", Context.MODE_PRIVATE);
    }
    static int geti(Context c, String k, int def, int max) {
        int v = p(c).getInt(k, def);
        return v < 0 || v > max ? def : v;
    }
    static void seti(Context c, String k, int v) { p(c).edit().putInt(k, v).apply(); }
    static boolean getb(Context c, String k, boolean def) { return p(c).getBoolean(k, def); }
    static void setb(Context c, String k, boolean v) { p(c).edit().putBoolean(k, v).apply(); }

    static int engine(Context c) { return geti(c, "engine", 0, 2); }
    static int res(Context c) { return geti(c, "res", 2, 4); }
    static int fps(Context c) { return FPS_VALUES[geti(c, "fps", 1, 3)]; }
    static int bitrateIdx(Context c) { return geti(c, "bitrate", 0, 7); }
    static int codec(Context c) { return geti(c, "codec", 0, 1); }
    static int audio(Context c) { return geti(c, "audio", 0, 3); }
    /** Audio internal (suara game/musik langsung dari HP) butuh Android 10+. */
    static boolean internalAudio(Context c) { return Build.VERSION.SDK_INT >= 29 && audio(c) >= 2; }
    static int audioBitrate(Context c) { return ABR_VALUES[geti(c, "abr", 2, 3)] * 1000; }
    static int sampleRate(Context c) { return SAMPLE_VALUES[geti(c, "sample", 1, 1)]; }
    static int countdown(Context c) { return COUNTDOWN_VALUES[geti(c, "countdown", 2, 3)]; }
    static int maxMin(Context c) { return MAXMIN_VALUES[geti(c, "maxmin", 0, 5)]; }
    static int minFreeMb(Context c) { return MINFREE_VALUES[geti(c, "minfree", 1, 3)]; }
    static boolean bubble(Context c) { return getb(c, "bubble", false); }
    static int bubbleMode(Context c) { return geti(c, "bubbleMode", 1, 2); }
    static int nameMode(Context c) { return geti(c, "nameMode", 0, 2); }
    static boolean autoGame(Context c) { return getb(c, "autoGame", false); }
    static int autoDelay(Context c) { return AUTODELAY_VALUES[geti(c, "autoDelay", 2, 5)]; }
    static boolean stopScreenOff(Context c) { return getb(c, "stopOff", true); }
    static boolean showTouches(Context c) { return getb(c, "touches", false); }
    static boolean thermalStop(Context c) { return getb(c, "thermal", true); }
    static int tempStop(Context c) { return TEMP_VALUES[geti(c, "tempStop", 2, 4)]; }

    static boolean rootKnown(Context c) { return getb(c, "rootOk", false); }
    static void setRootKnown(Context c, boolean v) { setb(c, "rootOk", v); }
    static String last(Context c) { return p(c).getString("last", ""); }
    static void setLast(Context c, String uri) { p(c).edit().putString("last", uri == null ? "" : uri).apply(); }
    static String touchesOld(Context c) { return p(c).getString("touchesOld", ""); }
    static void setTouchesOld(Context c, String v) { p(c).edit().putString("touchesOld", v == null ? "" : v).commit(); }

    /** Bubble: posisi (px) dari kiri-atas layar. */
    static int bubbleX(Context c, int def) { return p(c).getInt("bx", def); }
    static int bubbleY(Context c, int def) { return p(c).getInt("by", def); }
    /** Panel geser: posisi handle di tepi atas (px dari kiri). */
    static int panelX(Context c, int def) { return p(c).getInt("px", def); }
    static void setPanelX(Context c, int x) { p(c).edit().putInt("px", x).apply(); }
    static void setBubblePos(Context c, int x, int y) { p(c).edit().putInt("bx", x).putInt("by", y).apply(); }

    /** Mesin root dipakai? (tanpa menjalankan su; pakai status root terakhir yang diketahui) */
    static boolean willUseRoot(Context c) {
        int e = engine(c);
        return audio(c) == 0 && (e == 1 || (e == 0 && (Boost.root || rootKnown(c))));
    }

    static void applyPreset(Context c, int i) {
        SharedPreferences.Editor e = p(c).edit();
        switch (i) {
            case 0: e.putInt("res", 4).putInt("fps", 1).putInt("bitrate", 1); break;
            case 1: e.putInt("res", 2).putInt("fps", 1).putInt("bitrate", 0); break;
            case 2: e.putInt("res", 1).putInt("fps", 1).putInt("bitrate", 0); break;
            default: e.putInt("res", 1).putInt("fps", 3).putInt("bitrate", 6); break;
        }
        e.apply();
    }

    // ---- ukuran & bitrate ----
    /** {lebar, tinggi, dpi} layar sekarang (sudah ikut rotasi + resolusi yang sedang dipakai). */
    static int[] screen(Context c) {
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        return new int[]{dm.widthPixels, dm.heightPixels, dm.densityDpi};
    }

    /** Ukuran video: sisi pendek mengikuti pilihan resolusi, rasio dijaga, kelipatan 16 (aman untuk encoder hardware). */
    static int[] size(Context c) {
        int[] s = screen(c);
        int w = s[0], h = s[1];
        int target = RES_SHORT[res(c)];
        int shortSide = Math.min(w, h);
        if (target > 0 && shortSide > target) {
            float f = (float) target / shortSide;
            w = Math.round(w * f);
            h = Math.round(h * f);
        }
        return new int[]{Math.max(16, w & ~15), Math.max(16, h & ~15)};
    }

    static int bitrate(Context c, int w, int h, int fps) {
        int i = bitrateIdx(c);
        if (i > 0) return BITRATE_MBPS[i] * 1_000_000;
        double bpp = codec(c) == 1 ? 0.085 : 0.12;           // bit per piksel per frame
        long bps = (long) (w * (long) h * fps * bpp);
        return (int) Math.max(2_000_000L, Math.min(16_000_000L, bps));
    }

    // ---- nama file ----
    static String baseName(Context c) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        int m = nameMode(c);
        return (m == 1 ? "REC" : m == 2 ? "Vallen" : gameLabel(c)) + "_" + stamp;
    }

    private static String gameLabel(Context c) {
        String s = Boost.session(c);
        if (s.isEmpty()) return "REC";
        try {
            CharSequence l = c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(s, 0));
            String n = l.toString().replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
            return n.isEmpty() ? "Game" : n;
        } catch (Exception e) {
            return "Game";
        }
    }
}

package com.vallen.boost;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Halaman Rekam Layar, hidup di dalam MainActivity (satu stage dengan Home & Setting):
 * background, nav bawah, dan video live wallpaper tetap jalan, transisi digeser halus.
 */
final class RecPage {
    private static final int BG = 0xFF07080C, LINE = 0xFF232838, FG = 0xFFFFFFFF, DIM = 0xFF8A93A6,
        GLASS = 0xB3101319, RED = 0xFFFF3B5C, AMBER = 0xFFFFB020, GREEN = 0xFF00FF88;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    static final int REQ_MIC = 7;

    private final Activity act;
    private final Runnable back;
    private int accent, acc1, acc2;
    private final ArrayList<Runnable> refreshers = new ArrayList<>();
    private boolean busy, registered;
    private TextView stateTv, timeTv, hintTv, engineHint;
    private Button startBtn, pauseBtn;
    private View overlayRow;
    private LinearLayout listCard;
    private boolean showAll;
    private int prevState = RecorderService.IDLE;

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            refreshStatus();
            int s = RecorderService.state;
            if (prevState != RecorderService.IDLE && s == RecorderService.IDLE) loadList();   // rekaman baru selesai disimpan
            prevState = s;
        }
    };

    RecPage(Activity act, Runnable back) {
        this.act = act;
        this.back = back;
        accent = Boost.THEME_ACCENT[Boost.theme(act)];
        acc1 = mix(accent, 0xFF000000, 0.22f);
        acc2 = mix(accent, 0xFF000000, 0.58f);
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Dipanggil waktu halaman kelihatan: pantau status perekam. */
    void resume() {
        if (!registered) {
            act.registerReceiver(rx, new IntentFilter(RecorderService.STATE));
            registered = true;
        }
        refreshAll();
    }

    /** Dipanggil waktu halaman tersembunyi / app ke background: berhenti memantau (hemat baterai). */
    void pause() {
        if (!registered) return;
        try { act.unregisterReceiver(rx); } catch (Exception ignored) {}
        registered = false;
    }

    void onPermResult(int req, int[] r) {
        if (req == REQ_MIC && (r.length == 0 || r[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(act, "Izin mikrofon ditolak: rekaman tanpa suara", Toast.LENGTH_LONG).show();
        }
    }

    // ---------- susunan halaman ----------
    View build() {
        LinearLayout page = box(LinearLayout.VERTICAL);
        // wallpaper kelihatan (sama kayak Home & Setting), cuma digelapin tipis biar kartu tetap kebaca
        page.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0x22000000, 0x00000000, 0x33000000}));

        LinearLayout bar = box(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(10), dp(16), dp(10));
        bar.setBackgroundColor(0x33000000);
        TextView backTv = text("\u2039  KEMBALI", 12, FG);
        backTv.setTypeface(Typeface.DEFAULT_BOLD);
        backTv.setPadding(dp(12), dp(7), dp(12), dp(7));
        backTv.setBackground(shape(0x33FFFFFF, 0x66FFFFFF, 18));
        backTv.setOnClickListener(v -> back.run());
        pressFx(backTv);
        bar.addView(backTv);
        View mark = new View(act);
        mark.setBackground(shape(accent, 0, 2));
        bar.addView(mark, lp(dp(3), dp(18), 0, 14, 0, 0, 0));
        TextView title = text("REKAM LAYAR", 16, FG);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        title.setLetterSpacing(0.2f);
        title.setPadding(dp(10), 0, 0, 0);
        bar.addView(title);
        page.addView(bar, new LinearLayout.LayoutParams(MATCH, WRAP));
        View topLine = new View(act);
        topLine.setBackgroundColor(0x22FFFFFF);
        page.addView(topLine, new LinearLayout.LayoutParams(MATCH, 1));

        ScrollView sv = new ScrollView(act);
        sv.setFillViewport(false);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout col = box(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(12), dp(16), dp(96));   // ruang buat navigasi bawah
        sv.addView(col, new ViewGroup.LayoutParams(MATCH, WRAP));
        page.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1));

        // ---- status + tombol ----
        stateTv = text("SIAP", 20, GREEN);
        stateTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        stateTv.setGravity(Gravity.CENTER);
        timeTv = text("00:00", 34, FG);
        timeTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        timeTv.setGravity(Gravity.CENTER);
        hintTv = text("", 11, DIM);
        hintTv.setGravity(Gravity.CENTER);
        startBtn = primaryButton("MULAI REKAM", v -> onStartStop());
        pauseBtn = button("JEDA", AMBER, v -> {
            RecorderService.send(act, RecorderService.state == RecorderService.PAUSED
                ? RecorderService.A_RESUME : RecorderService.A_PAUSE);
        });
        pauseBtn.setVisibility(View.GONE);
        LinearLayout st = box(LinearLayout.VERTICAL);
        st.setPadding(dp(14), dp(14), dp(14), dp(14));
        st.setBackground(shape(GLASS, 0x22FFFFFF, 20));
        st.addView(stateTv, new LinearLayout.LayoutParams(MATCH, WRAP));
        st.addView(timeTv, new LinearLayout.LayoutParams(MATCH, WRAP));
        st.addView(hintTv, lp(MATCH, WRAP, 0, 0, 4, 0, 10));
        LinearLayout btns = box(LinearLayout.HORIZONTAL);
        btns.addView(startBtn, lp(0, dp(48), 2, 0, 0, 6, 0));
        btns.addView(pauseBtn, lp(0, dp(48), 1, 6, 0, 0, 0));
        st.addView(btns, new LinearLayout.LayoutParams(MATCH, WRAP));
        col.addView(st, new LinearLayout.LayoutParams(MATCH, WRAP));

        // ---- daftar rekaman ----
        section(col, "REKAMAN");
        listCard = box(LinearLayout.VERTICAL);
        listCard.setPadding(dp(14), dp(2), dp(14), dp(2));
        listCard.setBackground(shape(GLASS, 0x22FFFFFF, 20));
        col.addView(listCard, new LinearLayout.LayoutParams(MATCH, WRAP));

        // ---- cara meluncurkan ----
        section(col, "CARA MELUNCURKAN");
        overlayRow = linkRow("Izin tampil di atas app lain", "Dibutuhkan panel geser & hitung mundur. Ketuk untuk mengizinkan.",
            linkVal("BELUM"), v -> act.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + act.getPackageName()))));
        col.addView(card(
            overlayRow,
            toggle("Panel geser (ala ROG)", "Geser turun dari tepi atas layar: panel Mulai / Stop / Jeda + setelan cepat", "bubble", false,
                () -> RecorderService.syncArm(act)),
            choice("Handle saat merekam", "Handle kecil di atas layar: tampil, samar, atau sembunyi biar tidak ikut terlihat di video", RecConfig.BUBBLE_NAMES, "bubbleMode", 1, () -> RecorderService.syncArm(act)),
            toggle("Auto-rekam saat game dibuka", "Mulai saat game dibuka lewat launcher Vallen Boost, berhenti saat game ditutup", "autoGame", false,
                () -> RecorderService.syncArm(act)),
            choice("Tunda auto-rekam", "Lewati layar loading game", RecConfig.AUTODELAY_NAMES, "autoDelay", 2, null),
            info("Cara lain: tambahkan tile \"Rekam layar\" di panel Quick Settings (tarik panel, ikon pensil). "
                + "Notifikasi perekam juga punya tombol Jeda dan Stop. Stop paling aman lewat tile atau notifikasi, karena tidak ikut terekam.")
        ), new LinearLayout.LayoutParams(MATCH, WRAP));

        // ---- kualitas ----
        section(col, "KUALITAS VIDEO");
        engineHint = text("", 11, DIM);
        engineHint.setPadding(0, dp(4), 0, dp(10));
        View preset = linkRow("Preset cepat", "Atur resolusi, FPS, dan bitrate sekaligus", linkVal("Pilih"), v ->
            new AlertDialog.Builder(act).setTitle("Preset kualitas")
                .setItems(RecConfig.PRESET_NAMES, (d, w) -> { RecConfig.applyPreset(act, w); refreshAll(); })
                .setNegativeButton("Batal", null).show());
        col.addView(card(
            preset,
            choice("Mesin rekam", "Standar: ada suara + jeda. Root: tanpa dialog izin (cocok auto-rekam)", RecConfig.ENGINE_NAMES, "engine", 0, null),
            choice("Resolusi", "Makin kecil makin ringan buat HP", RecConfig.RES_NAMES, "res", 2, null),
            choice("Frame rate", "30 FPS paling ringan. Hanya mesin Standar", RecConfig.FPS_NAMES, "fps", 1, null),
            choice("Bitrate video", "Otomatis menyesuaikan resolusi dan FPS", RecConfig.BITRATE_NAMES, "bitrate", 0, null),
            choice("Codec", "HEVC lebih kecil tapi lebih berat; kalau chip tidak mendukung otomatis balik ke H.264", RecConfig.CODEC_NAMES, "codec", 0, null),
            info("")
        ), new LinearLayout.LayoutParams(MATCH, WRAP));
        // baris terakhir kartu = ringkasan; ganti isi info kosong tadi dengan engineHint
        LinearLayout kq = (LinearLayout) col.getChildAt(col.getChildCount() - 1);
        kq.removeViewAt(kq.getChildCount() - 1);
        kq.addView(engineHint);

        // ---- suara ----
        section(col, "SUARA");
        col.addView(card(
            choice("Sumber suara", "Audio internal = suara game/musik langsung dari HP, bersih tanpa noise (Android 10+). Mikrofon = suara sekitar/ngomong", RecConfig.AUDIO_NAMES, "audio", 0, this::askMic),
            choice("Bitrate suara", "128 kbps sudah jernih", RecConfig.ABR_NAMES, "abr", 2, null),
            choice("Sample rate", "48 kHz standar video", RecConfig.SAMPLE_NAMES, "sample", 1, null)
        ), new LinearLayout.LayoutParams(MATCH, WRAP));

        // ---- pengaman ----
        section(col, "PENGAMAN & OTOMATIS");
        col.addView(card(
            choice("Hitung mundur", "Waktu pindah ke game sebelum mulai merekam", RecConfig.COUNTDOWN_NAMES, "countdown", 2, null),
            choice("Batas durasi", "Berhenti otomatis supaya file tidak kebesaran", RecConfig.MAXMIN_NAMES, "maxmin", 0, null),
            choice("Stop kalau ruang sisa kurang dari", "Cegah penyimpanan penuh", RecConfig.MINFREE_NAMES, "minfree", 1, null),
            toggle("Stop saat layar mati", "Rekaman berhenti kalau layar dikunci", "stopOff", true, null),
            toggle("Stop kalau terlalu panas", "Merekam menambah panas; berhenti sebelum HP kepanasan", "thermal", true, null),
            choice("Batas suhu baterai", "Dipakai oleh pengaman panas di atas", RecConfig.TEMP_NAMES, "tempStop", 2, null),
            toggle("Tampilkan sentuhan (root)", "Titik di layar tiap jari menyentuh, berguna untuk tutorial. Dikembalikan setelah rekam", "touches", false, null)
        ), new LinearLayout.LayoutParams(MATCH, WRAP));

        // ---- file ----
        section(col, "FILE");
        col.addView(card(
            choice("Nama file", "Contoh: Mobile_Legends_20261019_153000.mp4", RecConfig.NAME_NAMES, "nameMode", 0, null),
            info("Disimpan di Movies/VallenBoost (muncul di Galeri). Penyimpanan internal paling lancar; hindari kartu SD yang lambat.")
        ), new LinearLayout.LayoutParams(MATCH, WRAP));

        section(col, "TIPS BIAR LANCAR");
        col.addView(card(info(
            "\u2022 Mulai dari preset Seimbang (720p, 30 FPS). Naik ke 1080p/60 FPS hanya kalau game masih mulus.\n"
          + "\u2022 H.264 lebih ringan daripada HEVC di chip kelas menengah.\n"
          + "\u2022 Pakai hitung mundur 5 detik lalu pindah ke game, atau Auto-rekam.\n"
          + "\u2022 Sembunyikan handle saat merekam dan stop lewat tile/notifikasi.\n"
          + "\u2022 Tutup app lain dan jaga suhu; Stop-kalau-panas bantu mencegah throttling.")
        ), new LinearLayout.LayoutParams(MATCH, WRAP));

        // ringkasan hasil + status ikut diperbarui tiap setelan berubah
        refreshers.add(() -> {
            int[] sz = RecConfig.size(act);
            boolean root = RecConfig.willUseRoot(act);
            int fps = RecConfig.fps(act);
            float mbps = RecConfig.bitrate(act, sz[0], sz[1], fps) / 1_000_000f;
            engineHint.setText("Hasil: " + sz[0] + "\u00D7" + sz[1] + " \u2022 "
                + (root ? "mesin Root: tanpa suara/jeda, FPS & codec diatur sistem, file disambung tiap 3 menit"
                        : String.format(Locale.US, "%d FPS \u2022 %.1f Mbps \u2022 %s", fps, mbps,
                            RecConfig.codec(act) == 1 ? "HEVC" : "H.264")));
        });
        return page;
    }

    // ---------- aksi ----------
    private void onStartStop() {
        int s = RecorderService.state;
        if (s == RecorderService.REC || s == RecorderService.PAUSED || s == RecorderService.PREP) {
            RecorderService.send(act, RecorderService.A_STOP);
        } else if (s == RecorderService.IDLE) {
            askMic();
            RecorderService.send(act, RecorderService.A_START);
            Toast.makeText(act, "Pindah ke game-nya. Stop lewat panel geser / notifikasi / tile.", Toast.LENGTH_LONG).show();
            if (RecConfig.willUseRoot(act)) act.moveTaskToBack(true);
        }
    }

    private void askMic() {
        if (RecConfig.audio(act) != 0 && !RecConfig.willUseRoot(act)
                && act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    // ---------- daftar rekaman ----------
    private static final class Rec {
        Uri uri;
        File file;
        String name;
        long date, dur, size;
    }

    private ArrayList<Rec> queryRecs(int limit) {
        ArrayList<Rec> out = new ArrayList<>();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                String[] proj = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE};
                Cursor cu = act.getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj,
                    MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?", new String[]{"Movies/VallenBoost%"},
                    MediaStore.Video.Media.DATE_ADDED + " DESC");
                if (cu != null) {
                    try {
                        while (cu.moveToNext() && out.size() < limit) {
                            Rec r = new Rec();
                            r.uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, String.valueOf(cu.getLong(0)));
                            r.name = cu.getString(1);
                            r.date = cu.getLong(2) * 1000L;
                            r.dur = cu.getLong(3);
                            r.size = cu.getLong(4);
                            out.add(r);
                        }
                    } finally {
                        cu.close();
                    }
                }
            } else {
                File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_MOVIES), "VallenBoost");
                File[] fs = dir.listFiles();
                if (fs != null) {
                    java.util.Arrays.sort(fs, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
                    for (File f : fs) {
                        if (out.size() >= limit) break;
                        if (!f.getName().endsWith(".mp4")) continue;
                        Rec r = new Rec();
                        r.file = f;
                        r.uri = Uri.fromFile(f);
                        r.name = f.getName();
                        r.date = f.lastModified();
                        r.size = f.length();
                        out.add(r);
                    }
                }
            }
        } catch (Exception ignored) {
            // tidak ada izin baca / MediaStore error: daftar kosong
        }
        return out;
    }

    private void loadList() {
        if (listCard == null) return;
        listCard.removeAllViews();
        ArrayList<Rec> all = queryRecs(40);
        if (all.isEmpty()) {
            listCard.addView(info("Belum ada rekaman. Hasil rekaman muncul di sini, bisa dibuka atau dihapus."));
            return;
        }
        int n = showAll ? all.size() : Math.min(4, all.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                View d = new View(act);
                d.setBackgroundColor(LINE);
                listCard.addView(d, new LinearLayout.LayoutParams(MATCH, 1));
            }
            listCard.addView(recRow(all.get(i)));
        }
        if (all.size() > 4) {
            View d = new View(act);
            d.setBackgroundColor(LINE);
            listCard.addView(d, new LinearLayout.LayoutParams(MATCH, 1));
            TextView more = text(showAll ? "Tampilkan lebih sedikit" : "Tampilkan semua (" + all.size() + ")", 12, accent);
            more.setTypeface(Typeface.DEFAULT_BOLD);
            more.setGravity(Gravity.CENTER);
            more.setPadding(0, dp(12), 0, dp(12));
            more.setOnClickListener(v -> { showAll = !showAll; loadList(); });
            pressFx(more);
            listCard.addView(more, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
    }

    private View recRow(final Rec r) {
        LinearLayout row = box(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        LinearLayout tx = box(LinearLayout.VERTICAL);
        TextView name = text(r.name == null ? "rekaman.mp4" : r.name, 13, FG);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        StringBuilder meta = new StringBuilder(new SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(new Date(r.date)));
        if (r.dur > 0) meta.append("  \u2022  ").append(RecorderService.fmt(r.dur));
        if (r.size > 0) meta.append("  \u2022  ").append(size(r.size));
        tx.addView(name);
        tx.addView(text(meta.toString(), 11, DIM));
        row.addView(tx, lp(0, WRAP, 1, 0, 0, 8, 0));
        row.addView(button("Buka", FG, v -> openRec(r)), lp(WRAP, dp(38), 0, 0, 0, 6, 0));
        row.addView(button("Bagikan", FG, v -> shareRec(r)), lp(WRAP, dp(38), 0, 0, 0, 6, 0));
        row.addView(button("Hapus", RED, v -> deleteRec(r)), lp(WRAP, dp(38), 0, 0, 0, 0, 0));
        return row;
    }

    private static String size(long b) {
        if (b >= 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f GB", b / 1073741824.0);
        return String.format(Locale.US, "%d MB", Math.max(1, b / 1048576));
    }

    private void openRec(Rec r) {
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(r.uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception e) {
            Toast.makeText(act, "Tidak ada aplikasi pemutar video", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareRec(Rec r) {
        try {
            act.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("video/mp4")
                .putExtra(Intent.EXTRA_STREAM, r.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Bagikan rekaman"));
        } catch (Exception e) {
            Toast.makeText(act, "Gagal membagikan", Toast.LENGTH_SHORT).show();
        }
    }

    private void deleteRec(final Rec r) {
        new AlertDialog.Builder(act).setTitle("Hapus rekaman?").setMessage(r.name)
            .setPositiveButton("Hapus", (d, w) -> {
                boolean ok = false;
                try {
                    if (r.file != null) ok = r.file.delete();
                    else ok = act.getContentResolver().delete(r.uri, null, null) > 0;
                } catch (Exception ignored) {}
                if (ok) {
                    if (r.uri.toString().equals(RecConfig.last(act))) RecConfig.setLast(act, "");
                    Toast.makeText(act, "Rekaman dihapus", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(act, "Gagal menghapus", Toast.LENGTH_SHORT).show();
                }
                loadList();
            })
            .setNegativeButton("Batal", null).show();
    }

    // ---------- penyegaran ----------
    private void refreshAll() {
        for (Runnable r : refreshers) r.run();
        overlayRow.setVisibility(Settings.canDrawOverlays(act) ? View.GONE : View.VISIBLE);
        loadList();
        refreshStatus();
    }

    private void refreshStatus() {
        int s = RecorderService.state;
        String hint = "Setelah mulai, pindah ke game. Stop lewat panel geser, notifikasi, atau tile.";
        pauseBtn.setVisibility(View.GONE);
        startBtn.setEnabled(true);
        switch (s) {
            case RecorderService.REC:
                stateTv.setText("\u25CF MEREKAM"); stateTv.setTextColor(RED);
                timeTv.setText(RecorderService.fmt(RecorderService.elapsedMs));
                startBtn.setText("STOP");
                if (RecorderService.canPause) { pauseBtn.setText("JEDA"); pauseBtn.setVisibility(View.VISIBLE); }
                break;
            case RecorderService.PAUSED:
                stateTv.setText("\u275A\u275A DIJEDA"); stateTv.setTextColor(AMBER);
                timeTv.setText(RecorderService.fmt(RecorderService.elapsedMs));
                startBtn.setText("STOP");
                pauseBtn.setText("LANJUT"); pauseBtn.setVisibility(View.VISIBLE);
                break;
            case RecorderService.PREP:
                stateTv.setText("MENYIAPKAN..."); stateTv.setTextColor(AMBER);
                startBtn.setText("BATAL");
                break;
            case RecorderService.STOPPING:
                stateTv.setText("MENYIMPAN..."); stateTv.setTextColor(AMBER);
                startBtn.setEnabled(false);
                break;
            default:
                stateTv.setText("SIAP"); stateTv.setTextColor(GREEN);
                timeTv.setText("00:00");
                startBtn.setText("MULAI REKAM");
                if (!RecorderService.lastError.isEmpty()) hint = "Terakhir: " + RecorderService.lastError;
        }
        hintTv.setText(hint);
    }

    // ---------- komponen UI (gaya sama dengan MainActivity) ----------
    private View choice(final String title, String desc, final String[] names, final String key, final int def, final Runnable after) {
        final TextView val = linkVal("");
        refreshers.add(() -> val.setText(names[RecConfig.geti(act, key, def, names.length - 1)]));
        return linkRow(title, desc, val, v ->
            new AlertDialog.Builder(act).setTitle(title)
                .setSingleChoiceItems(names, RecConfig.geti(act, key, def, names.length - 1), (d, w) -> {
                    RecConfig.seti(act, key, w);
                    d.dismiss();
                    if (after != null) after.run();
                    refreshAll();
                }).setNegativeButton("Batal", null).show());
    }

    private View toggle(String title, String desc, final String key, final boolean def, final Runnable after) {
        final Switch s = sw();
        refreshers.add(() -> { busy = true; s.setChecked(RecConfig.getb(act, key, def)); busy = false; });
        s.setOnCheckedChangeListener((v, on) -> {
            if (busy) return;
            RecConfig.setb(act, key, on);
            if (after != null) after.run();
            refreshAll();
        });
        return row(title, desc, s);
    }

    private View info(String s) {
        TextView t = text(s, 11, DIM);
        t.setPadding(0, dp(10), 0, dp(10));
        return t;
    }

    private LinearLayout box(int o) {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(o);
        return l;
    }

    private LinearLayout.LayoutParams lp(int w, int h, float wt, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, wt);
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

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private void section(LinearLayout col, String s) {
        SpannableString sp = new SpannableString("\u258c " + s);
        sp.setSpan(new ForegroundColorSpan(accent), 0, 1, 0);
        TextView t = text("", 11, DIM);
        t.setText(sp);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.25f);
        t.setPadding(dp(2), dp(22), 0, dp(8));
        col.addView(t);
    }

    private LinearLayout card(View... rows) {
        LinearLayout c = box(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(2), dp(14), dp(2));
        c.setBackground(shape(GLASS, 0x22FFFFFF, 20));
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) {
                View d = new View(act);
                d.setBackgroundColor(LINE);
                c.addView(d, new LinearLayout.LayoutParams(MATCH, 1));
            }
            c.addView(rows[i]);
        }
        return c;
    }

    private TextView linkVal(String s) {
        TextView t = text(s, 12, accent);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

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

    private Switch sw() {
        Switch w = new Switch(act);
        w.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{accent, 0xFF6B7280}));
        w.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
            new int[]{(accent & 0x00FFFFFF) | 0x66000000, 0x33FFFFFF}));
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

    private Button button(String s, int color, View.OnClickListener l) {
        Button b = new Button(act);
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

    private Button primaryButton(String s, View.OnClickListener l) {
        Button b = button(s, FG, l);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{acc1, acc2});
        g.setCornerRadius(dp(14));
        g.setStroke(dp(1), accent);
        b.setBackground(g);
        return b;
    }

    private void pressFx(View v) {
        v.setOnTouchListener((view, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) view.animate().scaleX(0.96f).scaleY(0.96f).alpha(0.85f).setDuration(90).start();
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(160).start();
            return false;
        });
    }

    private int dp(int v) { return (int) (v * act.getResources().getDisplayMetrics().density); }
}

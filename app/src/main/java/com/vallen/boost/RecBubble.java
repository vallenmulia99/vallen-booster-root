package com.vallen.boost;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;

/**
 * Kontrol perekam ala ROG: handle tipis di tengah atas layar (tanpa ikon bulat). Geser ke bawah (atau ketuk)
 * membuka panel yang meluncur dari atas: Mulai / Stop / Jeda + setelan cepat. Handle bisa digeser ke kiri-kanan.
 * Semua dipanggil dari main thread.
 */
final class RecBubble {
    interface Cb { void onToggle(); void onPause(); void onClose(); }

    private static final int RED = 0xFFFF3B5C, AMBER = 0xFFFFB020, GREEN = 0xFF00FF88,
        PANEL = 0xF2101319, LINE = 0x33FFFFFF, DIM = 0xFF8A93A6, FG = 0xFFFFFFFF;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT, MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    private final Context c;
    private final WindowManager wm;
    private final Cb cb;
    private final Handler h = new Handler(Looper.getMainLooper());

    // handle
    private LinearLayout hRoot;
    private View hGrip;
    private TextView hTxt;
    private WindowManager.LayoutParams hLp;
    private boolean hAttached;

    // panel
    private LinearLayout pRoot, setBox;
    private TextView statusTv, bRec, bPause;
    private WindowManager.LayoutParams pLp;
    private boolean pAttached, expanded, settingsOpen;
    private final ArrayList<Runnable> chipRefresh = new ArrayList<>();

    private TextView countTv;
    private int st = RecorderService.IDLE;
    private String time = "00:00";
    private final Runnable collapse = new Runnable() {
        @Override public void run() { expand(false); }
    };

    RecBubble(Context c, Cb cb) {
        this.c = c;
        this.cb = cb;
        this.wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
    }

    private int dp(int v) { return (int) (v * c.getResources().getDisplayMetrics().density); }

    private boolean active() { return st == RecorderService.REC || st == RecorderService.PAUSED; }

    private static int dark(int col, float f) {
        int r = Math.round(((col >> 16) & 255) * (1 - f));
        int g = Math.round(((col >> 8) & 255) * (1 - f));
        int b = Math.round((col & 255) * (1 - f));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private GradientDrawable shape(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    // ================= handle =================

    void show() {
        if (hAttached) { apply(); return; }
        if (!Settings.canDrawOverlays(c)) return;
        final int hw = dp(84), hh = dp(24);

        hRoot = new LinearLayout(c);
        hRoot.setOrientation(LinearLayout.HORIZONTAL);
        hRoot.setGravity(Gravity.CENTER);
        hRoot.setMinimumWidth(hw);
        hRoot.setMinimumHeight(hh);

        hGrip = new View(c);
        hGrip.setBackground(shape(0x88FFFFFF, 0, 3));
        hRoot.addView(hGrip, new LinearLayout.LayoutParams(dp(44), dp(5)));

        hTxt = text("", 10, FG);
        hTxt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        hTxt.setGravity(Gravity.CENTER);
        hTxt.setPadding(dp(10), dp(3), dp(10), dp(3));
        hTxt.setVisibility(View.GONE);
        hRoot.addView(hTxt, new LinearLayout.LayoutParams(WRAP, WRAP));

        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        hLp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        hLp.gravity = Gravity.TOP | Gravity.START;
        hLp.x = clampX(RecConfig.panelX(c, (dm.widthPixels - hw) / 2));
        hLp.y = 0;

        hRoot.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy;
            int ox, mode;                       // mode: 0 belum jelas, 1 geser samping, 2 geser ke bawah
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY(); ox = hLp.x; mode = 0;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                        if (mode == 0) {
                            if (dy > dp(10) && dy > Math.abs(dx)) { mode = 2; expand(true); }
                            else if (Math.abs(dx) > dp(10) && Math.abs(dx) > Math.abs(dy)) mode = 1;
                        }
                        if (mode == 1) {
                            hLp.x = clampX(ox + (int) dx);
                            try { wm.updateViewLayout(hRoot, hLp); } catch (Exception ignored) {}
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (mode == 0) expand(true);                  // ketuk = buka panel
                        else if (mode == 1) RecConfig.setPanelX(c, hLp.x);
                        return true;
                    default:
                        return false;
                }
            }
        });
        try {
            wm.addView(hRoot, hLp);
            hAttached = true;
        } catch (Exception e) {
            hAttached = false;
            return;
        }
        apply();
    }

    private int clampX(int x) {
        DisplayMetrics d = c.getResources().getDisplayMetrics();
        return Math.max(0, Math.min(d.widthPixels - dp(84), x));
    }

    // ================= panel =================

    private LinearLayout.LayoutParams lp(int w, int hgt, float wt, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, hgt, wt);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private void buildPanel() {
        final int accent = Boost.THEME_ACCENT[Boost.theme(c)];
        chipRefresh.clear();
        pRoot = new LinearLayout(c);
        pRoot.setOrientation(LinearLayout.VERTICAL);
        pRoot.setPadding(dp(14), dp(10), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL);
        bg.setStroke(dp(1), LINE);
        float r = dp(20);
        bg.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        pRoot.setBackground(bg);
        pRoot.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) { expand(false); return true; }
            return false;
        });

        // ---- header ----
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View mark = new View(c);
        mark.setBackground(shape(accent, 0, 2));
        head.addView(mark, new LinearLayout.LayoutParams(dp(3), dp(16)));
        statusTv = text("SIAP", 12, GREEN);
        statusTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        statusTv.setLetterSpacing(0.12f);
        statusTv.setPadding(dp(8), 0, 0, 0);
        head.addView(statusTv, new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView gear = pill("\u2699  SETELAN");
        gear.setOnClickListener(v -> {
            settingsOpen = !settingsOpen;
            setBox.setVisibility(settingsOpen ? View.VISIBLE : View.GONE);
            refreshPanel();
            schedule();
        });
        head.addView(gear);
        TextView up = pill("\u25B2");
        up.setOnClickListener(v -> expand(false));
        head.addView(up, lp(WRAP, WRAP, 0, 8, 0, 0, 0));
        pRoot.addView(head, new LinearLayout.LayoutParams(MATCH, WRAP));

        // ---- tombol utama ----
        LinearLayout btns = new LinearLayout(c);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        bRec = bigBtn();
        bRec.setOnClickListener(v -> {
            if (st == RecorderService.PREP || st == RecorderService.STOPPING) return;
            expand(false);
            cb.onToggle();
        });
        bPause = bigBtn();
        bPause.setOnClickListener(v -> {
            if (st == RecorderService.PAUSED) expand(false);          // lanjut merekam: panel jangan ikut terekam
            else schedule();                                          // jeda: frame dibuang, panel boleh tetap terbuka
            cb.onPause();
        });
        btns.addView(bRec, lp(0, dp(46), 2, 0, 0, 4, 0));
        btns.addView(bPause, lp(0, dp(46), 1, 4, 0, 0, 0));
        pRoot.addView(btns, lp(MATCH, WRAP, 0, 0, 10, 0, 0));

        // ---- setelan cepat (ketuk = pilihan berikutnya) ----
        setBox = new LinearLayout(c);
        setBox.setOrientation(LinearLayout.VERTICAL);
        setBox.setVisibility(View.GONE);
        LinearLayout r1 = chipRow(), r2 = chipRow();
        r1.addView(chip("RESOLUSI", RecConfig.RES_NAMES, "res", 2, true));
        r1.addView(chip("FPS", RecConfig.FPS_NAMES, "fps", 1, true));
        r1.addView(chip("SUARA", RecConfig.AUDIO_NAMES, "audio", 0, true));
        r2.addView(chip("HITUNG MUNDUR", RecConfig.COUNTDOWN_NAMES, "countdown", 2, false));
        r2.addView(chip("BATAS DURASI", RecConfig.MAXMIN_NAMES, "maxmin", 0, false));
        r2.addView(chip("HANDLE SAAT REKAM", RecConfig.BUBBLE_NAMES, "bubbleMode", 1, false));
        setBox.addView(r1, lp(MATCH, WRAP, 0, 0, 6, 0, 0));
        setBox.addView(r2, lp(MATCH, WRAP, 0, 0, 6, 0, 0));
        LinearLayout foot = new LinearLayout(c);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        TextView open = pill("BUKA HALAMAN REKAM");
        open.setOnClickListener(v -> {
            expand(false);
            try {
                c.startActivity(new Intent(c, MainActivity.class).putExtra("page", 2)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            } catch (Exception ignored) {}
        });
        TextView off = pill("MATIKAN PANEL");
        off.setTextColor(RED);
        off.setOnClickListener(v -> { expand(false); cb.onClose(); });
        foot.addView(open);
        foot.addView(off, lp(WRAP, WRAP, 0, 8, 0, 0, 0));
        setBox.addView(foot, lp(WRAP, WRAP, 0, 0, 8, 0, 0));
        pRoot.addView(setBox, lp(MATCH, WRAP, 0, 0, 10, 0, 0));

        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        pLp = new WindowManager.LayoutParams(
            Math.min(dp(500), dm.widthPixels - dp(24)), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        pLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
    }

    private TextView pill(String s) {
        TextView t = text(s, 10, FG);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        t.setBackground(shape(0x22FFFFFF, 0x44FFFFFF, 12));
        return t;
    }

    private TextView bigBtn() {
        TextView t = text("", 13, FG);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.1f);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private LinearLayout chipRow() {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    /** Kotak setelan: ketuk = pilihan berikutnya. lockActive = tidak bisa diubah saat merekam. */
    private View chip(String label, final String[] names, final String key, final int def, final boolean lockActive) {
        final LinearLayout ch = new LinearLayout(c);
        ch.setOrientation(LinearLayout.VERTICAL);
        ch.setPadding(dp(10), dp(6), dp(10), dp(6));
        ch.setBackground(shape(0x22FFFFFF, 0x33FFFFFF, 12));
        TextView cap = text(label, 8, DIM);
        cap.setTypeface(Typeface.DEFAULT_BOLD);
        cap.setLetterSpacing(0.1f);
        cap.setSingleLine(true);
        cap.setEllipsize(TextUtils.TruncateAt.END);
        final TextView val = text("", 12, FG);
        val.setTypeface(Typeface.DEFAULT_BOLD);
        val.setSingleLine(true);
        val.setEllipsize(TextUtils.TruncateAt.END);
        ch.addView(cap);
        ch.addView(val);
        final Runnable refresh = () -> {
            val.setText(names[RecConfig.geti(c, key, def, names.length - 1)]);
            ch.setAlpha(lockActive && (active() || st == RecorderService.PREP) ? 0.4f : 1f);
        };
        chipRefresh.add(refresh);
        ch.setOnClickListener(v -> {
            schedule();
            if (lockActive && (active() || st == RecorderService.PREP || st == RecorderService.STOPPING)) {
                Toast.makeText(c, "Tidak bisa diubah saat merekam", Toast.LENGTH_SHORT).show();
                return;
            }
            int cur = RecConfig.geti(c, key, def, names.length - 1);
            RecConfig.seti(c, key, (cur + 1) % names.length);
            refresh.run();
            if ("bubbleMode".equals(key)) apply();
        });
        ch.setLayoutParams(lp(0, WRAP, 1, 3, 0, 3, 0));
        return ch;
    }

    private void expand(boolean on) {
        h.removeCallbacks(collapse);
        if (on == expanded) { if (on) schedule(); return; }
        if (on) {
            if (!hAttached) return;
            if (pRoot == null) buildPanel();
            expanded = true;
            settingsOpen = false;
            setBox.setVisibility(View.GONE);
            refreshPanel();
            pRoot.animate().cancel();
            if (!pAttached) {
                pRoot.setAlpha(0f);
                pRoot.setTranslationY(-dp(50));
                try {
                    wm.addView(pRoot, pLp);
                    pAttached = true;
                } catch (Exception e) {
                    expanded = false;
                    return;
                }
            }
            pRoot.animate().alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.8f)).start();
            hRoot.setVisibility(View.INVISIBLE);
            schedule();
        } else {
            expanded = false;
            if (pAttached) {
                pRoot.animate().cancel();
                pRoot.animate().alpha(0f).translationY(-dp(50)).setDuration(160)
                    .withEndAction(() -> { if (!expanded) removePanel(); }).start();
            }
            apply();
        }
    }

    private void removePanel() {
        if (pAttached) {
            try { wm.removeView(pRoot); } catch (Exception ignored) {}
            pAttached = false;
        }
    }

    /** Panel menutup sendiri kalau tidak disentuh (lebih lama kalau setelan lagi dibuka). */
    private void schedule() {
        h.removeCallbacks(collapse);
        h.postDelayed(collapse, settingsOpen ? 15000 : 6000);
    }

    private void refreshPanel() {
        if (pRoot == null) return;
        final int accent = Boost.THEME_ACCENT[Boost.theme(c)];
        boolean act = active();
        bPause.setVisibility(act && RecorderService.canPause ? View.VISIBLE : View.GONE);
        switch (st) {
            case RecorderService.REC:
                statusTv.setText("\u25CF MEREKAM  " + time); statusTv.setTextColor(RED); break;
            case RecorderService.PAUSED:
                statusTv.setText("\u275A\u275A DIJEDA  " + time); statusTv.setTextColor(AMBER); break;
            case RecorderService.PREP:
                statusTv.setText("MENYIAPKAN..."); statusTv.setTextColor(AMBER); break;
            case RecorderService.STOPPING:
                statusTv.setText("MENYIMPAN..."); statusTv.setTextColor(AMBER); break;
            default:
                statusTv.setText("SIAP"); statusTv.setTextColor(GREEN);
        }
        bRec.setAlpha(st == RecorderService.PREP || st == RecorderService.STOPPING ? 0.5f : 1f);
        if (act) {
            bRec.setText("\u25A0  STOP");
            bRec.setBackground(shape(0x33FF3B5C, RED, 14));
        } else {
            bRec.setText(st == RecorderService.PREP ? "MENYIAPKAN..." : st == RecorderService.STOPPING ? "MENYIMPAN..." : "\u25CF  MULAI REKAM");
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{dark(accent, 0.22f), dark(accent, 0.58f)});
            g.setCornerRadius(dp(14));
            g.setStroke(dp(1), accent);
            bRec.setBackground(g);
        }
        bPause.setText(st == RecorderService.PAUSED ? "\u25B6  LANJUT" : "\u275A\u275A  JEDA");
        bPause.setBackground(shape(0x22FFB020, AMBER, 14));
        bPause.setTextColor(AMBER);
        for (Runnable r : chipRefresh) r.run();
    }

    // ================= status =================

    /** Sinkronkan tampilan dengan status perekam + mode handle. */
    void setState(int s) {
        st = s;
        apply();
    }

    void time(String t) {
        time = t;
        if (!hAttached) return;
        if (st == RecorderService.REC) hTxt.setText("\u25CF " + t);
        if (pAttached && expanded) refreshPanel();
    }

    private void apply() {
        if (!hAttached) return;
        boolean act = active();
        int mode = RecConfig.bubbleMode(c);          // saat merekam: 0 biasa, 1 samar, 2 sembunyi
        if (act) {
            boolean rec = st == RecorderService.REC;
            hTxt.setText((rec ? "\u25CF " : "\u275A\u275A ") + time);
            hTxt.setTextColor(rec ? 0xFFFFFFFF : 0xFF000000);
            hTxt.setBackground(shape(rec ? RED : AMBER, 0, 8));
            hTxt.setVisibility(View.VISIBLE);
            hGrip.setVisibility(View.GONE);
        } else {
            hTxt.setVisibility(View.GONE);
            hGrip.setVisibility(View.VISIBLE);
        }
        float a = 1f;
        int vis = View.VISIBLE;
        if (act) {
            if (mode == 1) a = 0.35f;
            else if (mode == 2) vis = View.GONE;
        }
        hRoot.setAlpha(a);
        hRoot.setVisibility(expanded ? View.INVISIBLE : vis);
        if (expanded) refreshPanel();
    }

    // ================= hitung mundur =================

    /** Angka hitung mundur besar di tengah layar. n <= 0 = hapus. Hilang sebelum rekaman mulai, jadi tidak ikut terekam. */
    void countdown(int n) {
        if (n <= 0) {
            if (countTv != null) {
                try { wm.removeView(countTv); } catch (Exception ignored) {}
                countTv = null;
            }
            return;
        }
        if (countTv == null) {
            if (!Settings.canDrawOverlays(c)) return;
            countTv = new TextView(c);
            countTv.setTextSize(96);
            countTv.setTypeface(Typeface.DEFAULT_BOLD);
            countTv.setTextColor(0xFFFFFFFF);
            countTv.setShadowLayer(12, 0, 0, 0xFF000000);
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.CENTER;
            try { wm.addView(countTv, p); } catch (Exception e) { countTv = null; return; }
        }
        countTv.setText(String.valueOf(n));
    }

    void hide() {
        h.removeCallbacks(collapse);
        countdown(0);
        expanded = false;
        if (pRoot != null) pRoot.animate().cancel();
        removePanel();
        pRoot = null;
        pLp = null;
        if (hAttached) {
            try { wm.removeView(hRoot); } catch (Exception ignored) {}
            hAttached = false;
        }
    }
}

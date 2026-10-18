package com.vallen.boost;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * HUD gaya gaming (ala RedMagic Game Space): panel gelap semi-transparan dengan sudut terpotong,
 * garis tepi merah menyala, FPS besar miring di kiri, lalu kolom PING / CPU / GPU / RAM / BAT.
 * Tiga api di pojok kanan atas = tingkat panas (hijau 1, kuning 2, merah 3). Label "THROTTLE" muncul kalau
 * clock ditahan sistem. API sama seperti sebelumnya: set(), setFlag(), params().
 */
final class HudView extends View {
    static final int FPS = 0, PING = 1, CPU = 2, GPU = 3, RAM = 4, BAT = 5, N = 6;
    private static final int[] COLS = {PING, CPU, GPU, RAM, BAT};

    private static final int PANEL = 0xD0070910, DIM = 0xFF8A93A6, WHITE = 0xFFFFFFFF;
    private static final int HOT = 0xFFFF3B5C, WARM = 0xFFFFB020;          // sama dengan RED / AMBER di BoostService
    private static final String TITLE = "VALLEN ANTI LAG \uD83D\uDDFF";     // + emoji moai

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hi = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fpsP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fpsLbl = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mainP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flagP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path panel = new Path();
    private final Path fl = new Path();
    private final RectF r = new RectF();

    private final String[] vals = new String[N];
    private final String[] main = new String[N];
    private final String[] sub = new String[N];
    private final float[] colW = new float[N];
    private float d, icon, gapI, colGap, padX, padTop, padBot, accentW, cut, m, flameS;
    private float scale = 1f;        // skala ukuran HUD (pengaturan user)
    private int acc = 0xFFFF2D46;    // warna aksen (ikut tema)
    private float bodyW, headerNeed, headerH, row1H, bodyH;
    private int color = 0xFF00FF88, pingColor = 0xFF00FF88;
    private String flag = "";

    HudView(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_SOFTWARE, null);                      // glow (shadow layer) butuh software layer
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        border.setStyle(Paint.Style.STROKE);
        hi.setStyle(Paint.Style.STROKE);
        fpsP.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC));
        fpsLbl.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        fpsLbl.setLetterSpacing(0.12f);
        mainP.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        subP.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        title.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC));
        title.setLetterSpacing(0.12f);
        flagP.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        flagP.setLetterSpacing(0.08f);
        for (int i = 0; i < N; i++) { vals[i] = "--"; split(i); }
        restyle(c);
    }

    /** Ukuran / warna yang dipakai sekarang sudah beda dari pengaturan? (dicek service tiap tick) */
    boolean stale(Context c) {
        return Boost.hudScale(c) != scale || Boost.hudAccent(c) != acc;
    }

    /** Terapkan ukuran + tema dari pengaturan. Aman dipanggil kapan saja dari main thread. */
    void restyle(Context c) {
        scale = Boost.hudScale(c);
        acc = Boost.hudAccent(c);
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        d = dm.density * scale;
        icon = 10 * d; gapI = 3 * d; colGap = 11 * d; padX = 8 * d; padTop = 5 * d; padBot = 6 * d;
        accentW = 3 * d; cut = 7 * d; m = 3 * d; flameS = 10 * d;
        int glow = (acc & 0x00FFFFFF) | 0xCC000000;
        stroke.setStrokeWidth(1.2f * d);
        border.setStrokeWidth(1.1f * d);
        border.setShadowLayer(4 * d, 0, 0, glow);                      // glow warna tema
        hi.setStrokeWidth(2f * d);
        hi.setColor(acc);
        fpsP.setTextSize(sp(c, 22f));
        fpsP.setColor(WHITE);
        fpsP.setShadowLayer(6 * d, 0, 0, (acc & 0x00FFFFFF) | 0x99000000);
        fpsLbl.setTextSize(sp(c, 7f));
        fpsLbl.setColor(DIM);
        mainP.setTextSize(sp(c, 11f));
        subP.setTextSize(sp(c, 7.5f));
        subP.setColor(DIM);
        title.setTextSize(sp(c, 8.5f));
        flagP.setTextSize(sp(c, 7f));
        flagP.setColor(WHITE);
        if (getWidth() > 0) {
            border.setShader(new LinearGradient(0, 0, getWidth(), 0, acc, (acc & 0x00FFFFFF) | 0x66000000, Shader.TileMode.CLAMP));
        }
        requestLayout();
        invalidate();
    }

    private float sp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, c.getResources().getDisplayMetrics()) * scale;
    }

    /** Parameter window overlay: bawah tengah + geseran yang disimpan user. Dipakai service dan pratinjau di app. */
    static WindowManager.LayoutParams params(Context c) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        lp.x = Math.round(Boost.hudX(c) * dm.widthPixels);
        lp.y = Math.round(Boost.hudY(c) * dm.heightPixels);
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return lp;
    }

    /** Dipanggil dari main thread. Ping > 100 ms merah, > 60 ms kuning. */
    void set(String[] v, int c, int pingMs) {
        for (int i = 0; i < N && i < v.length; i++) { vals[i] = v[i] == null ? "--" : v[i]; split(i); }
        color = c;
        pingColor = pingMs > 100 ? HOT : pingMs > 60 ? WARM : 0xFF00FF88;
        requestLayout();
        invalidate();
    }

    /** Label THROTTLE di header, mis. "THROTTLE GPU". Kosong = tidak ada. Dipanggil sebelum set(). */
    void setFlag(String f) { flag = f == null ? "" : f; }

    /** Pecah string metrik jadi nilai utama (baris 1) dan keterangan (baris 2). */
    private void split(int k) {
        String v = vals[k].trim();
        String[] t = v.split("\\s+");
        switch (k) {
            case PING: {                                  // "42ms|PING \u00b18" = nilai | keterangan
                int bar = v.indexOf('|');
                if (bar > 0) { main[k] = v.substring(0, bar); sub[k] = v.substring(bar + 1); }
                else { main[k] = v; sub[k] = "PING"; }
                break;
            }
            case RAM: main[k] = v; sub[k] = "RAM"; break;
            case CPU: case GPU: {
                main[k] = t[0];
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < t.length; i++) { if (sb.length() > 0) sb.append(' '); sb.append(t[i]); }
                sub[k] = sb.toString();
                break;
            }
            case BAT: main[k] = t.length > 1 ? t[1] : t[0]; sub[k] = t.length > 1 ? t[0] : ""; break;
            default: main[k] = v; sub[k] = "";
        }
    }

    private int heat() { return color == HOT ? 3 : color == WARM ? 2 : 1; }

    private float flamesW() { return flameS * 0.8f * 3 + 2 * d * 2; }

    private float flagW() { return flag.isEmpty() ? 0 : flagP.measureText(flag) + 8 * d + 4 * d; }

    private void measureAll() {
        float fpsW = fpsP.measureText(main[FPS]) + 3 * d + fpsLbl.measureText("FPS");
        bodyW = fpsW;
        for (int k : COLS) {
            colW[k] = Math.max(icon + gapI + mainP.measureText(main[k]), subP.measureText(sub[k]));
            bodyW += colGap + colW[k];
        }
        headerH = title.getFontSpacing();
        row1H = Math.max(icon, mainP.getFontSpacing());
        bodyH = Math.max(row1H + subP.getFontSpacing(), fpsP.getFontSpacing());
        headerNeed = title.measureText("// " + TITLE) + 14 * d + flagW() + flamesW();
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        measureAll();
        float w = m * 2 + accentW + padX * 2 + Math.max(bodyW, headerNeed);
        float h = m * 2 + padTop + headerH + 2 * d + bodyH + padBot;
        setMeasuredDimension((int) Math.ceil(w), (int) Math.ceil(h));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        border.setShader(new LinearGradient(0, 0, w, 0, acc, (acc & 0x00FFFFFF) | 0x66000000, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas c) {
        float L = m, T = m, R = getWidth() - m, B = getHeight() - m;
        // panel sudut terpotong (kiri atas & kanan bawah)
        panel.reset();
        panel.moveTo(L + cut, T); panel.lineTo(R, T); panel.lineTo(R, B - cut);
        panel.lineTo(R - cut, B); panel.lineTo(L, B); panel.lineTo(L, T + cut); panel.close();
        fill.setColor(PANEL);
        c.drawPath(panel, fill);
        c.drawPath(panel, border);
        // aksen: batang kiri (warna status) + sudut terang kanan atas & kiri bawah
        fill.setColor(color);
        r.set(L, T + cut, L + accentW, B);
        c.drawRect(r, fill);
        c.drawLine(R - 24 * d, T, R, T, hi);
        c.drawLine(L + accentW, B, L + 24 * d, B, hi);

        float x0 = L + accentW + padX;
        float innerW = (R - L) - accentW - padX * 2;

        // ---- header ----
        float hy = T + padTop;
        float by = hy - title.ascent();
        title.setColor(acc);
        c.drawText("//", x0, by, title);
        title.setColor(WHITE);
        c.drawText(TITLE, x0 + title.measureText("// "), by, title);
        // api panas, rata kanan
        float fw = flameS * 0.8f, fx = R - padX - flamesW() + 2 * d;
        int lit = heat();
        for (int i = 0; i < 3; i++) {
            flame(c, fx + i * (fw + 2 * d), hy + (headerH - flameS) / 2f, flameS, i < lit ? color : 0x33FFFFFF);
        }
        // chip THROTTLE di kiri api
        if (!flag.isEmpty()) {
            float cw = flagP.measureText(flag) + 8 * d;
            float cx = R - padX - flamesW() - 4 * d - cw;
            r.set(cx, hy, cx + cw, hy + headerH);
            fill.setColor((acc & 0x00FFFFFF) | 0x55000000);
            c.drawRoundRect(r, 2 * d, 2 * d, fill);
            c.drawText(flag, cx + 4 * d, hy + (headerH - (flagP.ascent() + flagP.descent())) / 2f, flagP);
        }

        // ---- body ----
        float y0 = hy + headerH + 2 * d;
        float x = x0 + (innerW - bodyW) / 2f;
        // FPS besar
        float base = y0 + (bodyH - fpsP.getFontSpacing()) / 2f - fpsP.ascent();
        c.drawText(main[FPS], x, base, fpsP);
        float fx2 = x + fpsP.measureText(main[FPS]) + 3 * d;
        c.drawText("FPS", fx2, base, fpsLbl);
        x = fx2 + fpsLbl.measureText("FPS");
        // kolom
        float colTop = y0 + (bodyH - (row1H + subP.getFontSpacing())) / 2f;
        for (int k : COLS) {
            drawSep(c, x + colGap / 2f, y0 + 1 * d, y0 + bodyH - 1 * d);
            x += colGap;
            int ic = k == PING ? pingColor : color;
            stroke.setColor(ic);
            fill.setColor(ic);
            drawIcon(c, k, x, colTop + (row1H - icon) / 2f);
            mainP.setColor(k == PING ? pingColor : WHITE);
            c.drawText(main[k], x + icon + gapI, colTop + (row1H - mainP.getFontSpacing()) / 2f - mainP.ascent(), mainP);
            c.drawText(sub[k], x, colTop + row1H - subP.ascent(), subP);
            x += colW[k];
        }
    }

    /** Garis pemisah miring tipis. */
    private void drawSep(Canvas c, float x, float y1, float y2) {
        stroke.setColor(0x33FFFFFF);
        c.drawLine(x + 2 * d, y1, x - 2 * d, y2, stroke);
    }

    private void flame(Canvas c, float x, float y, float s, int col) {
        fl.reset();
        fl.moveTo(x + s * .4f, y);
        fl.cubicTo(x + s * .5f, y + s * .25f, x + s * .74f, y + s * .4f, x + s * .7f, y + s * .68f);
        fl.cubicTo(x + s * .67f, y + s * .9f, x + s * .52f, y + s, x + s * .4f, y + s);
        fl.cubicTo(x + s * .28f, y + s, x + s * .11f, y + s * .9f, x + s * .09f, y + s * .68f);
        fl.cubicTo(x + s * .08f, y + s * .45f, x + s * .24f, y + s * .35f, x + s * .4f, y);
        fl.close();
        fill.setColor(col);
        c.drawPath(fl, fill);
    }

    private void drawIcon(Canvas c, int kind, float x, float y) {
        float s = icon;
        switch (kind) {
            case FPS: {                                   // speedometer
                float cx = x + s / 2f, cy = y + s / 2f;
                c.drawCircle(cx, cy, s / 2f - 0.8f, stroke);
                c.drawLine(cx, cy, cx + s * 0.22f, cy - s * 0.28f, stroke);
                c.drawCircle(cx, cy, s * 0.07f, fill);
                break;
            }
            case PING: {                                  // bar sinyal
                float bw = s / 3f - 1.2f;
                for (int k = 0; k < 3; k++) {
                    float h = s * (0.4f + 0.3f * k);
                    float l = x + k * (s / 3f) + 0.6f;
                    r.set(l, y + s - h, l + bw, y + s);
                    c.drawRoundRect(r, 1f, 1f, fill);
                }
                break;
            }
            case CPU: {                                   // chip + kaki
                r.set(x + s * 0.22f, y + s * 0.22f, x + s * 0.78f, y + s * 0.78f);
                c.drawRoundRect(r, 1.5f, 1.5f, stroke);
                r.set(x + s * 0.42f, y + s * 0.42f, x + s * 0.58f, y + s * 0.58f);
                c.drawRect(r, fill);
                for (float p : new float[]{0.38f, 0.62f}) {
                    c.drawLine(x + s * p, y, x + s * p, y + s * 0.22f, stroke);
                    c.drawLine(x + s * p, y + s * 0.78f, x + s * p, y + s, stroke);
                    c.drawLine(x, y + s * p, x + s * 0.22f, y + s * p, stroke);
                    c.drawLine(x + s * 0.78f, y + s * p, x + s, y + s * p, stroke);
                }
                break;
            }
            case GPU: {                                   // kartu grafis + kipas
                r.set(x, y + s * 0.2f, x + s, y + s * 0.8f);
                c.drawRoundRect(r, 1.5f, 1.5f, stroke);
                c.drawCircle(x + s * 0.5f, y + s * 0.5f, s * 0.17f, stroke);
                break;
            }
            case RAM: {                                   // keping RAM
                r.set(x, y + s * 0.2f, x + s, y + s * 0.7f);
                c.drawRoundRect(r, 1.5f, 1.5f, stroke);
                for (float p : new float[]{0.18f, 0.43f, 0.68f}) {
                    r.set(x + s * p, y + s * 0.34f, x + s * (p + 0.14f), y + s * 0.56f);
                    c.drawRect(r, fill);
                }
                for (float p : new float[]{0.2f, 0.5f, 0.8f}) {
                    c.drawLine(x + s * p, y + s * 0.7f, x + s * p, y + s * 0.88f, stroke);
                }
                break;
            }
            default: {                                    // baterai
                r.set(x, y + s * 0.25f, x + s * 0.88f, y + s * 0.75f);
                c.drawRoundRect(r, 1.5f, 1.5f, stroke);
                r.set(x + s * 0.88f, y + s * 0.4f, x + s, y + s * 0.6f);
                c.drawRect(r, fill);
                r.set(x + 2.2f, y + s * 0.25f + 2.2f, x + 2.2f + (s * 0.88f - 4.4f) * 0.65f, y + s * 0.75f - 2.2f);
                c.drawRect(r, fill);
                break;
            }
        }
    }
}

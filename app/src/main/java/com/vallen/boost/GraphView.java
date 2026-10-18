package com.vallen.boost;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.TypedValue;
import android.view.View;
import java.util.Locale;

/** Grafik sesi terakhir: garis FPS (ijo) + garis suhu chip (kuning) per detik. */
final class GraphView extends View {
    private static final int GREEN = 0xFF00FF88, AMBER = 0xFFFFB020, GRID = 0xFF232838, DIM = 0xFF8A93A6;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint();
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float d;
    private int[] fps = new int[0], temp = new int[0];

    GraphView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.8f * d);
        line.setStrokeJoin(Paint.Join.ROUND);
        grid.setColor(GRID);
        grid.setStrokeWidth(1f);
        txt.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, c.getResources().getDisplayMetrics()));
    }

    void set(int[] f, int[] t) {
        fps = f;
        temp = t;
        invalidate();
    }

    /** Ambil deret angka dari "f=1,2,3;t=40,41". */
    static int[] parse(String g, String key) {
        for (String part : g.split(";")) {
            if (!part.startsWith(key + "=")) continue;
            String body = part.substring(key.length() + 1);
            if (body.isEmpty()) return new int[0];
            String[] xs = body.split(",");
            int[] a = new int[xs.length];
            for (int i = 0; i < xs.length; i++) {
                try { a[i] = Integer.parseInt(xs[i].trim()); } catch (NumberFormatException e) { a[i] = 0; }
            }
            return a;
        }
        return new int[0];
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), (int) (150 * d));
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), p = 12 * d;
        float left = p, right = w - p, top = p + 14 * d, bottom = h - p;
        if (fps.length < 2 && temp.length < 2) {
            txt.setColor(DIM);
            txt.setTextAlign(Paint.Align.CENTER);
            c.drawText("Belum ada data grafik (main dengan HUD nyala)", w / 2f, h / 2f, txt);
            return;
        }
        for (int i = 0; i < 3; i++) {
            float y = top + (bottom - top) * i / 2f;
            c.drawLine(left, y, right, y, grid);
        }
        txt.setTextAlign(Paint.Align.LEFT);
        if (fps.length >= 2) {
            int max = 0;
            long sum = 0;
            for (int v : fps) { max = Math.max(max, v); sum += v; }
            int top30 = Math.max(60, (max + 29) / 30 * 30);
            plot(c, fps, 0, top30, left, right, top, bottom, GREEN);
            txt.setColor(GREEN);
            c.drawText(String.format(Locale.US, "FPS  maks %d  rata %d", max, sum / fps.length), left, p + 8 * d, txt);
        }
        if (temp.length >= 2) {
            int lo = Integer.MAX_VALUE, hi = 0;
            for (int v : temp) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
            plot(c, temp, lo, Math.max(hi, lo + 4), left, right, top, bottom, AMBER);
            txt.setColor(AMBER);
            txt.setTextAlign(Paint.Align.RIGHT);
            c.drawText(String.format(Locale.US, "SUHU  %d\u2013%d\u00b0C", lo, hi), right, p + 8 * d, txt);
        }
    }

    private void plot(Canvas c, int[] a, int lo, int hi, float l, float r, float t, float b, int color) {
        path.reset();
        int n = a.length;
        for (int i = 0; i < n; i++) {
            float x = l + (r - l) * i / (n - 1);
            float f = (a[i] - lo) / (float) Math.max(1, hi - lo);
            float y = b - (b - t) * Math.max(0f, Math.min(1f, f));
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        line.setColor(color);
        c.drawPath(path, line);
    }
}

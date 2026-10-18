package com.vallen.boost;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Layar loading pas Boost ditekan: gadis anime berkedip + judul VALLEN BOOST berkilau + bar progress + log langkah asli. */
final class LaunchOverlay extends FrameLayout {
    private static final int PINK = 0xFFFF5FA2, VIOLET = 0xFFB78CFF, GREEN = 0xFF54E69A, RED = 0xFFFF5C7A, AMBER = 0xFFFFC857;
    private static final int MAX_LOG = 7;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LinearLayout box, col;
    private final GirlView girl;
    private final TextView title, sub, logTv;
    private final BarView bar;
    private final java.util.ArrayList<String> log = new java.util.ArrayList<>();
    private float shown = 0f, target = 0f;
    private int frame;
    private ValueAnimator anim;

    LaunchOverlay(Context c) {
        super(c);
        setClickable(true);
        setVisibility(GONE);
        setBackgroundColor(0xF2060606);                       // hitam polos, tanpa warna biru

        box = new LinearLayout(c);
        box.setGravity(Gravity.CENTER);

        girl = new GirlView(c);
        box.addView(girl, new LinearLayout.LayoutParams(dp(130), dp(130)));

        col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);

        title = new TextView(c);
        title.setText("VALLEN BOOST");
        title.setTextSize(24);
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        title.setLetterSpacing(0.18f);
        title.setShadowLayer(dp(10), 0, 0, 0xAAFF5FA2);
        col.addView(title);

        sub = new TextView(c);
        sub.setTextSize(11);
        sub.setTextColor(PINK);
        sub.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        sub.setLetterSpacing(0.2f);
        sub.setPadding(0, dp(2), 0, 0);
        col.addView(sub);

        bar = new BarView(c);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(250), dp(10));
        bp.topMargin = dp(14);
        col.addView(bar, bp);

        logTv = new TextView(c);
        logTv.setTextSize(10.5f);
        logTv.setTypeface(Typeface.MONOSPACE);
        logTv.setLineSpacing(dp(2), 1f);
        logTv.setMinLines(MAX_LOG);
        logTv.setPadding(0, dp(10), 0, 0);
        col.addView(logTv, new LinearLayout.LayoutParams(dp(250), -2));

        box.addView(col, new LinearLayout.LayoutParams(-2, -2));
        addView(box, new LayoutParams(-2, -2, Gravity.CENTER));
        layoutFor(false);
        render();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layoutFor(w > h);
    }

    /** Landscape: gadis di kiri, teks di kanan. Portrait: gadis di atas. */
    private void layoutFor(boolean land) {
        box.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        col.setGravity(land ? Gravity.START | Gravity.CENTER_VERTICAL : Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams gp = (LinearLayout.LayoutParams) girl.getLayoutParams();
        int s = land ? dp(120) : dp(140);
        gp.width = s;
        gp.height = s;
        gp.rightMargin = land ? dp(16) : 0;
        gp.bottomMargin = land ? 0 : dp(6);
        girl.setLayoutParams(gp);
    }

    /** Tampilkan dari awal. Aman dipanggil dari thread UI. */
    void begin(String gameName) {
        log.clear();
        shown = 0f;
        target = 0f;
        sub.setText("BOOST " + gameName.toUpperCase());
        setAlpha(1f);
        setVisibility(VISIBLE);
        girl.setMood(0);
        title.setAlpha(0f);                                   // judul muncul membal
        title.setScaleX(0.6f);
        title.setScaleY(0.6f);
        title.setPivotX(0f);
        title.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(550)
            .setInterpolator(new OvershootInterpolator(2f)).start();
        render();
        if (anim == null) {
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(1000);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.addUpdateListener(a -> {
                frame++;
                shown += (target - shown) * 0.18f;            // bar ngejar target dengan halus
                girl.tick();
                bar.tick(shown);
                shimmer();
                render();
            });
        }
        if (!anim.isStarted()) anim.start();
    }

    /** Dari thread mana pun: langkah baru, pct 0..100. */
    void step(final int pct, final String label) {
        ui.post(() -> {
            target = Math.max(target, Math.min(1f, pct / 100f));
            log.add("[..] " + label);
            while (log.size() > MAX_LOG) log.remove(0);
            if (log.size() >= 2) {                            // langkah sebelumnya dianggap selesai
                int i = log.size() - 2;
                log.set(i, log.get(i).replace("[..]", "[OK]"));
            }
            render();
        });
    }

    /** Ganti ke fase pemeriksaan: log dikosongkan, subjudul diganti. */
    void phase(final String t) {
        ui.post(() -> { log.clear(); sub.setText(t); render(); });
    }

    /** Satu hasil pemeriksaan: [OK] kalau beneran nyala, [!!] kalau tidak. */
    void check(final boolean ok, final String label) {
        ui.post(() -> {
            log.add((ok ? "[OK] " : "[!!] ") + label);
            while (log.size() > MAX_LOG) log.remove(0);
            render();
        });
    }

    /** Selesai: bar penuh, gadis senyum (atau cemas kalau ada yang gagal), lalu pudar. */
    void end(final Runnable after) {
        ui.post(() -> {
            target = 1f;
            boolean bad = hasFail();
            girl.setMood(bad ? 2 : 1);
            sub.setText(bad ? "ADA YANG GAGAL" : "SIAP TEMPUR");
            ui.postDelayed(() -> animate().alpha(0f).setDuration(300).withEndAction(() -> {
                setVisibility(GONE);
                if (anim != null) anim.cancel();
                if (after != null) after.run();
            }).start(), 1500);
        });
    }

    private boolean hasFail() {
        for (String s : log) if (s.startsWith("[!!]")) return true;
        return false;
    }

    /** Kilau putih lewat di judul. */
    private void shimmer() {
        float tw = Math.max(1f, title.getPaint().measureText(title.getText().toString()));
        float x = (frame * 7f) % (tw + 240f) - 120f;
        title.getPaint().setShader(new LinearGradient(x - 70f, 0, x + 70f, 0,
            new int[]{0xFFFF7EB6, 0xFFFFFFFF, 0xFFFF7EB6}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        title.invalidate();
    }

    private void render() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < log.size(); i++) {
            if (i > 0) sb.append('\n');
            String l = log.get(i);
            int s = sb.length();
            sb.append(l);
            int tag = l.startsWith("[OK]") ? GREEN : l.startsWith("[!!]") ? RED : AMBER;
            sb.setSpan(new ForegroundColorSpan(tag), s, s + Math.min(4, l.length()), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(0xFFB4B8C2), s + Math.min(4, l.length()), sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        logTv.setText(sb);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    /** Bar progress: gradasi pink-ungu, garis kilau bergerak, titik cahaya di ujung. */
    private static final class BarView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path clip = new Path();
        private float prog;
        private int t;

        BarView(Context c) { super(c); }

        void tick(float pr) { prog = pr; t++; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setShader(null);
            p.setColor(0x26FFFFFF);
            r.set(0, 0, w, h);
            c.drawRoundRect(r, h / 2, h / 2, p);
            float fw = Math.max(h, w * prog);
            r.set(0, 0, fw, h);
            p.setShader(new LinearGradient(0, 0, w, 0, PINK, VIOLET, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, h / 2, h / 2, p);
            p.setShader(null);
            c.save();                                         // garis kilau miring bergerak di dalam bar
            clip.reset();
            clip.addRoundRect(r, h / 2, h / 2, Path.Direction.CW);
            c.clipPath(clip);
            p.setColor(0x44FFFFFF);
            float step = h * 2.2f, off = (t * 2.5f) % step;
            for (float x = -step + off; x < fw + step; x += step) {
                clip.reset();
                clip.moveTo(x, h);
                clip.lineTo(x + h * 0.6f, 0);
                clip.lineTo(x + h * 1.1f, 0);
                clip.lineTo(x + h * 0.5f, h);
                clip.close();
                c.drawPath(clip, p);
            }
            c.restore();
            float hx = Math.max(h / 2, fw - h / 2);           // titik cahaya di ujung
            p.setColor(0xFFFFFFFF);
            p.setShadowLayer(h * 1.2f, 0, 0, PINK);
            c.drawCircle(hx, h / 2, h * 0.38f, p);
            p.clearShadowLayer();
        }
    }

    /** Gadis anime chibi digambar langsung: twintail goyang, mata besar berkedip, headset gaming. mood 0 normal, 1 senang, 2 cemas. */
    private static final class GirlView extends View {
        private static final int HAIR = 0xFFFF9AD0, HAIR_D = 0xFFE86BB0, OUTLINE = 0xFFC9488F, SKIN = 0xFFFFE9DC, INK = 0xFF3A1F3F;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();
        private int t, mood;

        GirlView(Context c) { super(c); }

        void setMood(int m) { mood = m; }
        void tick() { t++; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float S = Math.min(getWidth(), getHeight());
            c.save();
            c.translate((getWidth() - S) / 2f, (float) Math.sin(t * 0.07) * S * 0.014f);   // melayang pelan
            float sway = (float) Math.sin(t * 0.06);
            sparkles(c, S);
            // rambut belakang + twintail
            p.setStyle(Paint.Style.FILL);
            p.setShader(null);
            p.setColor(HAIR_D);
            r.set(0.17f * S, 0.12f * S, 0.83f * S, 0.82f * S);
            c.drawOval(r, p);
            tail(c, S, false, sway * 7f);
            tail(c, S, true, -sway * 7f);
            // leher + badan
            p.setColor(SKIN);
            r.set(0.45f * S, 0.74f * S, 0.55f * S, 0.90f * S);
            c.drawRect(r, p);
            p.setColor(0xFF22222E);
            r.set(0.24f * S, 0.86f * S, 0.76f * S, 1.14f * S);
            c.drawRoundRect(r, 0.1f * S, 0.1f * S, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(0.02f * S);
            p.setColor(PINK);
            r.set(0.36f * S, 0.80f * S, 0.64f * S, 0.98f * S);
            c.drawArc(r, 20, 140, false, p);
            // wajah
            p.setStyle(Paint.Style.FILL);
            p.setColor(SKIN);
            r.set(0.25f * S, 0.27f * S, 0.75f * S, 0.80f * S);
            c.drawOval(r, p);
            blush(c, S);
            eyes(c, S);
            mouth(c, S);
            // poni, rambut samping, ahoge
            hairFront(c, S, sway);
            headset(c, S);
            if (mood == 2) sweat(c, S);
            c.restore();
        }

        private void tail(Canvas c, float S, boolean mirror, float angle) {
            c.save();
            if (mirror) c.scale(-1f, 1f, 0.5f * S, 0f);
            c.rotate(angle, 0.20f * S, 0.36f * S);
            path.reset();
            path.moveTo(0.20f * S, 0.34f * S);
            path.cubicTo(0.01f * S, 0.44f * S, -0.01f * S, 0.80f * S, 0.10f * S, 0.98f * S);
            path.cubicTo(0.21f * S, 0.84f * S, 0.27f * S, 0.62f * S, 0.28f * S, 0.40f * S);
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, 0.34f * S, 0, 0.98f * S, HAIR, VIOLET, Shader.TileMode.CLAMP));
            c.drawPath(path, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(0.01f * S);
            p.setColor(OUTLINE);
            c.drawPath(path, p);
            p.setStyle(Paint.Style.FILL);                     // ikat rambut
            p.setColor(0xFFFF3D8B);
            c.drawCircle(0.22f * S, 0.37f * S, 0.035f * S, p);
            c.restore();
        }

        private void blush(Canvas c, float S) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x80FF7EA8);
            r.set(0.265f * S, 0.64f * S, 0.355f * S, 0.69f * S);
            c.drawOval(r, p);
            r.set(0.645f * S, 0.64f * S, 0.735f * S, 0.69f * S);
            c.drawOval(r, p);
        }

        private void eyes(Canvas c, float S) {
            boolean blink = (t % 120) > 113;
            float ew = 0.16f * S, eh = 0.19f * S, cy = (mood == 2 ? 0.57f : 0.55f) * S;
            float[] xs = {0.37f * S, 0.63f * S};
            for (int k = 0; k < 2; k++) {
                float cx = xs[k];
                if (mood == 1 && !blink) {                    // mata senyum ^ ^
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(0.025f * S);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    p.setColor(INK);
                    r.set(cx - ew / 2, cy - eh * 0.3f, cx + ew / 2, cy + eh * 0.5f);
                    c.drawArc(r, 200, 140, false, p);
                    p.setStrokeCap(Paint.Cap.BUTT);
                    continue;
                }
                float f = blink ? 0.1f : 1f;
                float h = eh * f;
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFFFFFF);
                r.set(cx - ew / 2, cy - h / 2, cx + ew / 2, cy + h / 2);
                c.drawOval(r, p);
                if (!blink) {
                    p.setShader(new LinearGradient(0, cy - h / 2, 0, cy + h / 2, 0xFF7A3CFF, 0xFFFF8AD8, Shader.TileMode.CLAMP));
                    r.set(cx - ew * 0.42f, cy - h * 0.46f, cx + ew * 0.42f, cy + h * 0.46f);
                    c.drawOval(r, p);
                    p.setShader(null);
                    p.setColor(0xFF2A1238);
                    r.set(cx - ew * 0.2f, cy - h * 0.2f, cx + ew * 0.2f, cy + h * 0.2f);
                    c.drawOval(r, p);
                    p.setColor(0xFFFFFFFF);                   // kilau mata
                    c.drawCircle(cx - ew * 0.16f, cy - h * 0.2f, ew * 0.15f, p);
                    c.drawCircle(cx + ew * 0.17f, cy + h * 0.2f, ew * 0.07f, p);
                }
                p.setStyle(Paint.Style.STROKE);               // garis bulu mata atas
                p.setStrokeWidth(0.022f * S);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setColor(INK);
                r.set(cx - ew / 2, cy - h / 2, cx + ew / 2, cy + h / 2);
                c.drawArc(r, 185, 170, false, p);
                p.setStrokeCap(Paint.Cap.BUTT);
                // alis
                p.setStrokeWidth(0.012f * S);
                p.setColor(0xFFB5527F);
                float by = cy - eh * 0.72f, dir = (k == 0 ? 1f : -1f);
                if (mood == 2) c.drawLine(cx - dir * 0.05f * S, by - 0.012f * S, cx + dir * 0.05f * S, by + 0.016f * S, p);
                else c.drawLine(cx - 0.05f * S, by, cx + 0.05f * S, by - 0.008f * S, p);
            }
        }

        private void mouth(Canvas c, float S) {
            float cx = 0.5f * S, cy = 0.705f * S;
            p.setColor(0xFFB5384F);
            if (mood == 1) {                                  // senyum lebar
                p.setStyle(Paint.Style.FILL);
                r.set(cx - 0.07f * S, cy - 0.05f * S, cx + 0.07f * S, cy + 0.05f * S);
                c.drawArc(r, 0, 180, true, p);
                p.setColor(0xFFFF8FA8);
                r.set(cx - 0.035f * S, cy + 0.012f * S, cx + 0.035f * S, cy + 0.048f * S);
                c.drawOval(r, p);
            } else if (mood == 2) {                           // cemberut
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(0.015f * S);
                r.set(cx - 0.05f * S, cy - 0.005f * S, cx + 0.05f * S, cy + 0.05f * S);
                c.drawArc(r, 200, 140, false, p);
            } else {                                          // lagi ngomong
                float open = 0.5f + 0.5f * (float) Math.sin(t * 0.35);
                p.setStyle(Paint.Style.FILL);
                r.set(cx - 0.03f * S, cy - (0.008f + 0.012f * open) * S, cx + 0.03f * S, cy + (0.008f + 0.02f * open) * S);
                c.drawOval(r, p);
            }
        }

        private void hairFront(Canvas c, float S, float sway) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(HAIR);
            path.reset();                                     // poni
            path.moveTo(0.20f * S, 0.50f * S);
            path.cubicTo(0.14f * S, 0.14f * S, 0.86f * S, 0.14f * S, 0.80f * S, 0.50f * S);
            path.lineTo(0.75f * S, 0.40f * S);
            path.quadTo(0.70f * S, 0.48f * S, 0.64f * S, 0.36f * S);
            path.quadTo(0.58f * S, 0.47f * S, 0.50f * S, 0.34f * S);
            path.quadTo(0.42f * S, 0.47f * S, 0.36f * S, 0.36f * S);
            path.quadTo(0.30f * S, 0.48f * S, 0.25f * S, 0.40f * S);
            path.close();
            c.drawPath(path, p);
            for (int k = 0; k < 2; k++) {                     // rambut samping
                c.save();
                if (k == 1) c.scale(-1f, 1f, 0.5f * S, 0f);
                path.reset();
                path.moveTo(0.21f * S, 0.40f * S);
                path.quadTo(0.17f * S, 0.62f * S, 0.23f * S, 0.74f * S);
                path.quadTo(0.28f * S, 0.62f * S, 0.30f * S, 0.42f * S);
                path.close();
                c.drawPath(path, p);
                c.restore();
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(0.01f * S);
            p.setColor(OUTLINE);
            path.reset();
            path.moveTo(0.20f * S, 0.50f * S);
            path.cubicTo(0.14f * S, 0.14f * S, 0.86f * S, 0.14f * S, 0.80f * S, 0.50f * S);
            c.drawPath(path, p);
            p.setColor(0x66FFFFFF);                           // kilau rambut
            p.setStrokeWidth(0.02f * S);
            p.setStrokeCap(Paint.Cap.ROUND);
            r.set(0.30f * S, 0.17f * S, 0.70f * S, 0.40f * S);
            c.drawArc(r, 200, 70, false, p);
            p.setStrokeCap(Paint.Cap.BUTT);
            p.setColor(HAIR);                                 // ahoge
            p.setStrokeWidth(0.022f * S);
            p.setStrokeCap(Paint.Cap.ROUND);
            path.reset();
            path.moveTo(0.50f * S, 0.20f * S);
            path.quadTo((0.46f + sway * 0.02f) * S, 0.08f * S, (0.56f + sway * 0.03f) * S, 0.05f * S);
            c.drawPath(path, p);
            p.setStrokeCap(Paint.Cap.BUTT);
        }

        private void headset(Canvas c, float S) {
            float glow = 0.5f + 0.5f * (float) Math.sin(t * 0.12);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(0.035f * S);
            p.setColor(0xFF2B2B38);
            r.set(0.14f * S, 0.03f * S, 0.86f * S, 0.79f * S);
            c.drawArc(r, 192, 156, false, p);
            for (int k = 0; k < 2; k++) {                     // ear cup kiri-kanan
                c.save();
                if (k == 1) c.scale(-1f, 1f, 0.5f * S, 0f);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFF2B2B38);
                r.set(0.09f * S, 0.33f * S, 0.20f * S, 0.57f * S);
                c.drawRoundRect(r, 0.04f * S, 0.04f * S, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(0.012f * S);
                p.setColor(PINK);
                p.setAlpha((int) (140 + 115 * glow));
                r.set(0.11f * S, 0.37f * S, 0.18f * S, 0.53f * S);
                c.drawRoundRect(r, 0.03f * S, 0.03f * S, p);
                p.setAlpha(255);
                c.restore();
            }
            p.setStyle(Paint.Style.STROKE);                   // mic
            p.setStrokeWidth(0.012f * S);
            p.setColor(0xFF2B2B38);
            path.reset();
            path.moveTo(0.11f * S, 0.55f * S);
            path.quadTo(0.14f * S, 0.78f * S, 0.36f * S, 0.78f * S);
            c.drawPath(path, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(PINK);
            c.drawCircle(0.37f * S, 0.78f * S, 0.022f * S, p);
        }

        private void sweat(Canvas c, float S) {
            float dy = (t % 60) / 60f * 0.05f * S;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFBFEFFF);
            path.reset();
            float x = 0.74f * S, y = 0.36f * S + dy;
            path.moveTo(x, y - 0.04f * S);
            path.quadTo(x + 0.035f * S, y + 0.01f * S, x, y + 0.03f * S);
            path.quadTo(x - 0.035f * S, y + 0.01f * S, x, y - 0.04f * S);
            path.close();
            c.drawPath(path, p);
        }

        private void sparkles(Canvas c, float S) {
            float[][] pos = {{0.06f, 0.14f, 0f}, {0.93f, 0.22f, 1.7f}, {0.10f, 0.62f, 3.1f}, {0.92f, 0.70f, 4.4f}};
            p.setStyle(Paint.Style.FILL);
            for (float[] s : pos) {
                float a = 0.5f + 0.5f * (float) Math.sin(t * 0.09 + s[2]);
                float sz = (0.02f + 0.03f * a) * S, x = s[0] * S, y = s[1] * S;
                p.setColor(0xFFFFFFFF);
                p.setAlpha((int) (60 + 195 * a));
                path.reset();
                path.moveTo(x, y - sz);
                path.quadTo(x, y, x + sz, y);
                path.quadTo(x, y, x, y + sz);
                path.quadTo(x, y, x - sz, y);
                path.quadTo(x, y, x, y - sz);
                path.close();
                c.drawPath(path, p);
            }
            p.setAlpha(255);
        }
    }
}

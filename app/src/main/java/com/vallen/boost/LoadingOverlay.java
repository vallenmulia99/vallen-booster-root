package com.vallen.boost;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Layar loading pas app dibuka: logo berdenyut + progress bar, lalu pudar ke lobby. */
final class LoadingOverlay extends FrameLayout {
    private static final long MIN_MS = 1600;     // minimal tampil, biar tidak kedip
    private static final int GREEN = 0xFF00FF88;

    private final ImageView back;
    private final TextView status;
    private final View fill;
    private final ValueAnimator prog;
    private final ObjectAnimator pulse;
    private boolean done;

    LoadingOverlay(Context c) {
        super(c);
        setClickable(true);                       // sentuhan tidak tembus ke lobby
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0xFF0B0F2A, 0xFF2B1655, 0xFF07080C}));

        back = new ImageView(c);
        back.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(back, new LayoutParams(-1, -1));
        View dim = new View(c);
        dim.setBackgroundColor(0xB005060A);
        addView(dim, new LayoutParams(-1, -1));

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView logo = new ImageView(c);
        logo.setImageResource(R.drawable.icon_vallen);
        box.addView(logo, new LinearLayout.LayoutParams(dp(76), dp(76)));

        TextView title = new TextView(c);
        title.setText("VALLEN BOOST");
        title.setTextSize(22);
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        title.setLetterSpacing(0.2f);
        title.setPadding(0, dp(12), 0, 0);
        box.addView(title);

        TextView sub = new TextView(c);
        sub.setText("GAME BOOSTER");
        sub.setTextSize(10);
        sub.setTextColor(GREEN);
        sub.setLetterSpacing(0.3f);
        box.addView(sub);

        FrameLayout track = new FrameLayout(c);
        GradientDrawable tb = new GradientDrawable();
        tb.setColor(0x33FFFFFF);
        tb.setCornerRadius(dp(3));
        track.setBackground(tb);
        fill = new View(c);
        GradientDrawable fb = new GradientDrawable();
        fb.setColor(GREEN);
        fb.setCornerRadius(dp(3));
        fill.setBackground(fb);
        fill.setPivotX(0f);
        fill.setScaleX(0f);
        track.addView(fill, new LayoutParams(-1, -1));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(260), dp(4));
        tp.topMargin = dp(26);
        box.addView(track, tp);

        status = new TextView(c);
        status.setTextSize(11);
        status.setTextColor(0xFF8A93A6);
        status.setTypeface(Typeface.MONOSPACE);
        status.setPadding(0, dp(10), 0, 0);
        box.addView(status);

        addView(box, new LayoutParams(-2, -2, Gravity.CENTER));

        pulse = ObjectAnimator.ofPropertyValuesHolder(logo,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f));
        pulse.setDuration(900);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.REVERSE);

        prog = ValueAnimator.ofFloat(0f, 0.92f);
        prog.setDuration(2600);
        prog.setInterpolator(new DecelerateInterpolator());
        prog.addUpdateListener(a -> set((float) a.getAnimatedValue()));
        set(0f);
    }

    /** Gambar latar sama dengan lobby; null = pakai gradasi bawaan. */
    void setBackdrop(Bitmap b) { back.setImageBitmap(b); }

    /** Panggil kalau pemeriksaan awal sudah beres; overlay pudar setelah minimal MIN_MS sejak startedAt. */
    void finish(long startedAt) {
        long wait = Math.max(0, MIN_MS - (SystemClock.uptimeMillis() - startedAt));
        postDelayed(this::close, wait);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!done) { prog.start(); pulse.start(); }
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        prog.cancel();
        pulse.cancel();
    }

    private void close() {
        if (done) return;
        done = true;
        prog.cancel();
        ValueAnimator fin = ValueAnimator.ofFloat(fill.getScaleX(), 1f);
        fin.setDuration(250);
        fin.addUpdateListener(a -> set((float) a.getAnimatedValue()));
        fin.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                LoadingOverlay.this.animate().alpha(0f).setDuration(350).withEndAction(() -> {
                    pulse.cancel();
                    setVisibility(GONE);
                }).start();
            }
        });
        fin.start();
    }

    private void set(float p) {
        fill.setScaleX(p);
        String s = p < 0.3f ? "Memeriksa akses root..."
            : p < 0.6f ? "Membaca sensor perangkat..."
            : p < 0.9f ? "Memuat daftar game..."
            : p < 1f ? "Menyiapkan tampilan..." : "Siap";
        status.setText(s + "  " + Math.round(p * 100) + "%");
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}

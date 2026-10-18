package com.vallen.boost;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.TextureView;

/**
 * Background video looping tanpa suara, di-crop memenuhi layar (center-crop).
 * Dilepas total pas tidak tampil (game dibuka / halaman tuning) supaya tidak makan CPU/GPU.
 */
final class VideoBg extends TextureView implements TextureView.SurfaceTextureListener {
    private MediaPlayer mp;
    private Surface surface;
    private SurfaceTexture tex;
    private String path;            // video yang diminta diputar, null = berhenti
    private String playing;         // video yang sedang dibuka player
    private int vw, vh;

    VideoBg(Context c) {
        super(c);
        setSurfaceTextureListener(this);
        setAlpha(0f);               // baru kelihatan setelah frame pertama tampil, supaya tidak ada kedip hitam
    }

    void play(String p) {
        path = p;
        if (mp != null && p.equals(playing)) return;   // sudah jalan
        if (tex != null) open();
    }

    void stopPlayback() {
        path = null;
        release();
        setAlpha(0f);
    }

    private void open() {
        release();
        if (path == null || tex == null) return;
        try {
            final MediaPlayer m = new MediaPlayer();
            surface = new Surface(tex);
            m.setSurface(surface);
            m.setDataSource(path);
            m.setLooping(true);
            m.setVolume(0f, 0f);
            m.setOnVideoSizeChangedListener((p, w, h) -> { vw = w; vh = h; fit(); });
            m.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) setAlpha(1f);
                return false;
            });
            m.setOnPreparedListener(p -> {
                vw = p.getVideoWidth();
                vh = p.getVideoHeight();
                fit();
                p.start();
            });
            m.setOnErrorListener((p, what, extra) -> {
                if (p == mp) { release(); setAlpha(0f); }
                return true;
            });
            mp = m;
            playing = path;
            m.prepareAsync();
        } catch (Exception e) {
            release();
        }
    }

    private void release() {
        playing = null;
        if (mp != null) {
            try { mp.release(); } catch (Exception ignored) {}
            mp = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }

    /** TextureView meregangkan video ke seluruh view, jadi dibalikin ke rasio asli lalu di-crop. */
    private void fit() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || vw <= 0 || vh <= 0) return;
        float videoRatio = (float) vw / vh, viewRatio = (float) w / h;
        float sx = 1f, sy = 1f;
        if (videoRatio > viewRatio) sx = videoRatio / viewRatio;
        else sy = viewRatio / videoRatio;
        Matrix m = new Matrix();
        m.setScale(sx, sy, w / 2f, h / 2f);
        setTransform(m);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fit();
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        tex = st;
        if (path != null) open();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { fit(); }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        release();
        tex = null;
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
}

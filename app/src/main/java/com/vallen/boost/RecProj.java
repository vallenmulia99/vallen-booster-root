package com.vallen.boost;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import java.io.File;
import java.io.IOException;

/**
 * Mesin "Standar": MediaProjection + MediaRecorder (encoder hardware).
 * Ringan karena frame layar langsung masuk ke encoder lewat Surface, tanpa lewat CPU/GL kita.
 * Mendukung mikrofon, jeda, H.264/HEVC. Butuh dialog izin rekam layar dari sistem.
 */
final class RecProj implements RecEngine {
    private final Context c;
    private final int code;
    private final Intent data;
    private final boolean mic;
    private final Runnable onStopped;       // dipanggil kalau berhenti sendiri (izin dicabut, error encoder)

    private HandlerThread ht;
    private Handler h;
    private MediaRecorder mr;
    private MediaProjection mp;
    private VirtualDisplay vd;
    private MediaProjection.Callback cb;
    private Uri uri;
    private ParcelFileDescriptor pfd;
    private File file;
    private boolean running, closed;
    private Uri result;

    RecProj(Context c, int code, Intent data, boolean mic, Runnable onStopped) {
        this.c = c.getApplicationContext();
        this.code = code;
        this.data = data;
        this.mic = mic;
        this.onStopped = onStopped;
    }

    @Override public synchronized void start() throws Exception {
        ht = new HandlerThread("vb-rec-proj", Process.THREAD_PRIORITY_BACKGROUND);
        ht.start();
        h = new Handler(ht.getLooper());
        try {
            try {
                init(RecConfig.codec(c) == 1);
            } catch (Exception e) {
                abort();
                if (RecConfig.codec(c) != 1) throw e;
                init(false);                                  // HEVC tidak didukung chip ini: ulangi pakai H.264
            }
        } catch (Exception e) {
            abort();
            if (ht != null) ht.quitSafely();
            throw new IllegalStateException(msg(e));
        }
    }

    private void init(boolean hevc) throws Exception {
        int[] sz = RecConfig.size(c);
        int w = sz[0], hh = sz[1], dpi = RecConfig.screen(c)[2];
        int fps = RecConfig.fps(c);
        int br = RecConfig.bitrate(c, w, hh, fps);
        openOutput();
        mr = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(c) : new MediaRecorder();
        if (mic) mr.setAudioSource(MediaRecorder.AudioSource.MIC);
        mr.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        if (pfd != null) mr.setOutputFile(pfd.getFileDescriptor());
        else mr.setOutputFile(file.getAbsolutePath());
        mr.setVideoSize(w, hh);
        mr.setVideoEncoder(hevc ? MediaRecorder.VideoEncoder.HEVC : MediaRecorder.VideoEncoder.H264);
        mr.setVideoEncodingBitRate(br);
        mr.setVideoFrameRate(fps);
        if (mic) {
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mr.setAudioEncodingBitRate(RecConfig.audioBitrate(c));
            mr.setAudioSamplingRate(RecConfig.sampleRate(c));
        }
        mr.prepare();

        MediaProjectionManager mm = (MediaProjectionManager) c.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mp = mm.getMediaProjection(code, data);
        if (mp == null) throw new IllegalStateException("Izin rekam layar ditolak");
        cb = new MediaProjection.Callback() {
            @Override public void onStop() {
                if (running && onStopped != null) onStopped.run();     // sistem mencabut proyeksi (mis. user tekan "Stop" di status bar)
            }
        };
        mp.registerCallback(cb, h);
        vd = mp.createVirtualDisplay("vb-rec", w, hh, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mr.getSurface(), null, h);
        mr.start();
        running = true;
    }

    private void openOutput() throws IOException {
        String name = RecConfig.baseName(c) + ".mp4";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VallenBoost");
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            uri = c.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("Gagal membuat file rekaman");
            pfd = c.getContentResolver().openFileDescriptor(uri, "rw");
            if (pfd == null) throw new IOException("Gagal membuka file rekaman");
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "VallenBoost");
            dir.mkdirs();
            file = new File(dir, name);
        }
    }

    /** Bersihkan setelah gagal start: hapus file kosong, lepas semua resource. */
    private void abort() {
        running = false;
        releaseAll();
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
        pfd = null;
        try { if (uri != null) c.getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
        uri = null;
        try { if (file != null) file.delete(); } catch (Exception ignored) {}
        file = null;
    }

    private void releaseAll() {
        try { if (vd != null) vd.release(); } catch (Exception ignored) {}
        vd = null;
        try {
            if (mp != null) {
                if (cb != null) mp.unregisterCallback(cb);
                mp.stop();
            }
        } catch (Exception ignored) {}
        mp = null;
        try { if (mr != null) mr.release(); } catch (Exception ignored) {}
        mr = null;
    }

    @Override public synchronized void stop() {
        if (closed) return;
        closed = true;
        boolean ok = running;
        running = false;
        if (mr != null && ok) {
            try { mr.stop(); } catch (RuntimeException e) { ok = false; }     // terlalu singkat / tidak ada frame
        }
        releaseAll();
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
        pfd = null;
        if (ok) {
            if (uri != null) {
                try {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Video.Media.IS_PENDING, 0);
                    c.getContentResolver().update(uri, v, null, null);
                } catch (Exception ignored) {}
                result = uri;
            } else if (file != null) {
                MediaScannerConnection.scanFile(c, new String[]{file.getAbsolutePath()}, new String[]{"video/mp4"}, null);
                result = Uri.fromFile(file);
            }
        } else {
            try { if (uri != null) c.getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
            try { if (file != null) file.delete(); } catch (Exception ignored) {}
        }
        if (ht != null) ht.quitSafely();
    }

    @Override public boolean canPause() { return true; }
    @Override public synchronized void pause() { try { if (mr != null && running) mr.pause(); } catch (RuntimeException ignored) {} }
    @Override public synchronized void resume() { try { if (mr != null && running) mr.resume(); } catch (RuntimeException ignored) {} }
    @Override public Uri result() { return result; }

    private static String msg(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }
}

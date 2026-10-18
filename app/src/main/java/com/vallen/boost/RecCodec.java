package com.vallen.boost;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.Surface;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

/**
 * Mesin "Standar + Audio Internal" (Android 10+): layar lewat MediaProjection -> encoder video hardware,
 * suara internal HP lewat AudioPlaybackCapture (opsional dicampur mikrofon) -> encoder AAC, digabung MediaMuxer.
 * Dipakai hanya kalau sumber suara = Audio internal / Internal + Mikrofon. Mode lain tetap lewat RecProj.
 */
final class RecCodec implements RecEngine {
    private static final String AAC = "audio/mp4a-latm";
    private static final int CHUNK = 1024;               // frame stereo per bacaan (~21 ms)
    private static final long MAX_PENDING = 32L * 1024 * 1024;

    private static final class Pending {
        final boolean video;
        final ByteBuffer data;
        final MediaCodec.BufferInfo info;
        Pending(boolean video, ByteBuffer data, MediaCodec.BufferInfo info) {
            this.video = video; this.data = data; this.info = info;
        }
    }

    private final Context c;
    private final int code;
    private final Intent data;
    private final boolean withMic;
    private final Runnable onStopped;

    private HandlerThread ht;
    private Handler h;
    private MediaProjection mp;
    private MediaProjection.Callback cb;
    private VirtualDisplay vd;
    private Surface inSurface;
    private MediaCodec venc, aenc;
    private MediaMuxer muxer;
    private AudioRecord arInt, arMic;
    private Thread vThread, aThread;
    private Uri uri;
    private ParcelFileDescriptor pfd;
    private File file;
    private Uri result;

    private volatile boolean running, closed, paused, stopReq;
    private volatile long t0Us, pausedTotalUs, pauseFromUs, pauseToUs;
    private volatile long endByMs;
    private final Object lock = new Object();
    private final MediaCodec.BufferInfo wInfo = new MediaCodec.BufferInfo();   // dipakai ulang, hanya di dalam synchronized(lock)
    private int vTrack = -1, aTrack = -1;
    private boolean muxStarted;
    private final ArrayList<Pending> pend = new ArrayList<>();
    private long pendBytes;
    private long lastV = -1, lastA = -1;
    private int sr;
    private long vFmtAt;

    /** Alasan gagal terakhir (buat pesan error di UI). */
    static volatile String fail = "";

    RecCodec(Context c, int code, Intent data, boolean withMic, Runnable onStopped) {
        this.c = c.getApplicationContext();
        this.code = code;
        this.data = data;
        this.withMic = withMic;
        this.onStopped = onStopped;
    }

    // ================= start =================

    @Override public synchronized void start() throws Exception {
        fail = "";
        ht = new HandlerThread("vb-rec-codec", Process.THREAD_PRIORITY_BACKGROUND);
        ht.start();
        h = new Handler(ht.getLooper());
        try {
            try {
                init(RecConfig.codec(c) == 1);
            } catch (Exception e) {
                abort();
                if (RecConfig.codec(c) != 1) throw e;
                init(false);                                   // HEVC tidak didukung chip ini: ulangi pakai H.264
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
        sr = RecConfig.sampleRate(c);
        openOutput();
        muxer = pfd != null
            ? new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            : new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        // ---- encoder video (input lewat Surface) ----
        MediaFormat vf = MediaFormat.createVideoFormat(hevc ? "video/hevc" : "video/avc", w, hh);
        vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        vf.setInteger(MediaFormat.KEY_BIT_RATE, br);
        vf.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        vf.setFloat("max-fps-to-encoder", fps);                // jangan encode lebih dari FPS pilihan (hemat CPU/GPU)
        vf.setLong("repeat-previous-frame-after", 500_000L);   // layar diam tetap dapat frame, audio tidak ketinggalan
        venc = MediaCodec.createEncoderByType(hevc ? "video/hevc" : "video/avc");
        venc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inSurface = venc.createInputSurface();
        venc.start();

        // ---- encoder audio AAC stereo ----
        MediaFormat af = MediaFormat.createAudioFormat(AAC, sr, 2);
        af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        af.setInteger(MediaFormat.KEY_BIT_RATE, RecConfig.audioBitrate(c));
        af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768);
        aenc = MediaCodec.createEncoderByType(AAC);
        aenc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        aenc.start();

        // ---- proyeksi layar ----
        MediaProjectionManager mm = (MediaProjectionManager) c.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mp = mm.getMediaProjection(code, data);
        if (mp == null) throw new IllegalStateException("Izin rekam layar ditolak");
        cb = new MediaProjection.Callback() {
            @Override public void onStop() {
                if (running && onStopped != null) onStopped.run();     // sistem mencabut proyeksi
            }
        };
        mp.registerCallback(cb, h);

        // ---- sumber audio ----
        try {
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();
            AudioFormat f = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sr)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();
            int min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            arInt = new AudioRecord.Builder()
                .setAudioFormat(f)
                .setBufferSizeInBytes(Math.max(min * 4, CHUNK * 4 * 8))
                .setAudioPlaybackCaptureConfig(cfg)
                .build();
        } catch (Exception e) {
            throw new IllegalStateException("Audio internal tidak bisa dipakai: " + msg(e));
        }
        if (arInt.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IllegalStateException("Audio internal tidak didukung di perangkat ini");
        }
        if (withMic) {
            try {
                int minM = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                AudioRecord m = new AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minM * 4, CHUNK * 2 * 8));
                if (m.getState() == AudioRecord.STATE_INITIALIZED) arMic = m;
                else m.release();
            } catch (Exception ignored) {
                arMic = null;                                   // mic gagal: tetap rekam suara internal saja
            }
        }

        t0Us = System.nanoTime() / 1000;
        vd = mp.createVirtualDisplay("vb-rec", w, hh, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inSurface, null, h);
        arInt.startRecording();
        if (arMic != null) arMic.startRecording();
        running = true;
        vThread = new Thread(this::drainVideo, "vb-vdrain");
        aThread = new Thread(this::audioLoop, "vb-audio");
        vThread.start();
        aThread.start();
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

    // ================= video =================

    private void drainVideo() {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT); } catch (Throwable ignored) {}
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        MediaCodec enc = venc;
        while (true) {
            checkMux();
            int i;
            try {
                i = enc.dequeueOutputBuffer(bi, 20_000);
            } catch (Exception e) {
                break;
            }
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                addTrack(true, enc.getOutputFormat());
            } else if (i >= 0) {
                boolean eos = (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                try {
                    ByteBuffer buf = enc.getOutputBuffer(i);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bi.size = 0;
                    long raw = bi.presentationTimeUs;
                    boolean drop = paused || (raw >= pauseFromUs && raw < pauseToUs);
                    if (bi.size > 0 && buf != null && !drop) {
                        long pts = raw - t0Us - pausedTotalUs;
                        if (pts < 0) pts = 0;
                        if (pts <= lastV) pts = lastV + 1;
                        lastV = pts;
                        buf.position(bi.offset);
                        buf.limit(bi.offset + bi.size);
                        write(true, buf, bi.size, pts, bi.flags);
                    }
                } finally {
                    try { enc.releaseOutputBuffer(i, false); } catch (Exception ignored) {}
                }
                if (eos) break;
            } else if (i == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (stopReq && System.currentTimeMillis() > endByMs) break;   // EOS tidak datang: jangan menggantung
            }
        }
    }

    // ================= audio =================

    private static short clip(int v) {
        return (short) (v > 32767 ? 32767 : v < -32768 ? -32768 : v);
    }

    private void diagToast(final String m) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() { android.widget.Toast.makeText(c, m, android.widget.Toast.LENGTH_LONG).show(); }
        });
    }

    private void audioLoop() {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO); } catch (Throwable ignored) {}   // audio kecil tapi tidak boleh putus saat game rebutan CPU
        final long diagT0 = SystemClock.uptimeMillis();
        int diagPeak = 0;
        boolean diagDone = false;
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        short[] in = new short[CHUNK * 2];
        short[] mic = arMic != null ? new short[CHUNK] : null;
        long base = -1, sentFrames = 0, lastPts = 0;
        long lastDataMs = SystemClock.uptimeMillis();
        while (!stopReq) {
            // baca tanpa memblokir: kalau tidak ada suara yang diputar, perekam internal bisa diam saja.
            // Dalam kondisi itu kita isi hening supaya track audio (dan file) tetap jadi.
            int n = arInt.read(in, 0, in.length, AudioRecord.READ_NON_BLOCKING);
            long nowMs = SystemClock.uptimeMillis();
            int frames;
            if (n >= 2) {
                frames = n / 2;
                lastDataMs = nowMs;
                if (!diagDone) {
                    for (int k = 0; k < n; k++) { int v = Math.abs((int) in[k]); if (v > diagPeak) diagPeak = v; }
                }
            } else {
                if (nowMs - lastDataMs < 60) {
                    try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                    continue;
                }
                frames = CHUNK;
                java.util.Arrays.fill(in, (short) 0);
                lastDataMs = nowMs - 40;                     // hening: satu chunk tiap ~20 ms
            }
            if (!diagDone && nowMs - diagT0 > 5000) {
                diagDone = true;
                if (diagPeak == 0) diagToast("Audio internal KOSONG 5 detik pertama (game memblokir capture / tidak ada suara). Coba mainkan suara.");
                else diagToast("Audio internal OK, level puncak=" + diagPeak);
            }
            int m = 0;
            if (arMic != null) m = Math.max(0, arMic.read(mic, 0, frames, AudioRecord.READ_NON_BLOCKING));
            if (paused) { base = -1; continue; }             // dibuang selama jeda
            if (arMic != null) {
                for (int k = 0; k < frames; k++) {
                    int s = k < m ? mic[k] : 0;
                    in[2 * k] = clip(in[2 * k] + s);
                    in[2 * k + 1] = clip(in[2 * k + 1] + s);
                }
            }
            if (base < 0) {
                long now = System.nanoTime() / 1000 - t0Us - pausedTotalUs;
                base = Math.max(0, now - frames * 1_000_000L / sr);
                sentFrames = 0;
            }
            long pts = base + sentFrames * 1_000_000L / sr;
            sentFrames += frames;
            int idx;
            try {
                idx = aenc.dequeueInputBuffer(10_000);
            } catch (Exception e) {
                break;
            }
            if (idx >= 0) {
                try {
                    ByteBuffer ib = aenc.getInputBuffer(idx);
                    ib.clear();
                    ib.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(in, 0, frames * 2);
                    aenc.queueInputBuffer(idx, 0, frames * 4, pts, 0);
                    lastPts = pts;
                } catch (Exception e) {
                    break;
                }
            } else {
                base = -1;                                   // encoder kewalahan: lewati, sinkron ulang waktunya
            }
            drainAudio(bi, 0);
        }
        // tutup stream audio
        try {
            int idx = -1;
            long until = System.currentTimeMillis() + 2000;
            while (idx < 0 && System.currentTimeMillis() < until) idx = aenc.dequeueInputBuffer(50_000);
            if (idx >= 0) aenc.queueInputBuffer(idx, 0, 0, lastPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            long limit = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < limit) {
                if (drainAudio(bi, 20_000)) break;
            }
        } catch (Exception ignored) {
            // encoder sudah dilepas
        }
    }

    /** @return true kalau EOS sudah keluar. */
    private boolean drainAudio(MediaCodec.BufferInfo bi, long timeoutUs) {
        while (true) {
            int i;
            try {
                i = aenc.dequeueOutputBuffer(bi, timeoutUs);
            } catch (Exception e) {
                return true;
            }
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                addTrack(false, aenc.getOutputFormat());
            } else if (i >= 0) {
                boolean eos = (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                try {
                    ByteBuffer buf = aenc.getOutputBuffer(i);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bi.size = 0;
                    if (bi.size > 0 && buf != null) {
                        long pts = bi.presentationTimeUs;
                        if (pts <= lastA) pts = lastA + 1;
                        lastA = pts;
                        buf.position(bi.offset);
                        buf.limit(bi.offset + bi.size);
                        write(false, buf, bi.size, pts, bi.flags);
                    }
                } finally {
                    try { aenc.releaseOutputBuffer(i, false); } catch (Exception ignored) {}
                }
                if (eos) return true;
            } else {
                return false;                                // INFO_TRY_AGAIN_LATER
            }
        }
    }

    // ================= muxer =================

    private void addTrack(boolean video, MediaFormat f) {
        synchronized (lock) {
            if (muxStarted) return;                           // track telat setelah muxer jalan: abaikan
            try {
                if (video) { vTrack = muxer.addTrack(f); vFmtAt = SystemClock.uptimeMillis(); }
                else aTrack = muxer.addTrack(f);
                if (vTrack >= 0 && aTrack >= 0) startMux();
            } catch (Exception e) {
                fail = "addTrack: " + msg(e);
            }
        }
    }

    /** Jalankan muxer (lock dipegang). Kalau audio belum siap, jalan tanpa track audio. */
    private void startMux() {
        if (muxStarted || vTrack < 0) return;
        try {
            muxer.start();
            muxStarted = true;
            for (Pending p : pend) {
                int tr = p.video ? vTrack : aTrack;
                if (tr >= 0) muxer.writeSampleData(tr, p.data, p.info);
            }
        } catch (Exception e) {
            fail = "muxer.start: " + msg(e);
        }
        pend.clear();
        pendBytes = 0;
    }

    /** Audio tidak kunjung siap dalam 2 detik: rekam video saja daripada gagal total. */
    private void checkMux() {
        synchronized (lock) {
            if (!muxStarted && vTrack >= 0 && aTrack < 0 && vFmtAt > 0 && SystemClock.uptimeMillis() - vFmtAt > 2000) startMux();
        }
    }

    private void write(boolean video, ByteBuffer buf, int size, long pts, int flags) {
        synchronized (lock) {
            try {
                if (muxStarted) {
                    int tr = video ? vTrack : aTrack;
                    if (tr < 0) return;
                    wInfo.set(buf.position(), size, pts, flags);
                    muxer.writeSampleData(tr, buf, wInfo);
                } else if (pendBytes + size <= MAX_PENDING) {
                    ByteBuffer cp = ByteBuffer.allocate(size);
                    cp.put(buf);
                    cp.flip();
                    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    info.set(0, size, pts, flags);
                    pend.add(new Pending(video, cp, info));
                    pendBytes += size;
                }
            } catch (Exception ignored) {
                // satu sample gagal ditulis: lewati
            }
        }
    }

    // ================= stop / cleanup =================

    /** Bersihkan setelah gagal start: hapus file kosong, lepas semua resource. */
    private void abort() {
        running = false;
        stopReq = true;
        releaseAll();
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
        pfd = null;
        try { if (uri != null) c.getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
        uri = null;
        try { if (file != null) file.delete(); } catch (Exception ignored) {}
        file = null;
        vTrack = -1;
        aTrack = -1;
        muxStarted = false;
        pend.clear();
        pendBytes = 0;
        lastV = -1;
        lastA = -1;
        paused = false;
        pausedTotalUs = 0;
        pauseFromUs = 0;
        pauseToUs = 0;
        stopReq = false;
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
        try { if (arInt != null) { arInt.stop(); arInt.release(); } } catch (Exception ignored) {}
        arInt = null;
        try { if (arMic != null) { arMic.stop(); arMic.release(); } } catch (Exception ignored) {}
        arMic = null;
        try { if (venc != null) { venc.stop(); venc.release(); } } catch (Exception ignored) {}
        venc = null;
        try { if (aenc != null) { aenc.stop(); aenc.release(); } } catch (Exception ignored) {}
        aenc = null;
        try { if (inSurface != null) inSurface.release(); } catch (Exception ignored) {}
        inSurface = null;
        try { if (muxer != null) muxer.release(); } catch (Exception ignored) {}
        muxer = null;
    }

    @Override public synchronized void stop() {
        if (closed) return;
        closed = true;
        boolean ok = running;
        running = false;
        endByMs = System.currentTimeMillis() + 3000;
        stopReq = true;
        try { if (vd != null) vd.release(); } catch (Exception ignored) {}
        vd = null;
        try { if (venc != null && ok) venc.signalEndOfInputStream(); } catch (Exception ignored) {}
        join(aThread);
        join(vThread);
        boolean muxed;
        synchronized (lock) {
            if (!muxStarted && vTrack >= 0) startMux();
            muxed = muxStarted;
        }
        if (muxed) {
            try {
                muxer.stop();
            } catch (Exception e) {                                       // terlalu singkat / tidak ada frame
                ok = false;
                fail = "muxer.stop: " + msg(e);
            }
        } else {
            ok = false;
            if (fail.isEmpty()) fail = "tidak ada frame video (video=" + vTrack + ", audio=" + aTrack + ")";
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

    private static void join(Thread t) {
        if (t == null) return;
        try { t.join(5000); } catch (InterruptedException ignored) {}
    }

    // ================= jeda =================

    @Override public boolean canPause() { return true; }

    @Override public synchronized void pause() {
        if (!running || paused) return;
        pauseFromUs = System.nanoTime() / 1000;
        pauseToUs = Long.MAX_VALUE;
        paused = true;
    }

    @Override public synchronized void resume() {
        if (!running || !paused) return;
        long now = System.nanoTime() / 1000;
        pausedTotalUs += now - pauseFromUs;
        pauseToUs = now;
        paused = false;
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);   // frame kunci baru setelah lanjut
            if (venc != null) venc.setParameters(b);
        } catch (Exception ignored) {}
    }

    @Override public Uri result() { return result; }

    private static String msg(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }
}

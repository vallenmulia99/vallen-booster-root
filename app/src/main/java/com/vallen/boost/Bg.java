package com.vallen.boost;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Simpan / muat background lobby pilihan pengguna (disalin ke storage internal app).
 * Bisa gambar diam (lobby_bg.jpg) ATAU video live wallpaper (lobby_bg.mp4), salah satu saja.
 */
final class Bg {
    private Bg() {}

    private static final long MAX_VIDEO = 200L * 1024 * 1024;   // batas 200 MB

    private static File img(Context c) { return new File(c.getFilesDir(), "lobby_bg.jpg"); }
    private static File vid(Context c) { return new File(c.getFilesDir(), "lobby_bg.mp4"); }

    /** File video live wallpaper, null kalau belum ada. */
    static File video(Context c) {
        File f = vid(c);
        return f.exists() && f.length() > 0 ? f : null;
    }

    /** Salin gambar dari Uri, diperkecil maksimal ~2200px biar tidak boros RAM. */
    static boolean save(Context c, Uri u) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = c.getContentResolver().openInputStream(u)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            if (o.outWidth <= 0 || o.outHeight <= 0) return false;
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / s > 2200) s *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = s;
            Bitmap bmp;
            try (InputStream in = c.getContentResolver().openInputStream(u)) {
                bmp = BitmapFactory.decodeStream(in, null, d);
            }
            if (bmp == null) return false;
            try (FileOutputStream out = new FileOutputStream(img(c))) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, out);
            }
            bmp.recycle();
            vid(c).delete();                // gambar menggantikan video
            return true;
        } catch (Exception | OutOfMemoryError e) {
            return false;
        }
    }

    /** Salin video dari Uri ke storage app (maks 200 MB), dicek dulu apakah benar-benar video. */
    static boolean saveVideo(Context c, Uri u) {
        File tmp = new File(c.getFilesDir(), "lobby_bg.tmp");
        boolean ok = false;
        try {
            long total = 0;
            boolean tooBig = false;
            try (InputStream in = c.getContentResolver().openInputStream(u);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                if (in == null) return false;
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_VIDEO) { tooBig = true; break; }
                    out.write(buf, 0, n);
                }
            }
            if (tooBig || total <= 0) return false;

            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(tmp.getAbsolutePath());
                String w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                if (w == null || Integer.parseInt(w) <= 0) return false;
            } finally {
                r.release();
            }
            File dst = vid(c);
            dst.delete();
            if (!tmp.renameTo(dst)) return false;
            img(c).delete();                // video menggantikan gambar
            ok = true;
            return true;
        } catch (Exception | OutOfMemoryError e) {
            return false;
        } finally {
            if (!ok) tmp.delete();
        }
    }

    /** Gambar background. Kalau background-nya video, yang dikembalikan frame pertamanya (poster). null kalau belum ada. */
    static Bitmap load(Context c) {
        File v = video(c);
        if (v != null) {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(v.getAbsolutePath());
                Bitmap b = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (b != null) return b;
            } catch (Exception | OutOfMemoryError ignored) {
                // lanjut: tanpa poster, videonya tetap diputar
            } finally {
                try { r.release(); } catch (Exception ignored) {}
            }
            return null;
        }
        File f = img(c);
        if (!f.exists()) return null;
        try {
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    static void clear(Context c) {
        img(c).delete();
        vid(c).delete();
    }
}

package com.vallen.boost;

import android.net.Uri;

/** Mesin rekam. start()/stop() dipanggil dari thread kerja (bukan main thread). */
interface RecEngine {
    /** Siapkan dan mulai merekam. Lempar exception (pesan bahasa Indonesia) kalau gagal; sisa resource sudah dibersihkan. */
    void start() throws Exception;
    /** Berhenti + simpan file. Aman dipanggil berkali-kali. */
    void stop();
    boolean canPause();
    void pause();
    void resume();
    /** File terakhir yang tersimpan (null kalau gagal / belum ada). Valid setelah stop(). */
    Uri result();
}

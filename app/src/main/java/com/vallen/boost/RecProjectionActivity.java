package com.vallen.boost;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Activity transparan: minta izin mikrofon (kalau perlu) lalu dialog izin rekam layar sistem,
 * hasilnya dikirim ke RecorderService. Tidak punya tampilan sendiri.
 */
public class RecProjectionActivity extends Activity {
    private static final int REQ_PROJ = 41, REQ_MIC = 42;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (RecConfig.audio(this) != 0
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        } else {
            ask();
        }
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        ask();                      // mic ditolak: tetap merekam, tanpa suara
    }

    private void ask() {
        try {
            MediaProjectionManager mm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mm.createScreenCaptureIntent(), REQ_PROJ);
        } catch (Exception e) {
            Toast.makeText(this, "Rekam layar tidak tersedia di perangkat ini", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PROJ && res == RESULT_OK && data != null) {
            try {
                startForegroundService(new Intent(this, RecorderService.class)
                    .setAction(RecorderService.A_PROJ)
                    .putExtra("code", res)
                    .putExtra("data", data));
            } catch (Exception ignored) {}
        } else if (req == REQ_PROJ) {
            Toast.makeText(this, "Rekam layar dibatalkan", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}

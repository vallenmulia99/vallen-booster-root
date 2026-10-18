package com.vallen.boost;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Tile Quick Settings: ketuk = mulai rekam / stop. Aman dipakai pas lagi di dalam game. */
public class RecTile extends TileService {
    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = RecorderService.state == RecorderService.REC || RecorderService.state == RecorderService.PAUSED;
        t.setLabel(on ? "Stop rekam" : "Rekam layar");
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.updateTile();
    }

    @Override public void onStartListening() { refresh(); }

    @Override public void onClick() {
        int s = RecorderService.state;
        if (s == RecorderService.REC || s == RecorderService.PAUSED) {
            RecorderService.send(this, RecorderService.A_STOP);
        } else if (s == RecorderService.IDLE) {
            if (RecConfig.willUseRoot(this)) {
                RecorderService.send(this, RecorderService.A_START);
            } else {
                // mesin Standar butuh dialog izin: buka dari tile (boleh dari background) lalu tutup panel
                Intent pi = new Intent(this, RecProjectionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                boolean done = false;
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    // Android 14+: versi Intent dilarang, wajib versi PendingIntent (dipanggil lewat reflection)
                    try {
                        android.app.PendingIntent p = android.app.PendingIntent.getActivity(this, 7, pi,
                            android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
                        TileService.class.getMethod("startActivityAndCollapse", android.app.PendingIntent.class).invoke(this, p);
                        done = true;
                    } catch (Throwable ignored) {}
                }
                if (!done) startActivityAndCollapse(pi);
            }
        }
        refresh();
    }
}

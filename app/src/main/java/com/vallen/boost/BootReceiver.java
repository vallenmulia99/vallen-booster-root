package com.vallen.boost;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Habis reboot: (1) kalau resolusi sempat diganti, balikin dulu (wm size bertahan setelah reboot!),
 * (2) kalau Auto Boost nyala, hidupkan lagi pemantau game (sisa boost lama dibersihkan oleh service).
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(final Context c, Intent i) {
        if (i == null || !Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        if (Boost.resApplied(c)) {
            final PendingResult pr = goAsync();
            new Thread(new Runnable() {
                @Override public void run() {
                    try { Boost.isRoot(); Boost.resRestore(c); } finally { pr.finish(); }
                }
            }).start();
        }
        if (!Boost.autoOn(c)) return;
        try {
            c.startForegroundService(new Intent(c, BoostService.class).putExtra("boot", true));
        } catch (Exception ignored) {}
    }
}

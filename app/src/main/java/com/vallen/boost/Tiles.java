package com.vallen.boost;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Tile Quick Settings: Boost on/off, Bersihkan RAM, Reset Semua. Tambahkan lewat edit panel Quick Settings. */
public final class Tiles {
    private Tiles() {}

    /** Hidupkan / matikan service pemantau sesuai status prefs. */
    static void syncService(Context c) {
        try {
            Intent i = new Intent(c, BoostService.class);
            if (Boost.gameOn(c) || Boost.lockOn(c) || !Boost.session(c).isEmpty() || Boost.autoOn(c)) {
                c.startForegroundService(i);
            } else {
                c.stopService(i);
            }
        } catch (Exception ignored) {}
    }

    private static boolean needRoot(Context c) {
        if (Boost.root || Boost.isRoot()) return false;
        Boost.notifyResult(c, "Butuh root", "Aksi ini tidak bisa jalan tanpa akses root");
        return true;
    }

    private static void set(TileService s, Tile t, boolean on, String label) {
        if (t == null) return;
        t.setLabel(label);
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.updateTile();
    }

    public static class BoostTile extends TileService {
        private void refresh() {
            set(this, getQsTile(), Boost.gameOn(this) || Boost.lockOn(this), "Vallen Boost");
        }
        @Override public void onStartListening() { refresh(); }
        @Override public void onClick() {
            final boolean on = Boost.gameOn(this) || Boost.lockOn(this);
            new Thread(() -> {
                if (needRoot(this)) return;
                if (on) {
                    Boost.notifyResult(this, "Boost dimatikan", Boost.resetAll(this));
                } else {
                    Boost.setGame(this, true);
                    Boost.notifyResult(this, "Boost aktif",
                        "Game Mode ON\n\u2022 CPU & GPU performance, boost grup top-app\n\u2022 Matikan lewat tile ini atau RESET SEMUA di app");
                }
                syncService(this);
                new Handler(Looper.getMainLooper()).post(this::refresh);
            }).start();
        }
    }

    public static class RamTile extends TileService {
        @Override public void onStartListening() { set(this, getQsTile(), false, "Bersihkan RAM"); }
        @Override public void onClick() {
            new Thread(() -> {
                if (needRoot(this)) return;
                Boost.notifyResult(this, "RAM dibersihkan", Boost.cleanRam(this));
                syncService(this);
            }).start();
        }
    }

    public static class ResetTile extends TileService {
        @Override public void onStartListening() { set(this, getQsTile(), false, "Reset Semua"); }
        @Override public void onClick() {
            new Thread(() -> {
                if (needRoot(this)) return;
                Boost.notifyResult(this, "Reset selesai", Boost.resetAll(this));
                syncService(this);
            }).start();
        }
    }
}

package sourcemove;

import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.objects.GridSquareEdgeFacingDirection;
import zombie.iso.objects.IsoWindow;
import zombie.iso.objects.IsoWindowFrame;

/** Jump through open windows between sill and top; crash through closed ones at speed. */
final class Windows {
    private Windows() {}

    static final double SILL = 0.38, TOP = 0.74;
    /** Speed kept after crashing through. */
    private static final double CRASH_KEEP = 0.7;

    private static IsoObject lastCrossed;
    private static long lastCrossedAt;

    /** You dive through, so feet may be this far below the sill. */
    static final double SILL_LEEWAY = 0.25;

    static boolean inOpening(double feet, int level) {
        double rel = feet - level;
        return rel >= SILL - SILL_LEEWAY && rel <= TOP - 0.04;
    }

    /** Window or empty frame on this edge. */
    static IsoObject on(IsoGridSquare sq, boolean north) {
        if (sq == null) return null;
        GridSquareEdgeFacingDirection dir = north ? GridSquareEdgeFacingDirection.NORTH_SOUTH : GridSquareEdgeFacingDirection.EAST_WEST;
        IsoWindow w = sq.getWindow(dir);
        if (w != null) return w;
        return sq.getWindowFrame(dir);
    }

    /** Window between two adjacent squares, or null. */
    static IsoObject between(IsoCell cell, int z, int ax, int ay, int bx, int by) {
        if (by == ay - 1 && bx == ax) return on(cell.getGridSquare(ax, ay, z), true);
        if (by == ay + 1 && bx == ax) return on(cell.getGridSquare(bx, by, z), true);
        if (bx == ax - 1 && by == ay) return on(cell.getGridSquare(ax, ay, z), false);
        if (bx == ax + 1 && by == ay) return on(cell.getGridSquare(bx, by, z), false);
        return null;
    }

    static boolean open(IsoObject o, IsoGameCharacter c) {
        if (o instanceof IsoWindow w) return w.canClimbThrough(c);
        if (o instanceof IsoWindowFrame f) return !f.isBarricaded();
        return false;
    }

    static boolean crashable(IsoObject o) {
        return o instanceof IsoWindow w && !w.isDestroyed() && !w.IsOpen() && !w.isBarricaded() && !w.isInvincible();
    }

    /** Can you pass now? Smashes a closed window at crash speed and rolls glass cuts. */
    static boolean pass(IsoGameCharacter c, IsoObject window, double feet, int level, double into, double speed, Physics.Vel vel) {
        if (window == null || !Cfg.windowJump || !inOpening(feet, level)) return false;
        if (open(window, c)) {
            if (window instanceof IsoWindow w && w.isDestroyed() && !w.isGlassRemoved()) cut(c, window, 0.5);
            return true;
        }
        if (!Cfg.windowCrash || !crashable(window) || into <= 0 || speed < Cfg.windowCrashSpeed) return false;
        ((IsoWindow) window).smashWindow();
        vel.x *= CRASH_KEEP;
        vel.y *= CRASH_KEEP;
        cut(c, window, 1.0);
        Log.info("crashed through a window at " + String.format("%.1f", speed) + " tiles/s");
        return true;
    }

    /** Glass cut, once per crossing. */
    private static void cut(IsoGameCharacter c, IsoObject window, double chance) {
        if (!Cfg.windowDamage) return;
        long now = System.nanoTime();
        if (window == lastCrossed && now - lastCrossedAt < 1_500_000_000L) return;
        lastCrossed = window;
        lastCrossedAt = now;
        if (Math.random() >= chance) return;
        c.getBodyDamage().setScratchedWindow();
        if (c instanceof IsoPlayer p) p.playerVoiceSound("PainFromGlassCut");
    }
}

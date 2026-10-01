package sourcemove;

import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.objects.GridSquareEdgeFacingDirection;
import zombie.iso.objects.IsoWindow;
import zombie.iso.objects.IsoWindowFrame;

/**
 * Jumping through windows. Open, smashed and empty windows are openings you can jump through with your feet
 * between the sill and the top of the opening; a closed window can be crashed through if you're fast enough.
 * The opening spans 0.38-0.74 level (measured from the wall-with-window sprites; a wall is 1.0). Vanilla's
 * own rules decide what's climbable (IsoWindow.canClimbThrough) and glass cuts (BodyDamage.setScratchedWindow,
 * 50% through unswept broken glass, as in ClimbThroughWindowState).
 */
final class Windows {
    private Windows() {}

    static final double SILL = 0.38, TOP = 0.74;
    /** Horizontal speed kept after crashing through glass. */
    private static final double CRASH_KEEP = 0.7;

    private static IsoObject lastCrossed;
    private static long lastCrossedAt;

    /** Feet high enough to clear the sill (with step-up leeway) and still inside the opening. */
    static boolean inOpening(double feet, int level) {
        double rel = feet - level;
        return rel >= SILL - Ledges.STEP_UP && rel <= TOP - 0.04;
    }

    /** The window (IsoWindow, or an empty IsoWindowFrame) on the north or west edge of {@code sq}. */
    static IsoObject on(IsoGridSquare sq, boolean north) {
        if (sq == null) return null;
        GridSquareEdgeFacingDirection dir = north ? GridSquareEdgeFacingDirection.NORTH_SOUTH : GridSquareEdgeFacingDirection.EAST_WEST;
        IsoWindow w = sq.getWindow(dir);
        if (w != null) return w;
        return sq.getWindowFrame(dir);
    }

    /** The window on the edge between two orthogonally adjacent squares at level z, or null. */
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

    /**
     * Can the player pass this window now? Opens the way by smashing a closed window when crashing is on,
     * they're moving into it at crash speed. Rolls glass cuts once per window crossing.
     * @param into horizontal speed toward the window (tiles/s); {@code speed} is total horizontal speed
     */
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

    /** Glass cut, rolled once per crossing of a given window. */
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

    /**
     * For CollideWithObstacles, which treats every window as a wall edge and pushes you back before you reach
     * it: is there a window within {@code radius} of (x,y), on an edge you're moving toward, that you can pass?
     */
    static boolean nearOpening(IsoGameCharacter c, IsoCell cell, double x, double y, double feet, double radius, Physics.Vel vel) {
        int level = (int) Math.floor(feet);
        if (!Cfg.windowJump || !inOpening(feet, level)) return false;
        int sx = (int) Math.floor(x), sy = (int) Math.floor(y);
        double speed = vel.speed();
        return (y - sy < radius && vel.y < 0 && pass(c, on(cell.getGridSquare(sx, sy, level), true), feet, level, -vel.y, speed, vel))
                || (sy + 1 - y < radius && vel.y > 0 && pass(c, on(cell.getGridSquare(sx, sy + 1, level), true), feet, level, vel.y, speed, vel))
                || (x - sx < radius && vel.x < 0 && pass(c, on(cell.getGridSquare(sx, sy, level), false), feet, level, -vel.x, speed, vel))
                || (sx + 1 - x < radius && vel.x > 0 && pass(c, on(cell.getGridSquare(sx + 1, sy, level), false), feet, level, vel.x, speed, vel));
    }
}

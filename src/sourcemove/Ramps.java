package sourcemove;

import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.FallingConstants;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;

/** Stairs, slopes, tent sides and rails as ramps, the height guard and trimps. */
public final class Ramps {
    private Ramps() {}

    /** Source NON_JUMP_VELOCITY, 140 u/s, in levels/s. */
    private static final double NON_JUMP = 140 * 0.01905 / Physics.LEVEL_M;
    private static boolean snapGuard;
    private static float snapX, snapY, snapZ;

    /** Fastest legit takeoff (levels/s), for the server's check. */
    static double maxLaunchSpeed() {
        double v0 = Physics.jumpSpeed(Math.max(0.1, Cfg.jumpHeight()), FallingConstants.IsoFallAcceleration);
        if (!Cfg.trimp()) return v0;
        double fastest = Cfg.mpSpeedLimit() > 0 ? Cfg.mpSpeedLimit() : 60;
        return v0 + fastest / Physics.LEVEL_M;
    }

    /** doStairs / handleSlopedSurface snap Z to the surface every frame; keep your height mid-jump. */
    public static void onSurfaceSnapEnter(IsoMovingObject o) {
        // Mid-jump, or standing on a rail, prop or car over the stairs.
        snapGuard = o == Mover.self && Mover.owned && (!Mover.grounded && (Mover.jumpedThisAir || Mover.self.getLastFallSpeed() < 0)
                || Mover.grounded && Floors.floorKind != Floors.FLOOR_GROUND);
        if (snapGuard) {
            snapX = o.getX();
            snapY = o.getY();
            snapZ = o.getZ();
        }
    }

    public static void onSurfaceSnapExit(IsoMovingObject o) {
        if (!snapGuard || o != Mover.self) return;
        snapGuard = false;
        if (o.getZ() < snapZ) o.setZ(snapZ);
        // In the air the XY clamp near stair tops is a jerk; on a rail it keeps you out of the wall.
        if (Mover.grounded) return;
        if (o.getX() != snapX) o.setX(snapX);
        if (o.getY() != snapY) o.setY(snapY);
    }

    /** Current ramp's uphill direction and angle. */
    private static double rampUx, rampUy, rampAngle;

    /** The ramp under you, a tent side, rail, stairs or slope. */
    static boolean groundRamp(IsoGameCharacter c) {
        if (Floors.floorKind == Floors.FLOOR_PROP && Floors.floorProp != null && Floors.floorProp.rampAngle > 0) {
            IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
            if (cell == null) return false;
            int dir = Props.uphill(cell, Floors.floorProp, Floors.floorPropX, Floors.floorPropY, (int) Math.floor(c.getZ()), c.getX(), c.getY());
            if (dir == 0) return false; // on the ridge
            rampUx = Floors.floorProp.ridgeAlongY ? dir : 0;
            rampUy = Floors.floorProp.ridgeAlongY ? 0 : dir;
            rampAngle = Floors.floorProp.rampAngle;
            return true;
        }
        IsoGridSquare sq = c.getCurrentSquare();
        double fx = c.getX() - (sq != null ? sq.getX() : 0), fy = c.getY() - (sq != null ? sq.getY() : 0), e = 0.1;
        if (Floors.floorKind == Floors.FLOOR_FENCE && Floors.floorRail != null) {
            // A rail has its stairs' slope.
            sq = Floors.floorRail;
            fx = fy = 0.5;
        }
        if (sq == null || !(sq.HasStairs() || sq.hasSlopedSurface())) return false;
        double gx = grade(sq, fx - e, fy, fx + e, fy), gy = grade(sq, fx, fy - e, fx, fy + e);
        double g = Math.hypot(gx, gy);
        if (g < 1e-3) return false;
        rampUx = gx / g;
        rampUy = gy / g;
        rampAngle = Math.atan(g * Physics.LEVEL_M);
        return true;
    }

    /** Rise per tile between two surface points (levels). */
    private static double grade(IsoGridSquare sq, double ax, double ay, double bx, double by) {
        ax = Physics.clamp01(ax);
        ay = Physics.clamp01(ay);
        bx = Physics.clamp01(bx);
        by = Physics.clamp01(by);
        double run = Math.hypot(bx - ax, by - ay);
        if (run < 1e-6) return 0;
        return (sq.getApparentZ((float) bx, (float) by) - sq.getApparentZ((float) ax, (float) ay)) / run;
    }

    /** Source's lift from running up the current ramp (levels/s), which decides a trimp. */
    private static double clipLift() {
        Physics.Vel v = clipScratch;
        v.x = Mover.vel.x;
        v.y = Mover.vel.y;
        return Physics.clipRamp(v, 0, rampUx, rampUy, rampAngle) / Physics.LEVEL_M;
    }

    private static final Physics.Vel clipScratch = new Physics.Vel();

    /** Rise that keeps you on the slope at full speed (levels/s), 0 going down it; tent sides count as 45 degrees. */
    static double slopeLift() {
        double up = Mover.vel.x * rampUx + Mover.vel.y * rampUy;
        return up > 0 ? up * Math.min(1, Math.tan(rampAngle)) / Physics.LEVEL_M : 0;
    }

    /** Fast enough up a ramp to trimp; you keep your speed and fly off its top. */
    private static boolean trimps(IsoGameCharacter c) {
        return clipLift() > NON_JUMP && !Mover.ceilingBlocked(c);
    }

    private static boolean groundTrimp(IsoPlayer p) {
        if (!trimps(p)) return false;
        launch(p, slopeLift());
        return true;
    }

    /** Landing on a ramp while moving up it fast keeps you flying; slower, you just land. */
    static boolean rampBounce(IsoGameCharacter c) {
        if (!Cfg.trimp() || !groundRamp(c) || !trimps(c)) return false;
        c.setLastFallSpeed((float) -slopeLift());
        Mover.jumpedThisAir = true;
        return true;
    }

    /** Trimp takeoff, or a boost if already airborne. */
    private static void launch(IsoGameCharacter c, double vz) {
        c.setLastFallSpeed((float) -vz);
        if (Mover.grounded) {
            Mover.grounded = false;
            Mover.airStartNanos = System.nanoTime();
            Landing.rememberLand(c);
            Net.jumped(false, 0);
        }
        Mover.jumpedThisAir = true;
        Mover.airborneUnderMod = true;
    }

    static void tryTrimp(IsoPlayer p) {
        if (groundRamp(p)) groundTrimp(p);
    }

    /** Running into a sloped rail near its top clips you along it; only uphill speed lifts. */
    static void railTrimp(IsoPlayer p, boolean n, boolean s, boolean e, boolean w) {
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        IsoGridSquare sq = p.getCurrentSquare();
        if (cell == null || sq == null || Mover.vel.speed() < 1e-3) return;
        IsoGridSquare edge = null;
        boolean north = false;
        if (w) edge = sq;
        else if (e) edge = cell.getGridSquare(sq.x + 1, sq.y, sq.z);
        if (edge == null || Rails.stairs(edge, false) == null) {
            north = true;
            edge = n ? sq : s ? cell.getGridSquare(sq.x, sq.y + 1, sq.z) : null;
        }
        IsoGridSquare stairs = edge == null ? null : Rails.stairs(edge, north);
        if (stairs == null) return;
        double top = Rails.top(stairs, edge, north, north ? p.getX() : p.getY());
        double feet = p.getZ();
        if (feet >= top || feet < top - Rails.RAIL - 0.02) return;
        // Rail slope is its stairs' gradient mid-square.
        double gx = grade(stairs, 0.4, 0.5, 0.6, 0.5), gy = grade(stairs, 0.5, 0.4, 0.5, 0.6);
        double g = Math.hypot(gx, gy);
        if (g < 1e-3) return;
        rampUx = gx / g;
        rampUy = gy / g;
        rampAngle = Math.atan(g * Physics.LEVEL_M);
        double vzNow = Mover.grounded ? 0 : -p.getLastFallSpeed();
        double vz = slopeLift();
        if (!trimps(p) || vz <= vzNow) return;
        launch(p, vz);
        if (Cfg.wireHud) Wire.log(String.format("rail trimp: %.2f lv/s up at %.1f tiles/s", vz, Mover.vel.speed()));
    }
}

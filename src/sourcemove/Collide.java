package sourcemove;

import org.joml.Vector2f;
import zombie.characters.IsoGameCharacter;
import zombie.characters.FallingConstants;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.vehicles.BaseVehicle;

/** Grid and polygon collision for the local player, what to let you over and velocity clipping. */
public final class Collide {
    private Collide() {}

    private static float preX, preY;
    private static boolean postActive;

    /** resolveCollision exit. Only runs near cars, so only cars and truly blocking solid squares push back here. */
    public static void onResolveCollision(IsoGameCharacter c, float nx, float ny, Vector2f result) {
        if (c != Mover.self || !Mover.owned || result == null || (result.x == nx && result.y == ny)) return;
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (cell == null) return;
        int cars = Rides.vehiclesAt(cell, c.getX(), c.getY(), nx, ny, c.getZ(), 0.3f, Ledges.STEP_UP);
        int props = cars < 0 ? 0 : Props.squaresAt(cell, c.getX(), c.getY(), nx, ny, c.getZ(), 0.3f, Ledges.STEP_UP, !Mover.grounded);
        boolean keep = cars < 0 || props < 0;
        if (Cfg.wireHud) {
            Wire.pushback(c.getX(), c.getY(), nx, ny, result.x, result.y, keep,
                    cars < 0 ? "car in the way" : props < 0 ? Props.lastBlock : "edge, left to the grid collision");
        }
        if (!keep) result.set(nx, ny);
    }

    /** shouldIgnoreCollisionWithSquare exit. Lets you over edges and props your feet are above. */
    public static boolean onIgnoreCollision(IsoGameCharacter c, IsoGridSquare target, boolean ret) {
        if (ret || c != Mover.self || !Mover.owned || target == null) return ret;
        boolean pass = ignoreCollision(c, target);
        if (Cfg.wireHud) {
            IsoGridSquare from = c.getLastSquare();
            asked(from == null ? "?" : String.format("%d,%d,%d>%d,%d,%d %s", from.getX(), from.getY(), from.getZ(),
                    target.getX(), target.getY(), target.getZ(), pass ? "pass" : "keep"));
        }
        return pass;
    }

    private static boolean ignoreCollision(IsoGameCharacter c, IsoGridSquare target) {
        IsoGridSquare from = c.getLastSquare();
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (from == null || cell == null) return deny(target, "no square under you", c.getZ() - target.getZ(), Double.NaN);
        double feet = c.getZ();
        int level = target.getZ();
        int fx = from.getX(), fy = from.getY(), tx = target.getX(), ty = target.getY();
        int dz = level - from.getZ();
        boolean moves = fx != tx || fy != ty;
        // Fell below the square you were in, so moving down into the one under it.
        if (dz < 0 && !moves && feet < from.getZ()) return true;
        // Open air at your level (off a roof, out a window) or the engine tests the square below and stops you dead.
        boolean air = dz < 0 && moves && feet >= from.getZ() && cell.getGridSquare(tx, ty, from.getZ()) == null;
        int edgeLevel = air ? from.getZ() : level;
        boolean descended = dz < 0 && moves && feet < from.getZ();
        if (descended && (int) Math.floor(feet) > level) {
            // Still above the square the engine fell back to, so open air at your level.
            air = true;
            edgeLevel = (int) Math.floor(feet);
        } else if (descended) {
            // Dropped out of a floorless square this frame, so judge the move at your new level.
            from = cell.getGridSquare(fx, fy, level);
            dz = 0;
        }
        if (!air && (dz != 0 && dz != 1 || level > (int) Math.floor(feet))) return deny(target, "other level", feet - level, Double.NaN);
        if ((dz == 0 || air) && Cfg.windowJump() && Windows.inOpening(feet, edgeLevel)) {
            // Open windows are openings; closed ones can be crashed through.
            IsoObject window = Windows.between(cell, edgeLevel, fx, fy, tx, ty);
            double into = Mover.vel.x * (tx - fx) + Mover.vel.y * (ty - fy);
            if (window != null && Windows.pass(c, window, feet, edgeLevel, into, Mover.vel.speed(), Mover.vel)) return true;
        }
        double top = Ledges.crossingHeight(cell, edgeLevel, fx, fy, tx, ty, c.getX(), c.getY());
        // The engine blocks corner cuts past any wall or solid, so diagonals are decided here.
        boolean diagonal = fx != tx && fy != ty;
        if (diagonal) top = Math.max(top, cornerHeight(cell, edgeLevel, fx, fy, tx, ty, c.getX(), c.getY()));
        if (air) return top <= 0 || feet >= edgeLevel + top - Ledges.STEP_UP || deny(target, "edge into open air", feet - edgeLevel, top - Ledges.STEP_UP);
        // Water only from the air or a prop in it; leaving it needs letting through too.
        boolean wet = Water.open(target), leavingWater = from != null && Water.open(from);
        if (wet && Mover.grounded && feet < level + Water.SURFACE) return deny(target, "water", feet - level, Water.SURFACE);
        boolean solidTarget = target.isSolid() || target.isSolidTrans();
        if (solidTarget) {
            // Props have their own heights; a tent side only counts where you step in.
            Props.Prop slope = Props.ramp(target);
            double prop = slope != null ? Props.rampHeightAt(cell, slope, tx, ty, level, c.getX(), c.getY())
                    : Props.entryTop(target);
            // The engine treats the whole tent square as solid.
            if (slope != null) {
                return feet >= level + top - Ledges.STEP_UP && feet >= level + prop - Props.RAMP_ALLOW
                        || deny(target, "tent side", feet - level, Math.max(top - Ledges.STEP_UP, prop - Props.RAMP_ALLOW));
            }
            if (Double.isNaN(prop)) {
                if (!target.has(IsoFlagType.solid)) return deny(target, null, feet - level, Double.NaN);
                prop = Ledges.FULL;
            }
            top = Math.max(top, prop);
        }
        if (top <= 0 && dz == 0 && (ramped(target) || ramped(from))) {
            // Stairs and slopes from the side are fine when you're above their surface there.
            double surface = ramped(target)
                    ? target.getApparentZ((float) Physics.clamp01(c.getX() - tx), (float) Physics.clamp01(c.getY() - ty)) : level;
            if (feet >= surface - Ledges.STEP_UP) return true;
            return deny(target, "stairs or slope side", feet - level, surface - level - Ledges.STEP_UP);
        }
        // No floor up here, open air you'll fall through.
        if (top <= 0 && dz == 0 && level > 0 && !target.TreatAsSolidFloor()) return true;
        // Misplaced escalator panel flag, nothing here.
        if (top <= 0 && dz == 0 && Rails.phantomCrossing(cell, level, fx, fy, tx, ty)) return true;
        // Nothing of ours on a straight edge, so vanilla decides, unless you just dropped a level.
        if (top <= 0 && dz == 0 && !diagonal && !descended && from != null) return wet || leavingWater || vanilla(from, target);
        return top <= 0 || feet >= level + top - Ledges.STEP_UP
                || deny(target, solidTarget ? null : diagonal ? "corner" : "edge", feet - level, top - Ledges.STEP_UP);
    }

    /** Height to clear going diagonally, the easier way round with its corner square. */
    private static double cornerHeight(IsoCell cell, int z, int sx, int sy, int tx, int ty, double px, double py) {
        return Math.min(viaCorner(cell, z, sx, sy, tx, sy, tx, ty, px, py), viaCorner(cell, z, sx, sy, sx, ty, tx, ty, px, py));
    }

    private static double viaCorner(IsoCell cell, int z, int sx, int sy, int cx, int cy, int tx, int ty, double px, double py) {
        double h = Math.max(Ledges.crossingHeight(cell, z, sx, sy, cx, cy, px, py),
                Ledges.crossingHeight(cell, z, cx, cy, tx, ty, px, py));
        IsoGridSquare corner = cell.getGridSquare(cx, cy, z);
        if (corner != null && (corner.isSolid() || corner.isSolidTrans())) {
            Props.Prop slope = Props.ramp(corner);
            double entry = slope != null ? slope.entryTop : Props.entryTop(corner);
            h = Math.max(h, Double.isNaN(entry) ? (corner.has(IsoFlagType.solid) ? Ledges.FULL : Double.POSITIVE_INFINITY) : entry);
        }
        return h;
    }

    /** Why we kept collisions this frame, for the overlay. */
    private static String denied;
    /** Square at postupdate start, for the log. */
    private static String postFrom;

    /** Keep the collision and note why for the overlay; null what = the target's contents, NaN need = never. */
    private static boolean deny(IsoGridSquare target, String what, double feet, double need) {
        if (Cfg.wireHud) {
            note(String.format("into %d,%d,%d: %s, feet %.2f, needs %s", target.getX(), target.getY(), target.getZ(),
                    what != null ? what : Props.describe(target), feet,
                    Double.isNaN(need) ? "never (not jumpable)" : String.format("%.2f", need)));
        }
        return false;
    }

    /** testCollideAdjacent exit. Blocks unflagged escalator panels unless your feet are over them. */
    public static boolean onCollideAdjacent(IsoGridSquare from, IsoMovingObject o, int dx, int dy, int dz, boolean ret) {
        // More than a level down the engine refuses without asking us (off a tall roof over open ground).
        if (ret && dz < -1 && o == Mover.self && Mover.owned && from != null && from.getCell() != null) {
            IsoGridSquare target = from.getCell().getGridSquare(from.getX() + dx, from.getY() + dy, from.getZ() + dz);
            return target == null || !onIgnoreCollision(Mover.self, target, false);
        }
        if (ret || o != Mover.self || !Mover.owned || dz != 0 || dx == 0 && dy == 0 || from == null) return ret;
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (cell == null) return false;
        int level = from.getZ();
        double h = Rails.unflaggedCrossing(cell, level, from.getX(), from.getY(), from.getX() + dx, from.getY() + dy,
                Mover.self.getX(), Mover.self.getY());
        if (h <= 0) return false;
        double feet = Mover.self.getZ();
        if (feet >= level + h - Ledges.STEP_UP) return false;
        IsoGridSquare target = cell.getGridSquare(from.getX() + dx, from.getY() + dy, level);
        if (target != null) deny(target, "escalator rail", feet - level, h - Ledges.STEP_UP);
        return true;
    }

    private static boolean ramped(IsoGridSquare sq) {
        return sq != null && (sq.HasStairs() || sq.hasSlopedSurface());
    }

    /** Squares the engine asked about this frame, for the log. */
    private static String askedLog;

    private static void asked(String s) {
        if (askedLog == null) askedLog = s;
        else if (askedLog.length() < 400) askedLog = askedLog + ", " + s;
    }

    private static String squareInfo(IsoGridSquare sq) {
        if (sq == null) return "none";
        return String.format("%d,%d,%d%s%s", sq.getX(), sq.getY(), sq.getZ(), sq.TreatAsSolidFloor() ? " floor" : " nofloor",
                sq.isSolid() || sq.isSolidTrans() ? " solid" : "");
    }

    private static void note(String why) {
        if (denied == null) denied = why;
        else if (!denied.contains(why) && denied.length() < 600) denied = denied + " / " + why;
    }

    /** Defer to vanilla; with the overlay on, note what it will block on. */
    private static boolean vanilla(IsoGridSquare from, IsoGridSquare target) {
        if (!Cfg.wireHud) return false;
        try {
            IsoObject special = from.testCollideSpecialObjects(target);
            String why = null;
            if (special != null) {
                why = "door/window/special object " + (special.getSprite() != null ? special.getSprite().getName() : special.getClass().getSimpleName());
            } else if (from.CalculateCollide(target, false, false, false)) {
                boolean diag = from.getX() != target.getX() && from.getY() != target.getY();
                IsoCell cell = from.getCell();
                IsoGridSquare a = diag ? cell.getGridSquare(from.getX(), target.getY(), from.getZ()) : null;
                IsoGridSquare b = diag ? cell.getGridSquare(target.getX(), from.getY(), from.getZ()) : null;
                if (from.isWallTo(target)) why = "wall between (isWallTo)";
                else if (diag) why = "diagonal corner: " + Props.describe(a) + " | " + Props.describe(b);
                else if (from.HasStairs() || target.HasStairs()) why = "stairs";
                else if (target.getZ() > 0 && !target.TreatAsSolidFloor()) why = "no floor there";
                else why = "unknown vanilla rule";
            }
            if (why != null) {
                note(String.format("into %d,%d,%d: vanilla: %s", target.getX(), target.getY(), target.getZ(), why));
            }
        } catch (Throwable t) {
            note("vanilla check failed: " + t);
        }
        return false;
    }

    public static void onPostUpdateEnter(IsoMovingObject o) {
        postActive = Mover.owned && o == Mover.self;
        if (postActive) {
            preX = o.getX();
            preY = o.getY();
            denied = null;
            askedLog = null;
            postFrom = Cfg.wireHud ? squareInfo(Mover.self.getCurrentSquare()) : null;
        }
    }

    public static void onPostUpdateExit(IsoMovingObject o) {
        if (!postActive || o != Mover.self) return;
        postActive = false;
        if (!(o.isCollidedN() || o.isCollidedS() || o.isCollidedE() || o.isCollidedW() || o.isCollidedWithVehicle())) return;
        // Hit a sloped rail fast enough, launch along it.
        if (Cfg.trimp()) Ramps.railTrimp(Mover.self, o.isCollidedN(), o.isCollidedS(), o.isCollidedE(), o.isCollidedW());
        // Rising over what's ahead, keep going instead of stopping on its face.
        boolean clearing = willClear(Mover.self);
        if (Cfg.wireHud && (o.isCollidedN() || o.isCollidedS() || o.isCollidedE() || o.isCollidedW())) {
            Wire.gridBlock(Mover.self.getCurrentSquare(), o.isCollidedN(), o.isCollidedS(), o.isCollidedE(), o.isCollidedW(), clearing,
                    (denied != null ? denied : "no refusal from us") + " [squares " + postFrom + " -> " + squareInfo(Mover.self.getCurrentSquare())
                            + "; asked: " + (askedLog != null ? askedLog : "nothing") + "]");
        }
        if (clearing) return;
        // Zero the blocked axis, as the engine does.
        if (o.isCollidedN() || o.isCollidedS()) Mover.vel.y = 0;
        if (o.isCollidedE() || o.isCollidedW()) Mover.vel.x = 0;
        if (o.isCollidedWithVehicle()) {
            // A car redirected us, keep only the velocity along the actual motion.
            double ax = o.getX() - preX, ay = o.getY() - preY;
            double len = Math.hypot(ax, ay);
            if (len < 1e-5) {
                Mover.vel.x = Mover.vel.y = 0;
            } else {
                double ux = ax / len, uy = ay / len;
                double d = Math.max(0, Mover.vel.x * ux + Mover.vel.y * uy);
                Mover.vel.x = ux * d;
                Mover.vel.y = uy * d;
            }
        }
    }

    /** Body radius plus a little (tiles). */
    private static final double AHEAD = 0.45;

    /** Contact just before the apex of a jump that will clear it keeps speed instead of falling back. */
    private static boolean willClear(IsoGameCharacter c) {
        if (Mover.grounded) return false;
        double v = -c.getLastFallSpeed();
        if (v <= 0) return false;
        double need = obstacleAhead(c);
        if (Double.isNaN(need)) return false;
        double apex = c.getZ() + v * v / (2 * FallingConstants.IsoFallAcceleration);
        return apex >= need - Ledges.STEP_UP;
    }

    /** Absolute Z of the tallest thing just ahead; NaN if it can't be jumped. */
    private static double obstacleAhead(IsoGameCharacter c) {
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        double s = Mover.vel.speed();
        if (cell == null || s < 1e-3) return Double.NaN;
        double px = c.getX() + Mover.vel.x / s * AHEAD, py = c.getY() + Mover.vel.y / s * AHEAD;
        int level = (int) Math.floor(c.getZ());
        int sx = (int) Math.floor(c.getX()), sy = (int) Math.floor(c.getY());
        double need = 0;
        for (int gx = (int) Math.floor(px - 0.3); gx <= (int) Math.floor(px + 0.3); gx++) {
            for (int gy = (int) Math.floor(py - 0.3); gy <= (int) Math.floor(py + 0.3); gy++) {
                if ((gx == sx && gy == sy) || Math.abs(gx - sx) > 1 || Math.abs(gy - sy) > 1) continue;
                double h = Ledges.crossingHeight(cell, level, sx, sy, gx, gy);
                IsoGridSquare sq = cell.getGridSquare(gx, gy, level);
                if (sq != null && (sq.isSolid() || sq.isSolidTrans())) {
                    double top = Props.entryTop(sq);
                    if (Double.isNaN(top)) return Double.NaN;
                    h = Math.max(h, top);
                }
                need = Math.max(need, h);
            }
        }
        for (BaseVehicle car : Rides.nearby(cell, (float) px, (float) py)) {
            if (!Rides.over(car, (float) px, (float) py, 0.3f)) continue;
            float roof = Rides.roofZ(car);
            if (Float.isNaN(roof)) return Double.NaN;
            need = Math.max(need, roof - level);
        }
        return level + need;
    }

    public static boolean onCheckHitWall(IsoMovingObject o) {
        return Mover.owned && o == Mover.self;
    }
}

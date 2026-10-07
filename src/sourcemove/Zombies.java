package sourcemove;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Locale;
import zombie.ai.State;
import zombie.ai.states.AttackNetworkState;
import zombie.ai.states.AttackState;
import zombie.ai.states.LungeState;
import zombie.ai.states.PathFindState;
import zombie.ai.states.WalkTowardState;
import zombie.ai.states.ZombieIdleState;
import zombie.ai.states.ZombieTurnAlerted;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.objects.interfaces.Thumpable;
import zombie.network.GameClient;
import zombie.pathfind.PathFindBehavior2;

/** Zombies vs. a jumping player, grab reach, pull-downs and the props you stand on. */
public final class Zombies {
    private Zombies() {}

    private static boolean reachSwapped, propSwapped;
    private static int reachDepth;
    private static float savedZ, savedLastZ, savedX, savedY, savedNextX, savedNextY, savedVecX, savedVecY;
    private static IsoPlayer reachPlayer;
    private static IsoZombie reachZombie;
    /** Keeps the edge point inside the prop's square. */
    private static final float EDGE_IN = 0.01f;
    private static final ArrayList<IsoObject> reachParts = new ArrayList<>();

    /** Count a raised player at the zombie's height while within grab reach, and on a prop at its nearest edge; nesting-safe. */
    public static void onZombieAttackEnter(IsoGameCharacter zombie) {
        if (reachDepth++ > 0) return;
        reachSwapped = propSwapped = false;
        // MP target may be another mod user.
        if (!(zombie instanceof IsoZombie z) || !(z.getTarget() instanceof IsoPlayer p)) return;
        if (p == Mover.self ? !Mover.baseActive : !Remote.active(p)) return;
        float dz = p.getZ() - zombie.getZ();
        if (dz < 0 || dz >= Cfg.zombieReach()) return;
        reachPlayer = p;
        if (p == Mover.self && Mover.grounded && Floors.floorKind == Floors.FLOOR_PROP) reachProp(z, p);
        if (dz < 0.2f) return;
        savedZ = p.getZ();
        savedLastZ = p.getLastZ();
        p.setZ(zombie.getZ());
        reachSwapped = true;
    }

    public static void onZombieAttackExit() {
        if (reachDepth > 0 && --reachDepth > 0) return;
        IsoPlayer p = reachPlayer;
        reachPlayer = null;
        if (p != null && propSwapped) {
            p.setX(savedX);
            p.setY(savedY);
            p.setNextX(savedNextX);
            p.setNextY(savedNextY);
            reachZombie.vectorToTarget.set(savedVecX, savedVecY);
        }
        if (p != null && reachSwapped) {
            p.setZ(savedZ);
            p.setLastZ(savedLastZ);
        }
        reachSwapped = propSwapped = false;
        reachZombie = null;
    }

    /** Measured to the prop's nearest edge, not your feet, so zombies beside a table claw whoever is on it. */
    private static void reachProp(IsoZombie z, IsoPlayer p) {
        IsoObject prop = Floors.floorObject;
        IsoGridSquare home = prop != null ? prop.getSquare() : null;
        if (home == null) return;
        float zx = z.getX(), zy = z.getY();
        // Nearest point on any of its squares, Manhattan as the hit test measures.
        float ex = 0, ey = 0, best = Float.MAX_VALUE;
        prop.getSpriteGridObjectsIncludingSelf(reachParts);
        for (int i = 0; i < reachParts.size(); i++) {
            IsoGridSquare sq = reachParts.get(i).getSquare();
            if (sq == null || sq.getZ() != home.getZ()) continue;
            float px = Math.max(sq.getX() + EDGE_IN, Math.min(sq.getX() + 1 - EDGE_IN, zx));
            float py = Math.max(sq.getY() + EDGE_IN, Math.min(sq.getY() + 1 - EDGE_IN, zy));
            float d = Math.abs(px - zx) + Math.abs(py - zy);
            if (d < best) {
                best = d;
                ex = px;
                ey = py;
            }
        }
        reachParts.clear();
        if (best >= Math.abs(p.getX() - zx) + Math.abs(p.getY() - zy)) return;
        savedX = p.getX();
        savedY = p.getY();
        savedNextX = p.getNextX();
        savedNextY = p.getNextY();
        savedVecX = z.vectorToTarget.x;
        savedVecY = z.vectorToTarget.y;
        p.setX(ex);
        p.setY(ey);
        z.vectorToTarget.set(ex - zx, ey - zy);
        reachZombie = z;
        propSwapped = true;
    }

    /** Grab yank speed (levels/s), under vanilla's 2.2 damage threshold. */
    private static final float PULL_SPEED = 1.5f;
    static boolean pulledThisAir;
    static long jumpLockUntil, pulledAt;

    /** Enough zombies in range can pull you down, once per airtime. */
    static void tryPulldown(IsoPlayer p, double dt) {
        if (dt <= 0 || p.isGhostMode() || p.isZombiesDontAttack()) return;
        IsoCell cell = p.getCell();
        if (cell == null) return;
        double r = Cfg.pulldownRange;
        IsoGridSquare mine = p.getCurrentSquare();
        int grabbers = 0;
        double x = p.getX(), y = p.getY(), z = p.getZ();
        // Only squares in range.
        for (int level = (int) Math.floor(z - Cfg.zombieReach()); level <= (int) Math.floor(z); level++) {
            for (int gx = (int) Math.floor(x - r); gx <= (int) Math.floor(x + r); gx++) {
                for (int gy = (int) Math.floor(y - r); gy <= (int) Math.floor(y + r); gy++) {
                    IsoGridSquare sq = cell.getGridSquare(gx, gy, level);
                    if (sq == null) continue;
                    ArrayList<IsoMovingObject> here = sq.getMovingObjects();
                    for (int i = 0; i < here.size(); i++) {
                        if (!(here.get(i) instanceof IsoZombie zed)) continue;
                        double dx = x - zed.getX(), dy = y - zed.getY();
                        if (dx * dx + dy * dy > r * r) continue;
                        double dz = z - zed.getZ();
                        if (dz < 0 || dz >= Cfg.zombieReach() || zed.getTarget() != p || !canGrab(zed)) continue;
                        if (sq != mine && mine != null && sq.isSomethingTo(mine)) continue;
                        grabbers++;
                    }
                }
            }
        }
        int group = Math.max(1, Cfg.pulldownGroup());
        if (grabbers < group) return;
        double rate = Cfg.pulldownRate * (grabbers - group + 1);
        if (Math.random() < 1 - Math.exp(-rate * dt)) pulldown(p);
    }

    /** Upright, facing you, chasing or attacking. */
    private static boolean canGrab(IsoZombie z) {
        if (z.isDead() || z.isOnFloor() || z.isCrawling() || z.isUseless() || z.isKnockedDown() || !z.isFacingTarget()) {
            return false;
        }
        State s = z.getCurrentState();
        // Another client's zombie, attacking.
        return s == LungeState.instance() || s == AttackState.instance() || s == AttackNetworkState.instance() || s == PathFindState.instance()
                || s == WalkTowardState.instance() || s == ZombieIdleState.instance() || s == ZombieTurnAlerted.instance();
    }

    private static void pulldown(IsoPlayer p) {
        Mover.vel.x *= Cfg.pulldownKeep();
        Mover.vel.y *= Cfg.pulldownKeep();
        if (p.getLastFallSpeed() < PULL_SPEED) p.setLastFallSpeed(PULL_SPEED);
        long now = System.nanoTime();
        jumpLockUntil = now + (long) (Cfg.pulldownLockout() * 1e9);
        Mover.jumpQueuedAt = 0;
        pulledThisAir = true;
        pulledAt = now;
    }

    /** Zombies start thumping the prop this close to it (tiles). */
    private static final double THUMP_RANGE = 0.6;
    /** Path goal this far out from the prop's side, and from its corner (tiles). */
    private static final float SIDE_GAP = 0.35f, CORNER_GAP = 0.25f;
    /** The prop stays yours this long after leaving it, through a hop. */
    private static final long UNDERFOOT_HOLD_NS = 500_000_000L;
    private static final long DRIVE_EVERY_NS = 250_000_000L, BREAK_RESEND_NS = 1_000_000_000L;
    /** Sides first, then corners. */
    private static final int[] SIDE_DX = {0, 0, -1, 1, -1, 1, -1, 1}, SIDE_DY = {-1, 1, 0, 0, -1, -1, 1, 1};

    /** The prop you stand on while zombies may break it, and all its tiles. */
    static IsoObject underfoot;
    private static final ArrayList<IsoObject> underfootParts = new ArrayList<>();
    private static long underfootSeenAt, drivenAt, breakSentAt;
    private static IsoObject breakSentFor;

    /** Drops world references, on game start and the main menu. */
    static void reset() {
        reachPlayer = null;
        reachZombie = null;
        underfoot = breakSentFor = null;
        underfootParts.clear();
        jumpLockUntil = 0;
    }

    /** Plain world furniture on a blocked square; doors, windows and built things stay vanilla. */
    private static boolean breakable(IsoObject o) {
        IsoGridSquare sq = o.getSquare();
        return Cfg.propBreak() && o.getClass() == IsoObject.class && !o.isDestroyed() && sq != null && (sq.isSolid() || sq.isSolidTrans());
    }

    /** Per frame, keeps the prop under you and sets nearby chasers on it while you stand there. */
    static void updateRaised(IsoPlayer p, boolean grounded) {
        long now = System.nanoTime();
        IsoObject o = Floors.floorKind == Floors.FLOOR_PROP ? Floors.floorObject : null;
        if (o != null && breakable(o)) {
            if (o != underfoot) {
                underfoot = o;
                o.getSpriteGridObjectsIncludingSelf(underfootParts);
            }
            underfootSeenAt = now;
        } else if (underfoot != null && (now - underfootSeenAt > UNDERFOOT_HOLD_NS || underfoot.getSquare() == null)) {
            underfoot = null;
            underfootParts.clear();
        }
        if (underfoot == null || o != underfoot || !grounded || now - drivenAt < DRIVE_EVERY_NS) return;
        drivenAt = now;
        if (!p.isGhostMode() && !p.isZombiesDontAttack()) setOnProp(p);
    }

    /** Zombies beside the prop thump it, as they would a barricade. */
    private static void setOnProp(IsoPlayer p) {
        IsoCell cell = p.getCell();
        IsoGridSquare home = underfoot.getSquare();
        if (cell == null || home == null) return;
        int level = home.getZ(), x0 = home.getX(), x1 = x0, y0 = home.getY(), y1 = y0;
        for (IsoObject part : underfootParts) {
            IsoGridSquare sq = part.getSquare();
            if (sq == null || sq.getZ() != level) continue;
            x0 = Math.min(x0, sq.getX());
            x1 = Math.max(x1, sq.getX());
            y0 = Math.min(y0, sq.getY());
            y1 = Math.max(y1, sq.getY());
        }
        for (int gx = x0 - 1; gx <= x1 + 1; gx++) {
            for (int gy = y0 - 1; gy <= y1 + 1; gy++) {
                IsoGridSquare sq = cell.getGridSquare(gx, gy, level);
                if (sq == null) continue;
                ArrayList<IsoMovingObject> here = sq.getMovingObjects();
                for (int i = 0; i < here.size(); i++) {
                    if (!(here.get(i) instanceof IsoZombie z) || z.getTarget() != p || !canThump(z)) continue;
                    IsoObject part = nearestPart(z.getX(), z.getY(), level);
                    // Not through a wall, door or window.
                    if (part == null || sq != part.getSquare() && sq.isSomethingTo(part.getSquare())) continue;
                    z.setThumpTimer(0);
                    z.setThumpTarget(part);
                    z.setPath2(null);
                }
            }
        }
    }

    /** Ours to drive, up and not already thumping. */
    private static boolean canThump(IsoZombie z) {
        if (z.isDead() || (GameClient.client && z.isRemoteZombie()) || z.getThumpTarget() != null || z.isUseless() || z.isKnockedDown()) {
            return false;
        }
        State s = z.getCurrentState();
        return s == ZombieIdleState.instance() || s == PathFindState.instance() || s == WalkTowardState.instance()
                || s == LungeState.instance() || s == ZombieTurnAlerted.instance();
    }

    /** The prop tile within thump range of (x, y), or null. */
    private static IsoObject nearestPart(double x, double y, int level) {
        IsoObject best = null;
        double bestD = THUMP_RANGE;
        for (IsoObject part : underfootParts) {
            IsoGridSquare sq = part.getSquare();
            if (sq == null || sq.getZ() != level) continue;
            double d = Props.squareDistance(sq.getX(), sq.getY(), x, y);
            if (d <= bestD) {
                bestD = d;
                best = part;
            }
        }
        return best;
    }

    /** getThumpableFor exit. A zombie after you can thump the prop you stand on; any of its tiles takes the hit on yours. */
    public static Thumpable onThumpableFor(IsoObject self, IsoGameCharacter chr, Thumpable ret) {
        IsoObject prop = underfoot;
        if (ret != null || prop == null || !(chr instanceof IsoZombie z) || z.getTarget() != Mover.self) return ret;
        if (self != prop && !underfootParts.contains(self)) return ret;
        return prop.isDestroyed() || prop.getSquare() == null ? ret : prop;
    }

    /** Thump enter. In MP the server breaks it; a client-side break would only remove it here. */
    public static void onThump(IsoObject self, IsoMovingObject thumper) {
        if (self != underfoot || !GameClient.client || self.damage > 1) return;
        self.damage = 2; // one thump takes at most 1
        IsoGridSquare sq = self.getSquare();
        IsoPlayer p = Mover.self;
        String sprite = self.getSpriteName();
        long now = System.nanoTime();
        if (sq == null || p == null || sprite == null || (self == breakSentFor && now - breakSentAt < BREAK_RESEND_NS)) return;
        if (self != breakSentFor && thumper instanceof IsoGameCharacter c && c.getEmitter() != null) c.getEmitter().playSound("BreakObject", self);
        breakSentFor = self;
        breakSentAt = now;
        Net.send(p, "brk", String.format(Locale.ROOT, "%d;%d;%d;%d;%s", sq.getX(), sq.getY(), sq.getZ(), self.getObjectIndex(), sprite));
    }

    private static Field pathOwner;
    private static boolean pathOwnerFailed;

    /** The character a path behavior moves, it has no getter. */
    private static IsoGameCharacter owner(PathFindBehavior2 pf) {
        if (pathOwnerFailed) return null;
        try {
            if (pathOwner == null) {
                pathOwner = PathFindBehavior2.class.getDeclaredField("chr");
                pathOwner.setAccessible(true);
            }
            return (IsoGameCharacter) pathOwner.get(pf);
        } catch (Throwable t) {
            pathOwnerFailed = true;
            Log.warn("zombie prop pathing off: " + t);
            return null;
        }
    }

    /** PathFindBehavior2.pathToCharacter exit, also each WalkTowardState tick. Chasers of a player on a prop go to its nearest open side, not into it. */
    public static void onPathToCharacter(PathFindBehavior2 pf, IsoGameCharacter target) {
        if (target != Mover.self || Floors.floorKind != Floors.FLOOR_PROP || !Mover.baseActive) return;
        IsoCell cell = target.getCell();
        if (cell == null || target.getVehicle() != null || !(owner(pf) instanceof IsoZombie z)) return;
        int gx = Floors.floorPropX, gy = Floors.floorPropY, level = (int) Math.floor(target.getZ());
        IsoGridSquare sq = cell.getGridSquare(gx, gy, level);
        if (sq == null || !(sq.isSolid() || sq.isSolidTrans())) return;
        // Level with you along a side.
        float alongX = Math.max(gx + 0.2f, Math.min(gx + 0.8f, target.getX()));
        float alongY = Math.max(gy + 0.2f, Math.min(gy + 0.8f, target.getY()));
        float bestX = 0, bestY = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < SIDE_DX.length && (i < 4 || best == Double.MAX_VALUE); i++) {
            int dx = SIDE_DX[i], dy = SIDE_DY[i];
            if (!pf.isGoodChairAdjacentSquare(sq, cell.getGridSquare(gx + dx, gy + dy, level))) continue;
            float gap = i < 4 ? SIDE_GAP : CORNER_GAP;
            float tx = dx == 0 ? alongX : dx < 0 ? gx - gap : gx + 1 + gap;
            float ty = dy == 0 ? alongY : dy < 0 ? gy - gap : gy + 1 + gap;
            double d = Math.hypot(tx - z.getX(), ty - z.getY());
            if (d < best) {
                best = d;
                bestX = tx;
                bestY = ty;
            }
        }
        if (best != Double.MAX_VALUE) pf.setData(bestX, bestY, level);
    }
}

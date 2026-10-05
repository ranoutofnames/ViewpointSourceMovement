package sourcemove;

import java.util.ArrayList;
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

/** Zombies vs. a jumping player, grab reach and pull-downs. */
public final class Zombies {
    private Zombies() {}

    private static boolean reachSwapped;
    private static int reachDepth;
    private static float savedZ, savedLastZ;
    private static IsoPlayer reachPlayer;

    /** Count a raised player at the zombie's height while within grab reach; nesting-safe. */
    public static void onZombieAttackEnter(IsoGameCharacter zombie) {
        if (reachDepth++ > 0) return;
        reachSwapped = false;
        // MP target may be another mod user.
        if (!(zombie instanceof IsoZombie z) || !(z.getTarget() instanceof IsoPlayer p)) return;
        if (p == Mover.self ? !Mover.baseActive : !Remote.active(p)) return;
        float dz = p.getZ() - zombie.getZ();
        if (dz < 0.2f || dz >= Cfg.zombieReach) return;
        savedZ = p.getZ();
        savedLastZ = p.getLastZ();
        p.setZ(zombie.getZ());
        reachPlayer = p;
        reachSwapped = true;
    }

    public static void onZombieAttackExit() {
        if (reachDepth > 0 && --reachDepth > 0) return;
        if (!reachSwapped) return;
        reachSwapped = false;
        IsoPlayer p = reachPlayer;
        reachPlayer = null;
        if (p == null) return;
        p.setZ(savedZ);
        p.setLastZ(savedLastZ);
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
        for (int level = (int) Math.floor(z - Cfg.zombieReach); level <= (int) Math.floor(z); level++) {
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
                        if (dz < 0 || dz >= Cfg.zombieReach || zed.getTarget() != p || !canGrab(zed)) continue;
                        if (sq != mine && mine != null && sq.isSomethingTo(mine)) continue;
                        grabbers++;
                    }
                }
            }
        }
        int group = Math.max(1, Cfg.pulldownGroup);
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
        Mover.vel.x *= Cfg.pulldownKeep;
        Mover.vel.y *= Cfg.pulldownKeep;
        if (p.getLastFallSpeed() < PULL_SPEED) p.setLastFallSpeed(PULL_SPEED);
        long now = System.nanoTime();
        jumpLockUntil = now + (long) (Cfg.pulldownLockout * 1e9);
        Mover.jumpQueuedAt = 0;
        pulledThisAir = true;
        pulledAt = now;
    }
}

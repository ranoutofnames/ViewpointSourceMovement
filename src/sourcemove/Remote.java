package sourcemove;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

import zombie.GameTime;
import zombie.ai.State;
import zombie.ai.states.ClimbOverFenceState;
import zombie.ai.states.ClimbOverWallState;
import zombie.ai.states.ClimbThroughWindowState;
import zombie.characters.FallingConstants;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.NetworkPlayerAI;
import zombie.network.GameClient;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehicleManager;

/** MP client. Other mod users shown at their reported height and position, with their leap and landing sounds. */
public final class Remote {
    private Remote() {}

    /** Reports older than this are stale. */
    private static final long STALE_NS = 2_500_000_000L;
    /** Longest arc extrapolated past the last report (s). */
    private static final double MAX_ARC = 0.6;
    /** Max drift from vanilla's position (levels) before we stop trusting reports. */
    private static final double MAX_GAP = 2.5;
    /** Hold a landing height until the position update catches up. */
    private static final long LAND_HOLD_NS = 500_000_000L;
    /** Position smoothing (s). */
    private static final double SMOOTH = 0.08;
    private static final String[] LAND_EVENTS = {null, "LandLight", "LandHeavy", "LandHeavyFromFall"};

    static final class St {
        float z, vz;
        int flags;
        /** Car they ride and their spot on it (x forward). */
        short vehicle;
        float localX, localY;
        /** When they last landed. */
        long landedAt;
        boolean jumpAlt;
        long at;
        boolean applied;
        float simZ, simLastZ, visZ;
        float startX, startY;
    }

    private static final Map<IsoPlayer, St> states = new IdentityHashMap<>();

    static void onState(short id, String d) {
        IsoPlayer p = GameClient.IDToPlayerMap.get(id);
        if (p == null || p.isLocalPlayer() || d == null) return;
        String[] f = d.split(";");
        if (f.length < 5) return;
        St st = states.computeIfAbsent(p, k -> new St());
        try {
            st.z = Float.parseFloat(f[0]);
            st.vz = Float.parseFloat(f[1]);
            st.flags = Integer.parseInt(f[2]);
            float anim = Float.parseFloat(f[3]);
            int land = Integer.parseInt(f[4]);
            if ((st.flags & Net.F_RIDE) != 0 && f.length >= 8) {
                st.vehicle = Short.parseShort(f[5]);
                st.localX = Float.parseFloat(f[6]);
                st.localY = Float.parseFloat(f[7]);
            } else {
                st.flags &= ~Net.F_RIDE;
            }
            st.at = System.nanoTime();
            if ((st.flags & Net.F_LAND) != 0) st.landedAt = st.at;
            if ((st.flags & Net.F_JUMP) != 0) {
                if (anim > 0) {
                    st.jumpAlt = !st.jumpAlt;
                    p.setVariable(Mover.ANIM_ALT_VAR, st.jumpAlt);
                    p.setVariable(Mover.ANIM_VAR, true);
                    p.setVariable(Mover.ANIM_SPEED_VAR, anim);
                }
                if (p.getEmitter() != null) p.getEmitter().playFootsteps("HumanFootstepsCombined", 1.0f);
            }
            if ((st.flags & Net.F_LAND) != 0 || (st.flags & Net.F_ANIM) == 0) p.setVariable(Mover.ANIM_VAR, false);
            if ((st.flags & Net.F_LAND) != 0 && land > 0 && land < LAND_EVENTS.length && p.getEmitter() != null) {
                p.getEmitter().playSoundImpl(LAND_EVENTS[land], p);
            }
        } catch (RuntimeException e) {
            // malformed, ignore
        }
        if (states.size() > 64) prune();
    }

    private static void prune() {
        long now = System.nanoTime();
        for (Iterator<Map.Entry<IsoPlayer, St>> it = states.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<IsoPlayer, St> e = it.next();
            if (now - e.getValue().at > 30 * STALE_NS && !e.getValue().applied) it.remove();
        }
    }

    private static St fresh(IsoGameCharacter c) {
        if (!(c instanceof IsoPlayer p) || !GameClient.client || p.isLocalPlayer()) return null;
        St st = states.get(p);
        return st != null && System.nanoTime() - st.at < STALE_NS ? st : null;
    }

    /** Using Source movement now. */
    static boolean active(IsoGameCharacter c) {
        St st = fresh(c);
        return st != null && (st.flags & Net.F_ACTIVE) != 0;
    }

    /** In the air (no footsteps). */
    static boolean airborne(IsoGameCharacter c) {
        St st = fresh(c);
        return st != null && (st.flags & Net.F_AIR) != 0;
    }

    /** update enter, put back the real height for vanilla's simulation. */
    public static void onUpdateEnter(IsoPlayer p) {
        if (!GameClient.client || p.isLocalPlayer()) return;
        St st = states.get(p);
        if (st == null) return;
        if (st.applied && p.getZ() == st.visZ) {
            p.setZ(st.simZ);
            p.setLastZ(st.simLastZ);
        }
        st.applied = false;
        st.startX = p.getX();
        st.startY = p.getY();
    }

    /** Their falls are theirs to animate. */
    static boolean fallsOverridden(IsoGameCharacter c) {
        return Cfg.fallMode != Cfg.FALL_VANILLA && active(c);
    }

    /** update exit, position and height from reports. */
    public static void onUpdateExit(IsoPlayer p) {
        St st = fresh(p);
        if (st == null || (st.flags & Net.F_ACTIVE) == 0 || p.getVehicle() != null || p.isDead()) return;
        if (Cfg.fallMode != Cfg.FALL_VANILLA) {
            p.setbFalling(false);
            p.setFallTime(0);
        }
        State s = p.getCurrentState();
        if (s == ClimbOverFenceState.instance() || s == ClimbThroughWindowState.instance() || s == ClimbOverWallState.instance()) return;
        long now = System.nanoTime();
        // Grid collision off a roof edge would undo the easing, vanilla turns it back on next frame.
        if ((st.flags & Net.F_AIR) != 0 || now - st.landedAt < LAND_HOLD_NS) {
            p.setCollidable(false);
            p.setHasObstacleOnPath(false);
        }

        // On a car, where they stand on it as we see it.
        BaseVehicle car = (st.flags & Net.F_RIDE) != 0 && VehicleManager.instance != null
                ? VehicleManager.instance.getVehicleByID(st.vehicle) : null;
        NetworkPlayerAI ai = p.getNetworkCharacterAI();
        if (car != null) {
            double yaw = Rides.yaw(car), cos = Math.cos(yaw), sin = Math.sin(yaw);
            p.setX((float) (car.getX() + st.localX * cos - st.localY * sin));
            p.setY((float) (car.getY() + st.localX * sin + st.localY * cos));
            float roof = Rides.roofZ(car);
            if (!Float.isNaN(roof)) st.z = roof;
        } else if (ai != null) {
            // Ease to the network target instead of walking there.
            double tx = ai.targetX - st.startX, ty = ai.targetY - st.startY;
            double dist = Math.hypot(tx, ty);
            if (dist > 0.01 && dist < 6) {
                double dt = Math.min(0.1, Math.max(0, GameTime.getInstance().getTimeDelta()));
                double k = 1 - Math.exp(-dt / SMOOTH);
                p.setX((float) (st.startX + tx * k));
                p.setY((float) (st.startY + ty * k));
            }
        }

        // Airborne follows the reported arc, standing never goes below vanilla, just landed holds.
        float sim = p.getZ();
        double z = st.z;
        if ((st.flags & Net.F_AIR) != 0) {
            double t = Math.min(MAX_ARC, (now - st.at) / 1e9);
            z = st.z + st.vz * t - 0.5 * FallingConstants.IsoFallAcceleration * t * t;
            if (Math.abs(z - sim) > MAX_GAP) return;
        } else if ((st.flags & Net.F_RAISED) != 0) {
            if (!(z > sim + 0.005) || Math.floor(z) != Math.floor(sim)) return;
        } else if (now - st.landedAt < LAND_HOLD_NS) {
            if (Math.abs(z - sim) < 0.005 || Math.abs(z - sim) > MAX_GAP) return;
        } else {
            return;
        }
        st.simZ = sim;
        st.simLastZ = p.getLastZ();
        p.setZ((float) z);
        st.visZ = p.getZ();
        st.applied = true;
    }
}

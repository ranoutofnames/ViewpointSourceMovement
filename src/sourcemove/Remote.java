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

/**
 * Multiplayer, client side: other players who use Source movement, as seen here. Their positions arrive with
 * whole-level Z and are walked toward at animation speed, so without this they'd never leave the ground and
 * would lag and teleport when bhopping. From their state reports ({@link Net}) we:
 *   - show their real height: set at the end of their update (so rendering and zombie attack checks see it)
 *     and put back at the start of the next one, so vanilla's own simulation of them never sees it;
 *   - move them straight to where the network says they are, at whatever speed that takes;
 *   - play their leap animation, landing sounds, and no footsteps while they're in the air.
 */
public final class Remote {
    private Remote() {}

    /** Reports older than this are stale (the sender stopped being active or left). */
    private static final long STALE_NS = 2_500_000_000L;
    /** Extrapolate an arc at most this long past the last report (s). */
    private static final double MAX_ARC = 0.6;
    /** Don't show them further than this (levels) from where vanilla has them: something's out of sync. */
    private static final double MAX_GAP = 2.5;
    /** Hold a landing height this long while the whole-level position update arrives. */
    private static final long LAND_HOLD_NS = 500_000_000L;
    /** Position smoothing time constant (s). */
    private static final double SMOOTH = 0.08;
    private static final String[] LAND_EVENTS = {null, "LandLight", "LandHeavy", "LandHeavyFromFall"};

    static final class St {
        float z, vz;
        int flags;
        /** Riding a car roof: its vehicle ID and our spot in its frame (x forward along its yaw). */
        short vehicle;
        float localX, localY;
        /** When their last landing report arrived: their height is held there until the position catches up. */
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
            // malformed report: ignore
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

    /** Using Source movement right now (for zombie reach). */
    static boolean active(IsoGameCharacter c) {
        St st = fresh(c);
        return st != null && (st.flags & Net.F_ACTIVE) != 0;
    }

    /** In the air from a jump or drop (no footsteps). */
    static boolean airborne(IsoGameCharacter c) {
        St st = fresh(c);
        return st != null && (st.flags & Net.F_AIR) != 0;
    }

    /** IsoPlayer.update enter: undo last frame's display height so vanilla simulates the real one. */
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

    /** Using Source movement and its fall rules: their falls (and landings) are theirs, not ours to animate. */
    static boolean fallsOverridden(IsoGameCharacter c) {
        return Cfg.fallMode != Cfg.FALL_VANILLA && active(c);
    }

    /** IsoPlayer.update exit: position and display height from the reports. */
    public static void onUpdateExit(IsoPlayer p) {
        St st = fresh(p);
        if (st == null || (st.flags & Net.F_ACTIVE) == 0 || p.getVehicle() != null || p.isDead()) return;
        if (Cfg.fallMode != Cfg.FALL_VANILLA) {
            // Our copy of them falls with vanilla gravity between whole-level position updates: no falling state.
            p.setbFalling(false);
            p.setFallTime(0);
        }
        State s = p.getCurrentState();
        if (s == ClimbOverFenceState.instance() || s == ClimbThroughWindowState.instance() || s == ClimbOverWallState.instance()) return;

        // On a car roof: stand where they are on the car as this client sees it (exact, no network lag).
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
            // Horizontal: ease toward the network's (already extrapolated) target instead of walking there.
            double tx = ai.targetX - st.startX, ty = ai.targetY - st.startY;
            double dist = Math.hypot(tx, ty);
            if (dist > 0.01 && dist < 6) {
                double dt = Math.min(0.1, Math.max(0, GameTime.getInstance().getTimeDelta()));
                double k = 1 - Math.exp(-dt / SMOOTH);
                p.setX((float) (st.startX + tx * k));
                p.setY((float) (st.startY + ty * k));
            }
        }

        // Vertical: in the air, follow the reported arc, across levels too (a drop off a roof); standing on
        // something, never below where vanilla has them; just landed, hold the landing height until the
        // whole-level position update catches up.
        float sim = p.getZ();
        double z = st.z;
        long now = System.nanoTime();
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

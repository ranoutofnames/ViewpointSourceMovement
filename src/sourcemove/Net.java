package sourcemove;

import java.util.Locale;

import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.network.GameClient;
import zombie.network.fields.character.Prediction;
import zombie.network.packets.character.PlayerPacket;
import zombie.vehicles.BaseVehicle;

/**
 * Multiplayer, client side. Positions go out with whole-level Z and with a speed taken from animation root
 * motion (which Source movement discards), so on their own they show nobody jumping and a bhopper standing
 * still. Two fixes:
 *   - a small state stream over Lua client commands ("SourceMovement"/"s"): height, vertical speed and
 *     jump/landing events (payload "z;vz;flags;animSpeed;landKind[;vehicleId;localX;localY]"), sent only while airborne or standing on something (a few per second),
 *     relayed by the server to nearby players ({@link Remote}) and used by its anti-cheat ({@link Server});
 *   - the outgoing position prediction carries our real velocity, so other clients extrapolate correctly.
 */
public final class Net {
    private Net() {}

    static final String MODULE = "SourceMovement";

    /** The server answered our hello: movement may run. */
    public static volatile boolean serverReady;
    /** ...and it runs our Java side, so its anti-cheat knows about jumps. */
    public static volatile boolean serverJava;

    static final int F_ACTIVE = 1, F_AIR = 2, F_ANIM = 4, F_JUMP = 8, F_LAND = 16, F_RAISED = 32, F_RIDE = 64;
    private static final long AIR_NS = 66_000_000L, RIDE_NS = 100_000_000L, RAISED_NS = 400_000_000L, ACTIVE_NS = 1_000_000_000L;

    private static int events;
    private static int landKind;
    private static float animSpeed = 1;
    private static long lastSentAt;
    private static int lastFlags = -1;

    static boolean mp() {
        return GameClient.client;
    }

    /** Takeoff: {@code anim} = leap animation playing at {@code speed}. */
    static void jumped(boolean anim, float speed) {
        events |= F_JUMP;
        animSpeed = anim ? speed : 0;
    }

    /** Landing; {@code kind} 1-3 = light/heavy/very heavy landing sound, 0 = none. */
    static void landed(int kind) {
        events |= F_LAND;
        landKind = kind;
    }

    /**
     * Once per frame for the local player, after our movement ran. {@code ride} = the car we stand on: then the
     * report also carries its vehicle ID and our spot on it in the car's own frame, so other clients place us
     * on the car as they see it rather than chasing our (latency-delayed) world position.
     */
    static void frame(IsoPlayer p, boolean active, boolean grounded, boolean raised, boolean anim, BaseVehicle ride) {
        if (!GameClient.client || !serverReady || p == null) return;
        int flags = (active ? F_ACTIVE : 0) | (grounded ? 0 : F_AIR) | (anim ? F_ANIM : 0) | (raised ? F_RAISED : 0)
                | (ride != null ? F_RIDE : 0);
        long now = System.nanoTime();
        long every = !active ? Long.MAX_VALUE : !grounded ? AIR_NS : ride != null ? RIDE_NS : raised ? RAISED_NS : ACTIVE_NS;
        if (events == 0 && flags == lastFlags && now - lastSentAt < every) return;
        String d = String.format(Locale.ROOT, "%.3f;%.3f;%d;%.2f;%d",
                p.getZ(), -p.getLastFallSpeed(), flags | events, animSpeed, landKind);
        if (ride != null) {
            double yaw = Rides.yaw(ride), cos = Math.cos(yaw), sin = Math.sin(yaw);
            double rx = p.getX() - ride.getX(), ry = p.getY() - ride.getY();
            d += String.format(Locale.ROOT, ";%d;%.3f;%.3f", ride.getId(), rx * cos + ry * sin, -rx * sin + ry * cos);
        }
        if (send(p, "s", d)) {
            lastSentAt = now;
            lastFlags = flags;
            events = 0;
            landKind = 0;
        }
    }

    static boolean send(IsoPlayer p, String command, String d) {
        try {
            KahluaTable t = LuaManager.platform.newTable();
            t.rawset("d", d);
            GameClient.instance.sendClientCommand(p, MODULE, command, t);
            return true;
        } catch (Throwable t) {
            Log.warn("MP: send failed (" + t + ")");
            return false;
        }
    }

    /**
     * NetworkPlayerAI.set(PlayerPacket) exit, local player: vanilla filled the prediction from root motion.
     * Give it our velocity so other clients extrapolate a bhopper along its real path.
     */
    public static void onPrediction(PlayerPacket packet) {
        if (!GameClient.client || packet == null || !Mover.owned || packet.getPlayer() != Mover.self) return;
        Prediction pr = packet.prediction;
        if (pr.type == 2) return; // pathfinding (click-to-walk): vanilla's
        double vx = Mover.velX(), vy = Mover.velY(), speed = Math.hypot(vx, vy);
        if (speed < 0.05) return;
        pr.type = 1;
        pr.speed = (float) speed;
        pr.moveDirection = (float) Math.atan2(vy, vx);
        // Distance byte is tiles * 8; extrapolate up to half a second (vanilla: 1.2 s at walking speeds).
        pr.distance = (byte) Math.min(127, Math.round(speed * 0.5 * Prediction.DISTANCE_SCALE));
    }

    /** From Lua (OnServerCommand). */
    static void onServerCommand(String command, double id, String d) {
        switch (command) {
            case "welcome" -> {
                serverReady = true;
                serverJava = "java".equals(d);
                Log.info("MP: server ready" + (serverJava ? "" : " (no Java side there: anti-cheat may pull you back)"));
            }
            case "s" -> Remote.onState((short) id, d);
            default -> { }
        }
    }

    static void reset() {
        serverReady = serverJava = false;
        lastFlags = -1;
        events = 0;
    }
}

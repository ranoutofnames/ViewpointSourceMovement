package sourcemove;

import java.util.Locale;

import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.characters.NetworkPlayerAI;
import zombie.network.GameClient;
import zombie.network.fields.character.Prediction;
import zombie.network.packets.character.PlayerPacket;
import zombie.vehicles.BaseVehicle;

/** MP client. Positions alone show no jumps, so a small state stream (height, vertical speed, jump/land events) and real-velocity prediction. */
public final class Net {
    private Net() {}

    static final String MODULE = "SourceMovement";

    /** The server answered, movement may run. */
    public static volatile boolean serverReady;
    /** The server runs our Java side. */
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

    /** Takeoff; anim = leap animation speed. */
    static void jumped(boolean anim, float speed) {
        events |= F_JUMP;
        animSpeed = anim ? speed : 0;
    }

    /** Landing; kind 1-3 = sound weight, 0 = none. */
    static void landed(int kind) {
        events |= F_LAND;
        landKind = kind;
    }

    /** Per frame; on a car, our spot in its frame so others see us on it. */
    static void frame(IsoPlayer p, boolean active, boolean grounded, boolean raised, boolean anim, BaseVehicle ride) {
        if (!GameClient.client || !serverReady || p == null) return;
        int flags = (active ? F_ACTIVE : 0) | (grounded ? 0 : F_AIR) | (anim ? F_ANIM : 0) | (raised ? F_RAISED : 0)
                | (ride != null ? F_RIDE : 0);
        long now = System.nanoTime();
        long every = !active ? Long.MAX_VALUE : !grounded ? AIR_NS : ride != null ? RIDE_NS : raised ? RAISED_NS : ACTIVE_NS;
        if (events == 0 && flags == lastFlags && now - lastSentAt < every) return;
        // Vanilla barely sends position mid-air, so ask for one with each report.
        NetworkPlayerAI ai = p.getNetworkCharacterAI();
        if (ai != null && ((active && !grounded) || (events & (F_JUMP | F_LAND)) != 0)) ai.needToUpdate();
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

    /** Prediction from our velocity, not root motion. */
    public static void onPrediction(PlayerPacket packet) {
        if (!GameClient.client || packet == null || !Mover.owned || packet.getPlayer() != Mover.self) return;
        Prediction pr = packet.prediction;
        if (pr.type == 2) return; // click-to-walk is vanilla's
        double vx = Mover.velX(), vy = Mover.velY(), speed = Math.hypot(vx, vy);
        if (speed < 0.05) return;
        pr.type = 1;
        pr.speed = (float) speed;
        pr.moveDirection = (float) Math.atan2(vy, vx);
        // Extrapolate half a second (byte is tiles * 8).
        pr.distance = (byte) Math.min(127, Math.round(speed * 0.5 * Prediction.DISTANCE_SCALE));
    }

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

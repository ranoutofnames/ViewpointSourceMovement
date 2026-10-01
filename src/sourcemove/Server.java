package sourcemove;

import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import zombie.characters.FallingConstants;
import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.Vector3;
import zombie.network.anticheats.AntiCheatNoClip;
import zombie.network.anticheats.AntiCheatSpeed;
import zombie.network.fields.IMovable;
import zombie.network.packets.INetworkPacket;
import zombie.network.packets.character.PlayerPacket;

/**
 * Multiplayer, server side. Player positions reach the server with whole-level Z (Prediction.z is a byte), so
 * the anti-cheat can't see a jump: AntiCheatNoClip rubber-bands every fence, prop and window crossing, and
 * AntiCheatSpeed kicks fast bhoppers. Clients that run this mod report their height and vertical speed while
 * airborne (a few times a second, see {@link Net}); from a takeoff report the server knows the apex of that
 * jump. A crossing the vanilla check rejects is let through only if every edge and square along it is low
 * enough for that apex to clear. Walls stay walls unless a recent jump could really get over them, and
 * teleport-length moves are still refused.
 */
public final class Server {
    private Server() {}

    static final String VERSION = "1";

    /** How long a reported apex counts as evidence (ms). A jump lasts under a second at default height. */
    private static final long EVIDENCE_MS = 2000;
    /** Slack on the height needed, for report timing and float rounding (levels). */
    private static final double SLACK = 0.05;
    /** Heights the server assumes for solid squares: it has no textures or tile geometry to measure props. */
    private static final double SOLIDTRANS_TOP = 0.25, SOLID_TOP = 0.5;
    private static final double G = FallingConstants.IsoFallAcceleration;

    static final class State {
        /** Highest absolute Z the player's recent jump reaches, and when it was reported. */
        double apex = Double.NEGATIVE_INFINITY;
        long apexAt;
        /** Last position the anti-cheat accepted, and when. */
        long okAt;
        int allowed;
    }

    private static final Map<IsoPlayer, State> players = new WeakHashMap<>();
    private static final Vector3 target = new Vector3();

    /** A client running this mod said hello. */
    static synchronized void hello(IsoPlayer p) {
        if (p == null) return;
        if (players.putIfAbsent(p, new State()) == null) Log.info("MP: " + p.getUsername() + " uses Source movement");
    }

    /**
     * A state report ({@link Net} payload: z;vz;flags;...). Keeps the jump apex as evidence for the
     * anti-cheat. Values are clamped to what the sandbox settings allow, so a doctored report can't claim more.
     */
    static synchronized void report(IsoPlayer p, String d) {
        State st = players.get(p);
        if (st == null || d == null) return;
        String[] f = d.split(";");
        if (f.length < 2) return;
        double z, vz;
        try {
            z = Double.parseDouble(f[0]);
            vz = Double.parseDouble(f[1]);
        } catch (NumberFormatException e) {
            return;
        }
        if (!Double.isFinite(z) || !Double.isFinite(vz) || Math.abs(z - p.getZ()) > 2) return;
        double vMax = Mover.maxLaunchSpeed() * 1.05 + 0.2;
        vz = Math.min(vz, vMax);
        double apex = z + (vz > 0 ? vz * vz / (2 * G) : 0);
        long now = System.currentTimeMillis();
        if (now - st.apexAt > EVIDENCE_MS || apex >= st.apex) {
            st.apex = apex;
            st.apexAt = now;
        }
    }

    // ---------------------------------------------------------------- anti-cheat

    /** AntiCheatNoClip.validate exit: {@code ret} is vanilla's verdict (null = fine). */
    public static synchronized String noClip(UdpConnection con, INetworkPacket packet, String ret) {
        if (!(packet instanceof AntiCheatNoClip.IAntiCheat f)) return ret;
        int idx = f.getPlayerIndex();
        if (con == null || idx < 0 || idx >= con.players.length) return ret;
        IsoPlayer p = con.players[idx];
        State st = p != null ? players.get(p) : null;
        if (st == null) return ret;
        long now = System.currentTimeMillis();
        if (ret == null) {
            st.okAt = now;
            return null;
        }
        if (!Cfg.enabled) return ret;
        Vector3 from = con.releventPos[idx];
        f.getPosition(target);
        try {
            if (!crossingOk(p, st, from, target, now)) return ret;
        } catch (Throwable t) {
            Log.warn("MP: crossing check failed (" + t + "); using vanilla verdict");
            return ret;
        }
        st.okAt = now;
        if (st.allowed++ < 5) {
            Log.info(String.format(Locale.ROOT, "MP: allowed %s (%.1f,%.1f,%d) -> (%.1f,%.1f,%d), vanilla said: %s",
                    p.getUsername(), from.x, from.y, (int) from.z, target.x, target.y, (int) target.z, ret));
        }
        return null;
    }

    /** AntiCheatSpeed.validate exit: raise the on-foot limit (vanilla 20 tiles/s) for players using this mod. */
    public static synchronized String speed(UdpConnection con, INetworkPacket packet, String ret) {
        if (ret == null || !Cfg.enabled || !ret.startsWith("speed=")) return ret;
        if (!(packet instanceof PlayerPacket pp) || !(packet instanceof AntiCheatSpeed.IAntiCheat f)) return ret;
        IsoPlayer p = pp.getPlayer();
        if (p == null || !players.containsKey(p)) return ret;
        double cap = Cfg.mpSpeedLimit;
        if (cap <= 0) return null;
        for (int i = 0; i < f.getMovableCount(); i++) {
            IMovable m = f.getMovable(i);
            if (m != null && (m.isVehicle() || m.getSpeed() > cap)) return ret;
        }
        return null;
    }

    /**
     * Could a Source-movement jump have made this move? Walks the squares on the straight line between the two
     * positions and checks every edge and solid square against the highest recent jump (or the ground).
     */
    private static boolean crossingOk(IsoPlayer p, State st, Vector3 from, Vector3 to, long now) {
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (cell == null || from == null) return false;
        int level = (int) Math.floor(from.z), toLevel = (int) Math.floor(to.z);
        if (toLevel > level + 1) return false;

        double len = Math.hypot(to.x - from.x, to.y - from.y);
        double secs = st.okAt == 0 ? 1 : Math.max(0.2, (now - st.okAt) / 1000.0);
        double cap = Cfg.mpSpeedLimit > 0 ? Math.max(20, Cfg.mpSpeedLimit) : 200;
        if (len > cap * secs + 2) return false;

        double feet = now - st.apexAt <= EVIDENCE_MS ? Math.max(level, st.apex) : level;
        // Onto a roof one level up: the jump has to reach (nearly) that floor.
        if (toLevel == level + 1 && feet < toLevel - Ledges.STEP_UP - SLACK - 0.2) return false;
        return lineClear(cell, p, level, from.x, from.y, to.x, to.y, feet - level);
    }

    /** Every square step on the straight line from (x0,y0) to (x1,y1) must be clearable with {@code feet}. */
    private static boolean lineClear(IsoCell cell, IsoPlayer p, int z, double x0, double y0, double x1, double y1, double feet) {
        return Physics.gridWalk(x0, y0, x1, y1, 64, (ax, ay, bx, by) -> stepClear(cell, p, z, ax, ay, bx, by, feet));
    }

    private static boolean stepClear(IsoCell cell, IsoPlayer p, int z, int ax, int ay, int bx, int by, double feet) {
        double h;
        IsoObject window = ax == bx || ay == by ? Windows.between(cell, z, ax, ay, bx, by) : null;
        if (window != null && Cfg.windowJump && (Windows.open(window, p) || Cfg.windowCrash && Windows.crashable(window))) {
            h = Windows.SILL;
        } else {
            h = Ledges.crossingHeight(cell, z, ax, ay, bx, by);
        }
        IsoGridSquare b = cell.getGridSquare(bx, by, z);
        // A tent's sides are slopes you walk up from the ground.
        if (b != null && (b.isSolid() || b.isSolidTrans()) && !Props.hasTent(b)) {
            h = Math.max(h, b.has(IsoFlagType.solid) ? SOLID_TOP : SOLIDTRANS_TOP);
        }
        return h <= 0 || feet >= h - Ledges.STEP_UP - SLACK;
    }
}

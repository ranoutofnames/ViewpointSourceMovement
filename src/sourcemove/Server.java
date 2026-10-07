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
import zombie.network.GameServer;
import zombie.network.ServerOptions;
import zombie.network.anticheats.AntiCheatNoClip;
import zombie.network.anticheats.AntiCheatSpeed;
import zombie.network.fields.IMovable;
import zombie.network.packets.INetworkPacket;
import zombie.network.packets.character.PlayerPacket;

/** MP server. The anti-cheat can't see jumps, so crossings a reported jump could clear are let through. */
public final class Server {
    private Server() {}

    /** How long a reported apex counts (ms). */
    private static final long EVIDENCE_MS = 2000;
    /** Slack for report timing and rounding (levels). */
    private static final double SLACK = 0.05;
    /** Assumed solid-square heights; the server can't measure props. */
    private static final double SOLIDTRANS_TOP = 0.25, SOLID_TOP = 0.5;
    private static final double G = FallingConstants.IsoFallAcceleration;

    static final class State {
        /** Recent jump apex (absolute Z). */
        double apex = Double.NEGATIVE_INFINITY;
        long apexAt;
        /** Last accepted position. */
        long okAt;
        int allowed;
    }

    private static final Map<IsoPlayer, State> players = new WeakHashMap<>();
    private static final Vector3 target = new Vector3();

    /** Each respawn is a new IsoPlayer, so a report registers its sender too. */
    private static State state(IsoPlayer p) {
        return players.computeIfAbsent(p, k -> {
            Log.info("MP: " + k.getUsername() + " uses Source movement");
            return new State();
        });
    }

    static synchronized void hello(IsoPlayer p) {
        if (p != null) state(p);
    }

    /** State report; keeps the jump apex, clamped to the sandbox settings. */
    static synchronized void report(IsoPlayer p, String d) {
        if (p == null || d == null) return;
        State st = state(p);
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
        double vMax = Ramps.maxLaunchSpeed() * 1.05 + 0.2;
        vz = Math.min(vz, vMax);
        double apex = z + (vz > 0 ? vz * vz / (2 * G) : 0);
        long now = System.currentTimeMillis();
        if (now - st.apexAt > EVIDENCE_MS || apex >= st.apex) {
            st.apex = apex;
            st.apexAt = now;
        }
    }


    /** AntiCheatNoClip exit; ret null = vanilla accepted. */
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

    /** AntiCheatSpeed exit, mod users get the sandbox speed limit. */
    public static synchronized String speed(UdpConnection con, INetworkPacket packet, String ret) {
        if (ret == null || !ret.startsWith("speed=")) return ret;
        if (!(packet instanceof PlayerPacket pp) || !(packet instanceof AntiCheatSpeed.IAntiCheat f)) return ret;
        IsoPlayer p = pp.getPlayer();
        if (p == null || !players.containsKey(p)) return ret;
        double cap = Cfg.mpSpeedLimit();
        if (cap <= 0) return null;
        for (int i = 0; i < f.getMovableCount(); i++) {
            IMovable m = f.getMovable(i);
            if (m != null && (m.isVehicle() || m.getSpeed() > cap)) return ret;
        }
        return null;
    }

    private static boolean quieting;

    /** Keeps our state stream out of cmd.txt unless the admin already filters our module. */
    public static void quietCommandLog() {
        if (quieting) return;
        ServerOptions.StringServerOption opt = ServerOptions.getInstance().clientCommandFilter;
        String filter = opt.getValue() != null ? opt.getValue() : "";
        for (String s : filter.split(";")) {
            if (s.startsWith(Net.MODULE + ".", 1)) return;
        }
        // Only the parsed filter keeps it, so the server ini never gets it.
        opt.setValue(filter + ";-" + Net.MODULE + ".s");
        quieting = true;
        try {
            GameServer.initClientCommandFilter();
            Log.info("MP: state reports kept out of cmd.txt");
        } finally {
            quieting = false;
            opt.setValue(filter);
        }
    }

    /** Could a recent jump have made this move? Checks every edge and solid square on the line. */
    private static boolean crossingOk(IsoPlayer p, State st, Vector3 from, Vector3 to, long now) {
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (cell == null || from == null) return false;
        int level = (int) Math.floor(from.z), toLevel = (int) Math.floor(to.z);
        if (toLevel > level + 1) return false;

        double len = Math.hypot(to.x - from.x, to.y - from.y);
        double secs = st.okAt == 0 ? 1 : Math.max(0.2, (now - st.okAt) / 1000.0);
        double cap = Cfg.mpSpeedLimit() > 0 ? Math.max(20, Cfg.mpSpeedLimit()) : 200;
        if (len > cap * secs + 2) return false;
        // Dropping off is fine, vanilla allows it too.
        if (toLevel < level) return true;

        double feet = now - st.apexAt <= EVIDENCE_MS ? Math.max(level, st.apex) : level;
        // Onto a roof the jump must nearly reach it.
        if (toLevel == level + 1 && feet < toLevel - Ledges.STEP_UP - SLACK - 0.2) return false;
        return lineClear(cell, p, level, from.x, from.y, to.x, to.y, feet - level);
    }

    private static boolean lineClear(IsoCell cell, IsoPlayer p, int z, double x0, double y0, double x1, double y1, double feet) {
        return Physics.gridWalk(x0, y0, x1, y1, 64, (ax, ay, bx, by) -> stepClear(cell, p, z, ax, ay, bx, by, feet));
    }

    private static boolean stepClear(IsoCell cell, IsoPlayer p, int z, int ax, int ay, int bx, int by, double feet) {
        double h;
        IsoObject window = ax == bx || ay == by ? Windows.between(cell, z, ax, ay, bx, by) : null;
        if (window != null && Cfg.windowJump() && (Windows.open(window, p) || Cfg.windowCrash() && Windows.crashable(window))) {
            h = Windows.SILL;
        } else {
            h = Ledges.crossingHeight(cell, z, ax, ay, bx, by);
        }
        IsoGridSquare b = cell.getGridSquare(bx, by, z);
        // Tent sides are walked up.
        if (b != null && (b.isSolid() || b.isSolidTrans()) && !Props.hasTent(b)) {
            h = Math.max(h, b.has(IsoFlagType.solid) ? SOLID_TOP : SOLIDTRANS_TOP);
        }
        return h <= 0 || feet >= h - Ledges.STEP_UP - SLACK;
    }
}

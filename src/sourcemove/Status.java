package sourcemove;

import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.network.GameClient;
import zombie.vehicles.BaseVehicle;

/** Debug and speed overlay text. */
final class Status {
    private Status() {}

    private static double measuredSpeed, hudX, hudY;
    private static boolean hudSeen;

    /** Measured speed, smoothed, for when vanilla moves you. */
    static void measureSpeed(IsoPlayer p, double dt) {
        if (hudSeen && dt > 0) {
            double m = Math.hypot(p.getX() - hudX, p.getY() - hudY) / dt;
            if (m < 200) measuredSpeed += (m - measuredSpeed) * Math.min(1, dt / 0.15); // skip teleports
        }
        hudX = p.getX();
        hudY = p.getY();
        hudSeen = true;
    }

    /** Ours while we drive, else measured. */
    static double hudSpeed() {
        return Mover.owned ? Math.hypot(Mover.velX(), Mover.velY()) : measuredSpeed;
    }

    private static String nearestRoof(IsoPlayer p) {
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (cell == null) return "";
        BaseVehicle best = null;
        double bestD = 4;
        for (BaseVehicle v : Rides.nearby(cell, p.getX(), p.getY())) {
            double d = Math.hypot(v.getX() - p.getX(), v.getY() - p.getY());
            if (d < bestD) { bestD = d; best = v; }
        }
        if (best == null) return "";
        float roof = Rides.roofZ(best);
        return String.format(" | nearest car roof %.2f lv (%s)", roof, best.getScript() != null ? best.getScript().getName() : "?");
    }

    static String status() {
        IsoPlayer p = Mover.self;
        if (p == null) return "Source movement: no player";
        double s = Mover.vel.speed();
        return String.format(
                "Source movement %s%s | owned=%s state=%s grounded=%s%s%s\n"
                        + "speed %.2f tiles/s (~%.0f u/s) | z=%.3f vz=%.2f\n"
                        + "%s\n"
                        + "learned max  sneak %.2f  walk %.2f  run %.2f  sprint %.2f\n"
                        + "floor: %s%s%s\n"
                        + "ahead: %s",
                Cfg.enabled ? "ON" : "OFF", !GameClient.client ? "" : !Net.serverReady() ? " (waiting for server)"
                        : Net.serverJava ? " (multiplayer)" : " (multiplayer, server has no Java side)",
                Mover.owned, Mover.lastState, Mover.grounded,
                Mover.blockedBy == null ? "" : " | off: " + Mover.blockedBy,
                Mover.jumpBlockedBy == null ? "" : " | last jump refused: " + Mover.jumpBlockedBy,
                s, s / 0.01905, p.getZ(), -p.getLastFallSpeed(),
                Mover.keyState(),
                Mover.maxSpeed[Mover.SNEAK], Mover.maxSpeed[Mover.WALK], Mover.maxSpeed[Mover.RUN], Mover.maxSpeed[Mover.SPRINT],
                Floors.floorName(p),
                nearestRoof(p),
                Zombies.pulledAt != 0 && System.nanoTime() - Zombies.pulledAt < 1_500_000_000L ? " | GRABBED" : "",
                aheadSquare(p));
    }

    /** The square in front of you, to identify props. */
    private static String aheadSquare(IsoPlayer p) {
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (cell == null) return "-";
        float fx = p.getForwardDirectionX(), fy = p.getForwardDirectionY();
        IsoGridSquare sq = cell.getGridSquare((int) Math.floor(p.getX() + fx * 0.8f),
                (int) Math.floor(p.getY() + fy * 0.8f), (int) Math.floor(p.getZ()));
        return Props.describe(sq);
    }
}

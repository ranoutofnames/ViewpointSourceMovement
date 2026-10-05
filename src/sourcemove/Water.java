package sourcemove;

import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.SpriteDetails.IsoFlagType;

/** Open water. Jump over it and onto props in it; landing in it puts you back on shore. */
final class Water {
    private Water() {}

    /** Search radius for dry land (tiles). */
    private static final int SEARCH = 8;
    /** Inset from the land square's edge (tiles). */
    private static final double INSET = 0.3;

    /** Feet this far above water are out of it (levels). */
    static final double SURFACE = 0.05;

    /** Water with no floor over it. */
    static boolean open(IsoGridSquare sq) {
        return sq != null && sq.has(IsoFlagType.water) && !sq.hasFloorOverWater();
    }

    /** Standable means floor, no water, nothing solid. */
    static boolean land(IsoGridSquare sq) {
        return sq != null && sq.TreatAsSolidFloor() && !sq.has(IsoFlagType.water) && !sq.isSolid() && !sq.isSolidTrans();
    }

    /** Closest dry-land point within SEARCH, or null. */
    static double[] nearestLand(IsoCell cell, double x, double y, int z) {
        int cx = (int) Math.floor(x), cy = (int) Math.floor(y);
        double best = Double.POSITIVE_INFINITY;
        double[] out = null;
        for (int gx = cx - SEARCH; gx <= cx + SEARCH; gx++) {
            for (int gy = cy - SEARCH; gy <= cy + SEARCH; gy++) {
                if (!land(cell.getGridSquare(gx, gy, z))) continue;
                double px = Math.max(gx + INSET, Math.min(gx + 1 - INSET, x));
                double py = Math.max(gy + INSET, Math.min(gy + 1 - INSET, y));
                double d = (px - x) * (px - x) + (py - y) * (py - y);
                if (d < best) {
                    best = d;
                    out = new double[]{px, py};
                }
            }
        }
        return out;
    }
}

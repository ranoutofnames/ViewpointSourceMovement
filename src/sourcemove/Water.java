package sourcemove;

import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.SpriteDetails.IsoFlagType;

/**
 * Open water (ponds, puddle pools, rivers): the engine makes water tiles solidtrans, so they block like a wall.
 * With this mod you can jump over them and onto props standing in them, but not land in the water itself:
 * that puts you back on the nearest dry land. Docks and bridges over water have a real floor, and
 * IsoGridSquare clears solidtrans for those. The engine's own square-to-square rule
 * (IsoGridSquare.CalculateCollide) blocks any move where either square is open water, in or out.
 */
final class Water {
    private Water() {}

    /** How far (tiles) to look for dry land around a water landing. */
    private static final int SEARCH = 8;
    /** Keep the rescue spot this far inside the land square's edges (tiles). */
    private static final double INSET = 0.3;

    /** Feet this far above the water's level count as out of the water (levels). */
    static final double SURFACE = 0.05;

    /** A water tile with nothing walkable over it (it may still hold a prop: a fountain, a fixture). */
    static boolean open(IsoGridSquare sq) {
        return sq != null && sq.has(IsoFlagType.water) && !sq.hasFloorOverWater();
    }

    /** Somewhere you can stand: a floor, no water, nothing solid (vanilla's canPlaceCorpseOnSquare test). */
    static boolean land(IsoGridSquare sq) {
        return sq != null && sq.TreatAsSolidFloor() && !sq.has(IsoFlagType.water) && !sq.isSolid() && !sq.isSolidTrans();
    }

    /**
     * The closest standable point to (x,y) at level z on dry land within {@value #SEARCH} tiles, as {x, y},
     * or null if there is none.
     */
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

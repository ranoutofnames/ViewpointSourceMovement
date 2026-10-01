package sourcemove;

import zombie.core.properties.PropertyContainer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.sprite.IsoSprite;
import zombie.util.list.PZArrayList;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.objects.GridSquareEdgeFacingDirection;

/**
 * Heights of what sits on grid edges, so the player can stand on fence tops and pass over edges whose
 * top is below their feet. PZ fences and walls are edge objects: a square's N/W flags describe its north
 * and west edges. Heights are in Z-levels (1 level = 2.449 m) above the edge's level.
 */
final class Ledges {
    private Ledges() {}

    /** Low hoppable fence (picket, low wooden, low metal): ~1 m. */
    static final double LOW_FENCE = 0.40;
    /** Tall hoppable fence (tall wooden/chain-link that vanilla climbs over): ~2.1 m. */
    static final double TALL_FENCE = 0.85;
    /** Walls, windows, doors: the whole level. */
    static final double FULL = 1.0;
    /** How far below a fence top you can arrive and still be stepped up onto it (levels). */
    static final double STEP_UP = 0.12;

    /** A stair hand rail stands this high (levels) above the stairs it follows (~1 m, like a low fence). */
    static final double RAIL = 0.40;

    /**
     * The stairs that the railing on this edge follows, or null. Stair rails (fixtures_railings_01, sloped
     * pieces) sit on an edge running alongside stairs: a west edge beside north-running stairs (the stairs
     * square itself or the one west of it), a north edge beside west-running stairs (itself or the one north).
     */
    static IsoGridSquare railStairs(IsoGridSquare sq, boolean north) {
        if (sq == null || !sq.has(north ? IsoFlagType.collideN : IsoFlagType.collideW) || !hasRailing(sq, north)) return null;
        if (north ? sq.HasStairsWest() : sq.HasStairsNorth()) return sq;
        IsoCell cell = sq.getCell();
        IsoGridSquare across = cell == null ? null
                : north ? cell.getGridSquare(sq.x, sq.y - 1, sq.z) : cell.getGridSquare(sq.x - 1, sq.y, sq.z);
        return across != null && (north ? across.HasStairsWest() : across.HasStairsNorth()) ? across : null;
    }

    private static boolean hasRailing(IsoGridSquare sq, boolean north) {
        PZArrayList<IsoObject> objects = sq.getObjects();
        for (int i = 0; i < objects.size(); i++) {
            IsoSprite s = objects.get(i).getSprite();
            PropertyContainer p = s == null ? null : s.getProperties();
            if (p != null && s.getName() != null && s.getName().startsWith("fixtures_railings")
                    && p.has(north ? IsoFlagType.collideN : IsoFlagType.collideW)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Absolute Z of a stair rail's top at {@code along} (x on a north edge, y on a west edge): the stairs'
     * surface where the rail meets it, plus {@link #RAIL}. The edge is the stairs square's own north/west side,
     * or its south/east side when the rail belongs to the square across.
     */
    static double railTop(IsoGridSquare stairs, IsoGridSquare edge, boolean north, double along) {
        float a = (float) Math.max(0, Math.min(1, along - (north ? stairs.x : stairs.y)));
        float side = stairs == edge ? 0 : 1;
        return (north ? stairs.getApparentZ(a, side) : stairs.getApparentZ(side, a)) + RAIL;
    }

    /** Height of the obstacle on the north (north=true) or west edge of {@code sq}; 0 = open. */
    static double edgeHeight(IsoGridSquare sq, boolean north) {
        if (sq == null) return 0;
        // A stair rail slopes with its stairs: its height at the middle of the edge.
        IsoGridSquare stairs = railStairs(sq, north);
        if (stairs != null) return railTop(stairs, sq, north, (north ? sq.x : sq.y) + 0.5) - sq.z;
        if (sq.has(north ? IsoFlagType.WindowN : IsoFlagType.WindowW)
                || sq.has(north ? IsoFlagType.windowN : IsoFlagType.windowW)
                || sq.has(north ? IsoFlagType.doorN : IsoFlagType.doorW)
                || sq.has(north ? IsoFlagType.DoorWallN : IsoFlagType.DoorWallW)) {
            return FULL;
        }
        // Tall fences and low walls (walls_exterior_house_low: Hoppable + Wall) are wall-type objects,
        // so test the hoppable flags before plain walls.
        if (sq.has(north ? IsoFlagType.TallHoppableN : IsoFlagType.TallHoppableW)) return TALL_FENCE;
        // The engine's own vault check: sprite Hoppable flags, plus player-built fences (IsoThumpable.isHoppable)
        // whose sprites may not carry the flag.
        if (sq.has(north ? IsoFlagType.HoppableN : IsoFlagType.HoppableW)
                || sq.getWallHoppable(north ? GridSquareEdgeFacingDirection.NORTH_SOUTH : GridSquareEdgeFacingDirection.EAST_WEST) != null) {
            return LOW_FENCE;
        }
        if (sq.has(north ? IsoFlagType.WallN : IsoFlagType.WallW)) return FULL;
        if (sq.has(north ? IsoFlagType.collideN : IsoFlagType.collideW)) return FULL;
        return 0;
    }

    /** Only fence tops are surfaces you can stand on (stair rails, which slope, are {@link #fenceTopUnder}'s). */
    static double standableHeight(IsoGridSquare sq, boolean north) {
        if (railStairs(sq, north) != null) return 0;
        double h = edgeHeight(sq, north);
        return h == LOW_FENCE || h == TALL_FENCE ? h : 0;
    }

    /** Height of the edge between two orthogonally adjacent squares at level z. */
    private static double stepHeight(IsoCell cell, int z, int ax, int ay, int bx, int by) {
        if (by == ay - 1) return edgeHeight(cell.getGridSquare(ax, ay, z), true);
        if (by == ay + 1) return edgeHeight(cell.getGridSquare(bx, by, z), true);
        if (bx == ax - 1) return edgeHeight(cell.getGridSquare(ax, ay, z), false);
        if (bx == ax + 1) return edgeHeight(cell.getGridSquare(bx, by, z), false);
        return 0;
    }

    /** Tallest thing you must clear moving from (sx,sy) to the adjacent (tx,ty) at level z. */
    static double crossingHeight(IsoCell cell, int z, int sx, int sy, int tx, int ty) {
        int dx = tx - sx, dy = ty - sy;
        if (Math.abs(dx) > 1 || Math.abs(dy) > 1) return FULL;
        if (dx == 0 && dy == 0) return 0;
        if (dx != 0 && dy != 0) {
            // Diagonal: either L-shaped path around the corner will do; take the easier one.
            double viaX = Math.max(stepHeight(cell, z, sx, sy, tx, sy), stepHeight(cell, z, tx, sy, tx, ty));
            double viaY = Math.max(stepHeight(cell, z, sx, sy, sx, ty), stepHeight(cell, z, sx, ty, tx, ty));
            return Math.min(viaX, viaY);
        }
        return stepHeight(cell, z, sx, sy, tx, ty);
    }

    /** The stairs followed by the rail {@link #fenceTopUnder} last stood you on, or null if it was a plain fence. */
    static IsoGridSquare lastRailStairs;

    /**
     * Absolute Z of the highest fence top within {@code r} tiles of (x,y) at level z, or -1. Stair rails count
     * at their sloped height, and also from the level below (a rail on the top stair rises past the next level).
     */
    static double fenceTopUnder(IsoCell cell, double x, double y, int z, double r) {
        double best = -1;
        lastRailStairs = null;
        for (int level = z; level >= z - 1; level--) {
            boolean railsOnly = level < z;
            // North edges lie on horizontal lines y = integer; square (cx, yl) owns the segment [cx, cx+1].
            int yl = (int) Math.round(y);
            if (Math.abs(y - yl) <= r) {
                for (int cx = (int) Math.floor(x - r); cx <= (int) Math.floor(x + r); cx++) {
                    if (Math.max(0, Math.max(cx - x, x - (cx + 1))) > r) continue;
                    best = edgeTop(cell.getGridSquare(cx, yl, level), true, x, level, railsOnly, best);
                }
            }
            // West edges lie on vertical lines x = integer; square (xl, cy) owns the segment [cy, cy+1].
            int xl = (int) Math.round(x);
            if (Math.abs(x - xl) <= r) {
                for (int cy = (int) Math.floor(y - r); cy <= (int) Math.floor(y + r); cy++) {
                    if (Math.max(0, Math.max(cy - y, y - (cy + 1))) > r) continue;
                    best = edgeTop(cell.getGridSquare(xl, cy, level), false, y, level, railsOnly, best);
                }
            }
        }
        return best;
    }

    /** Max of {@code best} and the standable top of this edge (a rail's at {@code along}). */
    private static double edgeTop(IsoGridSquare sq, boolean north, double along, int level, boolean railsOnly, double best) {
        if (sq == null) return best;
        IsoGridSquare stairs = railStairs(sq, north);
        if (stairs != null) {
            double top = railTop(stairs, sq, north, along);
            if (top > best) {
                lastRailStairs = stairs;
                return top;
            }
            return best;
        }
        if (railsOnly) return best;
        double h = standableHeight(sq, north);
        if (h > 0 && level + h > best) {
            lastRailStairs = null;
            return level + h;
        }
        return best;
    }
}

package sourcemove;

import java.util.IdentityHashMap;
import java.util.Map;

import zombie.core.properties.PropertyContainer;
import zombie.core.textures.Texture;
import zombie.iso.IsoDirections;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.sprite.IsoSprite;
import zombie.util.list.PZArrayList;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.objects.GridSquareEdgeFacingDirection;

/** Heights of what stands on grid edges (fences, walls, rails), in levels above the edge. */
final class Ledges {
    private Ledges() {}

    /** Picket, low wood and metal fences (~1 m). */
    static final double LOW_FENCE = 0.40;
    /** Tall fences vanilla climbs over (~2.1 m). */
    static final double TALL_FENCE = 0.85;
    /** Barbed and razor wire fences, a bit over a tall fence and never footing. */
    static final double BARBED = 0.90;
    /** Walls, windows, doors. */
    static final double FULL = 1.0;
    /** How far below a top you can be and still step onto it (levels). */
    static final double STEP_UP = 0.12;

    /** Square owning the edge between two adjacent squares (north edge if ax == bx). */
    static IsoGridSquare edgeOwner(IsoCell cell, int z, int ax, int ay, int bx, int by) {
        if (by == ay - 1) return cell.getGridSquare(ax, ay, z);
        if (by == ay + 1) return cell.getGridSquare(bx, by, z);
        if (bx == ax - 1) return cell.getGridSquare(ax, ay, z);
        if (bx == ax + 1) return cell.getGridSquare(bx, by, z);
        return null;
    }

    /** Height on a north or west edge, rails mid-edge; 0 = open. */
    static double edgeHeight(IsoGridSquare sq, boolean north) {
        return edgeHeight(sq, north, Double.NaN);
    }

    /** As above, rails at along; NaN = mid-edge. */
    static double edgeHeight(IsoGridSquare sq, boolean north, double along) {
        if (sq == null) return 0;
        int kinds = Rails.kinds(sq);
        boolean escalator = Rails.escalator(sq, north, kinds);
        IsoGridSquare stairs = Rails.stairs(sq, north, kinds, escalator);
        if (stairs != null) {
            if (Double.isNaN(along)) along = (north ? sq.x : sq.y) + 0.5;
            return Rails.top(stairs, sq, north, along) - sq.z;
        }
        return flatHeight(sq, north, kinds, escalator);
    }

    /** Height on an edge that isn't a stair rail. */
    private static double flatHeight(IsoGridSquare sq, boolean north, int kinds, boolean escalator) {
        // Escalator panel past its stairs is a rail on the floor.
        if (escalator) return Rails.RAIL;
        // Shifted escalator panel's flag, it stands on the next edge.
        if (Rails.phantom(kinds, north)) return 0;
        if (sq.has(north ? IsoFlagType.WindowN : IsoFlagType.WindowW)
                || sq.has(north ? IsoFlagType.windowN : IsoFlagType.windowW)
                || sq.has(north ? IsoFlagType.doorN : IsoFlagType.doorW)
                || sq.has(north ? IsoFlagType.DoorWallN : IsoFlagType.DoorWallW)) {
            return FULL;
        }
        // Wire wins over sandbags or anything else on the same edge.
        if ((sq.has(north ? IsoFlagType.collideN : IsoFlagType.collideW) || sq.has(north ? IsoFlagType.WallN : IsoFlagType.WallW))
                && barbed(sq, north)) {
            return BARBED;
        }
        // Tall fences and low walls are wall-type too, so hoppable flags first.
        if (sq.has(north ? IsoFlagType.TallHoppableN : IsoFlagType.TallHoppableW)) return TALL_FENCE;
        // Player-built fences may lack the flag.
        if (sq.has(north ? IsoFlagType.HoppableN : IsoFlagType.HoppableW)
                || sq.getWallHoppable(north ? GridSquareEdgeFacingDirection.NORTH_SOUTH : GridSquareEdgeFacingDirection.EAST_WEST) != null) {
            return LOW_FENCE;
        }
        if (sq.has(north ? IsoFlagType.WallN : IsoFlagType.WallW) || sq.has(north ? IsoFlagType.collideN : IsoFlagType.collideW)) {
            return wallHeight(sq, north);
        }
        return 0;
    }

    /** Wall height from its sprites. Roof parapets are low, walls_special adds nothing. */
    private static double wallHeight(IsoGridSquare sq, boolean north) {
        PZArrayList<IsoObject> objects = sq.getObjects();
        int side = north ? 0 : 1;
        double best = -1;
        for (int i = 0; i < objects.size(); i++) {
            IsoSprite s = objects.get(i).getSprite();
            if (s != null) best = Math.max(best, wallHeights(s)[side]);
        }
        // No visible wall means full height.
        return best < MIN_WALL ? FULL : best;
    }

    /** Below this a wall-flagged edge is a full wall (levels). */
    private static final double MIN_WALL = 0.05;
    /** One storey in a 1x wall image (px). */
    private static final float WALL_PX = 96f;
    private static final double[] NOT_A_WALL = {-1, -1, 0};
    private static final Map<IsoSprite, double[]> wallCache = new IdentityHashMap<>();

    /** Sprite's wall height on its {north, west} edge, -1 if none, then 1 if it's barbed wire. */
    private static double[] wallHeights(IsoSprite s) {
        double[] cached = wallCache.get(s);
        if (cached != null) return cached;
        PropertyContainer p = s.getProperties();
        double[] h = NOT_A_WALL;
        if (p != null && s.getName() != null && !s.getName().startsWith("walls_special")) {
            boolean n = p.has(IsoFlagType.WallN) || p.has(IsoFlagType.WallNW) || p.has(IsoFlagType.collideN);
            boolean w = p.has(IsoFlagType.WallW) || p.has(IsoFlagType.WallNW) || p.has(IsoFlagType.collideW);
            if (n || w) {
                // Damaged military razor fence lacks the flag.
                boolean barbed = p.has(IsoFlagType.CantClimb) || s.getName().startsWith("fencing_damaged_06_");
                double height = barbed ? BARBED : spriteWallHeight(s);
                h = new double[] {n ? height : -1, w ? height : -1, barbed ? 1 : 0};
            }
        }
        wallCache.put(s, h);
        return h;
    }

    /** Height from the image top; bottom-up made stair rails walkable. */
    private static double spriteWallHeight(IsoSprite s) {
        try {
            Texture tex = s.texture;
            if (tex == null) tex = s.getTextureForCurrentFrame(IsoDirections.N);
            if (tex == null || tex.getHeightOrig() <= 0) return FULL;
            float scale = tex.getHeightOrig() / 128f;
            float top = tex.getOffsetY() / scale;
            double h = Math.max(0, Math.min(FULL, (WALL_PX - top) / WALL_PX));
            // Cornices and trim shouldn't make a wall jumpable.
            return h > 0.85 ? FULL : h;
        } catch (Throwable t) {
            return FULL;
        }
    }

    /** Fence tops and low walls, not full walls or barbed wire. */
    private static double standable(IsoGridSquare sq, boolean north, double h) {
        return h > 0 && h < FULL && !barbed(sq, north) ? h : 0;
    }

    private static boolean barbed(IsoGridSquare sq, boolean north) {
        PZArrayList<IsoObject> objects = sq.getObjects();
        for (int i = 0; i < objects.size(); i++) {
            IsoSprite s = objects.get(i).getSprite();
            if (s == null) continue;
            double[] h = wallHeights(s);
            if (h[2] > 0 && h[north ? 0 : 1] > 0) return true;
        }
        return false;
    }

    /** Highest barbed wire top within r of (x, y) on this level, or -1. */
    static double barbedTopUnder(IsoCell cell, double x, double y, int z, double r) {
        double best = -1;
        int yl = (int) Math.round(y);
        if (Math.abs(y - yl) <= r) {
            for (int cx = (int) Math.floor(x - r); cx <= (int) Math.floor(x + r); cx++) {
                if (Math.max(0, Math.max(cx - x, x - (cx + 1))) > r) continue;
                IsoGridSquare sq = cell.getGridSquare(cx, yl, z);
                if (sq != null && barbed(sq, true)) best = z + BARBED;
            }
        }
        int xl = (int) Math.round(x);
        if (Math.abs(x - xl) <= r) {
            for (int cy = (int) Math.floor(y - r); cy <= (int) Math.floor(y + r); cy++) {
                if (Math.max(0, Math.max(cy - y, y - (cy + 1))) > r) continue;
                IsoGridSquare sq = cell.getGridSquare(xl, cy, z);
                if (sq != null && barbed(sq, false)) best = z + BARBED;
            }
        }
        return best;
    }

    /** Tallest thing between two adjacent squares, rails mid-edge. */
    static double crossingHeight(IsoCell cell, int z, int sx, int sy, int tx, int ty) {
        return crossingHeight(cell, z, sx, sy, tx, ty, Double.NaN, Double.NaN);
    }

    /** As above, rails at (px, py); NaN = mid-edge. */
    static double crossingHeight(IsoCell cell, int z, int sx, int sy, int tx, int ty, double px, double py) {
        int dx = tx - sx, dy = ty - sy;
        if (Math.abs(dx) > 1 || Math.abs(dy) > 1) return FULL;
        if (dx == 0 && dy == 0) return 0;
        if (dx != 0 && dy != 0) {
            // Diagonal takes the easier way round the corner.
            double viaX = Math.max(stepHeight(cell, z, sx, sy, tx, sy, px, py), stepHeight(cell, z, tx, sy, tx, ty, px, py));
            double viaY = Math.max(stepHeight(cell, z, sx, sy, sx, ty, px, py), stepHeight(cell, z, sx, ty, tx, ty, px, py));
            return Math.min(viaX, viaY);
        }
        return stepHeight(cell, z, sx, sy, tx, ty, px, py);
    }

    private static double stepHeight(IsoCell cell, int z, int ax, int ay, int bx, int by, double px, double py) {
        boolean north = ax == bx;
        return edgeHeight(edgeOwner(cell, z, ax, ay, bx, by), north, north ? px : py);
    }

    /** Stairs of the rail fenceTopUnder last found, null for a fence. */
    static IsoGridSquare lastRailStairs;

    /** Highest fence top within r of (x, y), or -1; rails from the level below too. */
    static double fenceTopUnder(IsoCell cell, double x, double y, int z, double r) {
        return fenceTopUnder(cell, x, y, z, r, Double.POSITIVE_INFINITY);
    }

    /** As above, ignoring tops above maxTop, so the next rail tile up doesn't win. */
    static double fenceTopUnder(IsoCell cell, double x, double y, int z, double r, double maxTop) {
        double best = -1;
        lastRailStairs = null;
        // Sandbags and the like are deeper than the footing around their line.
        for (int gx = (int) Math.floor(x - SLAB_FOOTING); gx <= (int) Math.floor(x + SLAB_FOOTING); gx++) {
            for (int gy = (int) Math.floor(y - SLAB_FOOTING); gy <= (int) Math.floor(y + SLAB_FOOTING); gy++) {
                IsoGridSquare sq = cell.getGridSquare(gx, gy, z);
                if (sq == null) continue;
                for (int side = 0; side < 2; side++) {
                    if (!onSlab(sq, side == 0, x - gx, y - gy)) continue;
                    double h = standable(sq, side == 0, flatHeight(sq, side == 0, Rails.kinds(sq), false));
                    if (h > 0 && z + h > best && z + h <= maxTop) best = z + h;
                }
            }
        }
        for (int level = z; level >= z - 1; level--) {
            boolean railsOnly = level < z;
            // North edges, square (cx, yl) owns [cx, cx+1].
            int yl = (int) Math.round(y);
            if (Math.abs(y - yl) <= r) {
                for (int cx = (int) Math.floor(x - r); cx <= (int) Math.floor(x + r); cx++) {
                    if (Math.max(0, Math.max(cx - x, x - (cx + 1))) > r) continue;
                    best = edgeTop(cell.getGridSquare(cx, yl, level), true, x, level, railsOnly, best, maxTop);
                }
            }
            // West edges, square (xl, cy) owns [cy, cy+1].
            int xl = (int) Math.round(x);
            if (Math.abs(x - xl) <= r) {
                for (int cy = (int) Math.floor(y - r); cy <= (int) Math.floor(y + r); cy++) {
                    if (Math.max(0, Math.max(cy - y, y - (cy + 1))) > r) continue;
                    best = edgeTop(cell.getGridSquare(xl, cy, level), false, y, level, railsOnly, best, maxTop);
                }
            }
        }
        return best;
    }

    /** Footing past a slab's sides (tiles). */
    private static final double SLAB_FOOTING = 0.15;
    /** Shallower than this an edge fence is just its line. */
    private static final double MIN_SLAB = 0.2;
    private static final double[] NO_SLAB = {};
    private static final Map<IsoSprite, double[]> slabCache = new IdentityHashMap<>();

    /** Square-local (lx, ly) over the body of a fence on this edge. */
    private static boolean onSlab(IsoGridSquare sq, boolean north, double lx, double ly) {
        if (!sq.has(north ? IsoFlagType.HoppableN : IsoFlagType.HoppableW)) return false;
        PZArrayList<IsoObject> objects = sq.getObjects();
        for (int i = 0; i < objects.size(); i++) {
            IsoSprite s = objects.get(i).getSprite();
            if (s == null) continue;
            double[] r = slab(s);
            if (r.length > 0 && (r[4] > 0) == north && lx >= r[0] - SLAB_FOOTING && lx <= r[2] + SLAB_FOOTING
                    && ly >= r[1] - SLAB_FOOTING && ly <= r[3] + SLAB_FOOTING) return true;
        }
        return false;
    }

    /** Footprint {x0, y0, x1, y1, north} of a one-edge fence's body against its edge, or empty. */
    private static double[] slab(IsoSprite s) {
        double[] cached = slabCache.get(s);
        if (cached != null) return cached;
        double[] r = NO_SLAB;
        PropertyContainer p = s.getProperties();
        boolean north = p != null && p.has(IsoFlagType.HoppableN);
        if (p != null && north != p.has(IsoFlagType.HoppableW)) {
            double x0 = 1, y0 = 1, x1 = 0, y1 = 0;
            for (Props.Shape sh : Props.shapes(s)) {
                if (sh.round || Math.abs(sh.sin) > 0.01f) continue;
                x0 = Math.min(x0, sh.cx - sh.hx + 0.5);
                x1 = Math.max(x1, sh.cx + sh.hx + 0.5);
                y0 = Math.min(y0, sh.cy - sh.hy + 0.5);
                y1 = Math.max(y1, sh.cy + sh.hy + 0.5);
            }
            double near = north ? y0 : x0, depth = north ? y1 - y0 : x1 - x0;
            if (near < 0.1 && depth >= MIN_SLAB) r = new double[] {x0, y0, x1, y1, north ? 1 : 0};
        }
        slabCache.put(s, r);
        return r;
    }

    private static double edgeTop(IsoGridSquare sq, boolean north, double along, int level, boolean railsOnly, double best,
            double maxTop) {
        if (sq == null) return best;
        int kinds = Rails.kinds(sq);
        boolean escalator = Rails.escalator(sq, north, kinds);
        IsoGridSquare stairs = Rails.stairs(sq, north, kinds, escalator);
        if (stairs != null) {
            double top = Rails.top(stairs, sq, north, along);
            if (top > best && top <= maxTop) {
                lastRailStairs = stairs;
                return top;
            }
            return best;
        }
        if (railsOnly) return best;
        double h = standable(sq, north, flatHeight(sq, north, kinds, escalator));
        if (h > 0 && level + h > best && level + h <= maxTop) {
            lastRailStairs = null;
            return level + h;
        }
        return best;
    }
}

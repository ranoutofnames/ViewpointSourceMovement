package sourcemove;

import java.util.IdentityHashMap;
import java.util.Map;

import zombie.core.properties.PropertyContainer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.SpriteDetails.IsoObjectType;
import zombie.iso.sprite.IsoSprite;
import zombie.util.list.PZArrayList;

/** Stair rails and escalator side panels, edge obstacles that slope with their stairs. */
final class Rails {
    private Rails() {}

    /** Rail height above its stairs (levels). */
    static final double RAIL = 0.40;

    /** Per-sprite edge bits for rail, escalator panel on the edge or shifted one square, any other wall. */
    private static final int RAIL_N = 1, RAIL_W = 2, PANEL_ON_N = 4, PANEL_ON_W = 8, PANEL_SHIFTED_N = 16, PANEL_SHIFTED_W = 32,
            OTHER_N = 64, OTHER_W = 128;
    private static final Map<IsoSprite, Integer> kindCache = new IdentityHashMap<>();

    /** Escalator panels 16-21 / 32-37 sit on the N / W edge; 24-29 / 40-45 are drawn one square on. */
    private static int spriteKinds(IsoSprite s) {
        if (s == null) return 0;
        Integer cached = kindCache.get(s);
        if (cached != null) return cached;
        int k = 0;
        PropertyContainer p = s.getProperties();
        String name = s.getName();
        if (name != null && name.startsWith("fixtures_escalators_01_")) {
            int i = s.tileSheetIndex;
            if (i >= 16 && i <= 21) k |= PANEL_ON_N;
            if (i >= 24 && i <= 29) k |= PANEL_SHIFTED_N;
            if (i >= 32 && i <= 37) k |= PANEL_ON_W;
            if (i >= 40 && i <= 45) k |= PANEL_SHIFTED_W;
        }
        if (p != null) {
            if (name != null && name.startsWith("fixtures_railings")) {
                if (p.has(IsoFlagType.collideN)) k |= RAIL_N;
                if (p.has(IsoFlagType.collideW)) k |= RAIL_W;
            }
            boolean n = p.has(IsoFlagType.WallN) || p.has(IsoFlagType.WallNW) || p.has(IsoFlagType.collideN) || p.has(IsoFlagType.WallNTrans);
            boolean w = p.has(IsoFlagType.WallW) || p.has(IsoFlagType.WallNW) || p.has(IsoFlagType.collideW) || p.has(IsoFlagType.WallWTrans);
            if (n && (k & (PANEL_ON_N | PANEL_SHIFTED_N)) == 0) k |= OTHER_N;
            if (w && (k & (PANEL_ON_W | PANEL_SHIFTED_W)) == 0) k |= OTHER_W;
        }
        kindCache.put(s, k);
        return k;
    }

    static int kinds(IsoGridSquare sq) {
        if (sq == null) return 0;
        PZArrayList<IsoObject> objects = sq.getObjects();
        int k = 0;
        for (int i = 0; i < objects.size(); i++) k |= spriteKinds(objects.get(i).getSprite());
        return k;
    }

    /** Escalator panel on this edge, from this square, the one before, or a level up. */
    static boolean escalator(IsoGridSquare sq, boolean north, int kinds) {
        int on = north ? PANEL_ON_N : PANEL_ON_W, shifted = north ? PANEL_SHIFTED_N : PANEL_SHIFTED_W;
        if ((kinds & on) != 0) return true;
        IsoCell cell = sq.getCell();
        if (cell == null) return false;
        int bx = north ? sq.x : sq.x - 1, by = north ? sq.y - 1 : sq.y;
        return (kinds(cell.getGridSquare(bx, by, sq.z)) & shifted) != 0
                || (kinds(cell.getGridSquare(sq.x, sq.y, sq.z + 1)) & on) != 0
                || (kinds(cell.getGridSquare(bx, by, sq.z + 1)) & shifted) != 0;
    }

    /** Stairs the rail on this edge follows, or null. */
    static IsoGridSquare stairs(IsoGridSquare sq, boolean north) {
        if (sq == null) return null;
        int kinds = kinds(sq);
        return stairs(sq, north, kinds, escalator(sq, north, kinds));
    }

    /** Rails run beside stairs, on this square or the one across, or a level up over the top stair. */
    static IsoGridSquare stairs(IsoGridSquare sq, boolean north, int kinds, boolean escalator) {
        if (!escalator && ((kinds & (north ? RAIL_N : RAIL_W)) == 0 || !sq.has(north ? IsoFlagType.collideN : IsoFlagType.collideW))) {
            return null;
        }
        IsoCell cell = sq.getCell();
        if (cell == null) return null;
        IsoGridSquare across = north ? cell.getGridSquare(sq.x, sq.y - 1, sq.z) : cell.getGridSquare(sq.x - 1, sq.y, sq.z);
        if (stairsAlong(sq, north)) return sq;
        if (stairsAlong(across, north)) return across;
        // Top-stair rails are often placed on the floor above; plain ones only follow the top stair.
        IsoGridSquare below = cell.getGridSquare(sq.x, sq.y, sq.z - 1);
        if (stairsAlong(below, north) && (escalator || topStair(below))) return below;
        IsoGridSquare acrossBelow = north ? cell.getGridSquare(sq.x, sq.y - 1, sq.z - 1) : cell.getGridSquare(sq.x - 1, sq.y, sq.z - 1);
        if (stairsAlong(acrossBelow, north) && (escalator || topStair(acrossBelow))) return acrossBelow;
        return null;
    }

    private static boolean topStair(IsoGridSquare sq) {
        return sq.has(IsoObjectType.stairsTN) || sq.has(IsoObjectType.stairsTW);
    }

    private static boolean stairsAlong(IsoGridSquare sq, boolean north) {
        return sq != null && (north ? sq.HasStairsWest() : sq.HasStairsNorth());
    }

    /** Absolute Z of the rail top at along, the stairs' surface there plus RAIL. */
    static double top(IsoGridSquare stairs, IsoGridSquare edge, boolean north, double along) {
        float a = (float) Math.max(0, Math.min(1, along - (north ? stairs.x : stairs.y)));
        float side = (north ? stairs.y < edge.y : stairs.x < edge.x) ? 1 : 0;
        return (north ? stairs.getApparentZ(a, side) : stairs.getApparentZ(side, a)) + RAIL;
    }

    /** The edge's flag is only a shifted panel's, nothing stands here. */
    static boolean phantom(int kinds, boolean north) {
        return (kinds & (north ? PANEL_SHIFTED_N : PANEL_SHIFTED_W)) != 0 && (kinds & (north ? OTHER_N : OTHER_W)) == 0;
    }

    static boolean phantomCrossing(IsoCell cell, int z, int ax, int ay, int bx, int by) {
        if (ax != bx && ay != by) return false;
        IsoGridSquare sq = Ledges.edgeOwner(cell, z, ax, ay, bx, by);
        if (sq == null) return false;
        boolean north = ax == bx;
        int kinds = kinds(sq);
        return phantom(kinds, north) && !escalator(sq, north, kinds);
    }

    /** Height of escalator panels the game has no collision for; diagonals take the easier way. */
    static double unflaggedCrossing(IsoCell cell, int z, int ax, int ay, int bx, int by, double px, double py) {
        if (ax != bx && ay != by) {
            double viaX = Math.max(unflaggedStep(cell, z, ax, ay, bx, ay, px, py), unflaggedStep(cell, z, bx, ay, bx, by, px, py));
            double viaY = Math.max(unflaggedStep(cell, z, ax, ay, ax, by, px, py), unflaggedStep(cell, z, ax, by, bx, by, px, py));
            return Math.min(viaX, viaY);
        }
        return unflaggedStep(cell, z, ax, ay, bx, by, px, py);
    }

    private static double unflaggedStep(IsoCell cell, int z, int ax, int ay, int bx, int by, double px, double py) {
        boolean north = ax == bx;
        IsoGridSquare sq = Ledges.edgeOwner(cell, z, ax, ay, bx, by);
        if (sq == null || sq.has(north ? IsoFlagType.collideN : IsoFlagType.collideW) || sq.has(north ? IsoFlagType.WallN : IsoFlagType.WallW)) {
            return 0;
        }
        int kinds = kinds(sq);
        if (!escalator(sq, north, kinds)) return 0;
        IsoGridSquare stairs = stairs(sq, north, kinds, true);
        // Past the stairs, a rail on the floor.
        return stairs != null ? top(stairs, sq, north, north ? px : py) - sq.z : RAIL;
    }
}

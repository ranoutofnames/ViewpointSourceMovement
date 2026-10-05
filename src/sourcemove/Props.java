package sourcemove;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

import zombie.core.properties.PropertyContainer;
import zombie.core.textures.Texture;
import zombie.iso.IsoDirections;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteManager;
import zombie.tileDepth.TileDepthTextureAssignmentManager;
import zombie.tileDepth.TileGeometryFile;
import zombie.tileDepth.TileGeometryManager;
import zombie.util.list.PZArrayList;

/** Props you can jump onto and over, sized from the game's tile geometry or else the sprite's pixel height. */
final class Props {
    private Props() {}

    static final int MODE_OFF = 0, MODE_OUTDOOR = 1, MODE_ALL = 2;
    static final int MAT_CONCRETE = 2, MAT_WOOD = 7, MAT_CARPET = 8, MAT_METAL = 12;

    private static final double LEVEL_METERS = 2.44949;
    /** Taller than this (levels) is a wall. */
    private static final float MAX_TOP = 0.9f;
    /** Lower than this is feet and base plates. */
    private static final float MIN_FLOOR = 0.08f;
    /** Walk-through props must clear the step-up, or walking through them would lift you. */
    private static final float MIN_WALKTHROUGH = 0.15f;
    /** Walk-through clutter smaller than a garbage can stays walk-through. */
    private static final float WALKTHROUGH_MIN_TOP = 0.33f, WALKTHROUGH_MIN_AREA = 0.3f;
    /** A walk-through shape this low (levels) stands on the ground. */
    private static final float GROUNDED = 0.05f;
    private static final float PX_PER_LEVEL = 96f, FOOTPRINT_PX = 24f;
    private static final float TIRE_WALL = 0.35f, TIRE_WALL_LOW = 0.2f;
    /** Two-tile tents, ridge height (levels) and where the slope starts (tiles). */
    private static final float BIG_TENT_TOP = 0.47f, BIG_TENT_INSET = 0.1f;
    /** IsLow objects with no shape, about counter height. */
    private static final float LOW_PROP = 0.35f;

    /** A box or upright cylinder; top in levels. */
    static final class Shape {
        float cx, cy, hx, hy, cos = 1, sin, r, top, bottom;
        boolean round;

        /** Signs, rails and posts get fence-style footing. */
        boolean thin() {
            return round ? r < 0.1f : Math.min(hx, hy) < 0.1f;
        }

        float area() {
            return round ? (float) Math.PI * r * r : 4 * hx * hy;
        }

        boolean contains(double x, double y, double margin) {
            double dx = x - cx, dy = y - cy;
            if (round) return dx * dx + dy * dy <= (r + margin) * (r + margin);
            double lx = dx * cos - dy * sin, ly = dx * sin + dy * cos;
            return Math.abs(lx) <= hx + margin && Math.abs(ly) <= hy + margin;
        }
    }

    /** A prop sprite's shapes and footstep material. */
    static final class Prop {
        final Shape[] shapes;
        final float entryTop;
        final int material;
        final String name;
        /** Tent side angle (radians); 0 = no ramp. */
        float rampAngle;
        /** Ridge runs north-south. */
        boolean ridgeAlongY;
        /** Two-tile tent half, the slope rises across the square to the shared edge. */
        boolean halfRamp;
        /** Where the slope starts in from the outer edge (tiles). */
        float rampInset;

        Prop(Shape[] shapes, int material, String name) {
            this.shapes = shapes;
            this.material = material;
            this.name = name;
            // You step onto the largest surface, the seat not the backrest.
            this.entryTop = shapes.length == 0 ? 0 : principal(shapes).top;
        }
    }

    /** In the way and not jumpable. */
    private static final Prop BLOCKER = new Prop(new Shape[0], 0, null);

    static boolean jumpable(Prop p) {
        return p != null && p != BLOCKER;
    }
    private static final Map<IsoSprite, Prop> cache = new IdentityHashMap<>();
    private static int cachedMode = -1;

    private static boolean obstacle(PropertyContainer p) {
        return p.has(IsoFlagType.solid) || p.has(IsoFlagType.solidtrans);
    }

    /** null = not in the way, BLOCKER = in the way, else jumpable. */
    static Prop prop(IsoObject o) {
        IsoSprite s = o.getSprite();
        if (s == null) return null;
        int mode = Cfg.propMode;
        if (mode != cachedMode) {
            cache.clear();
            cachedMode = mode;
        }
        Prop p = cache.get(s);
        if (p == null && !cache.containsKey(s)) {
            try {
                p = build(s, mode);
            } catch (Throwable t) {
                Log.warn("prop geometry failed for " + s.getName() + ": " + t);
                p = BLOCKER;
            }
            cache.put(s, p);
        }
        return p;
    }

    /** Tilesets that are never props. */
    private static final String[] NEVER = {"vegetation_trees", "vegetation_foliage", "f_", "d_plants", "blends_", "radio_tower", "e_"};

    /** The game tags plants "vegitation" (sic). */
    private static boolean bush(PropertyContainer p, String name) {
        return p.has(IsoFlagType.vegitation) || "Bush".equals(name) || "Hedge".equals(name);
    }
    /** Walk-through things that make poor footing. */
    private static final String[] NOT_FLOORS = {"camping_02", "vegetation_", "fixtures_windows_curtains", "floors_", "fixtures_stairs", "damaged_objects"};

    /** Prop height from its geometry, an aliased tile's geometry, fixed values, its pixels, or IsLow; taller than MAX_TOP stays solid. */
    private static Prop build(IsoSprite s, int mode) {
        PropertyContainer p = s.getProperties();
        if (p == null) return null;
        boolean blocks = obstacle(p);
        if (mode == MODE_OFF) return blocks ? BLOCKER : null;

        String name = p.get("CustomName");
        String group = p.get("GroupName");
        String tileset = tileset(s);
        int index = s.tileSheetIndex;
        boolean low = p.has("IsLow");
        if (tileset == null || startsWithAny(tileset, NEVER) || bush(p, name)) return blocks ? BLOCKER : null;

        // Speedway tire walls, only some tagged Rubber and the geometry is a placeholder.
        boolean raceTires = "recreational_sports_01".equals(tileset) && index >= 136 && index <= 149;
        boolean tires = raceTires || "Tires".equals(name) || "Tire".equals(name);
        boolean fountain = "location_community_park_01".equals(tileset) && index >= 40 && index <= 48;
        boolean bench = name != null && name.endsWith("Bench") || "Low Bench".equals(group);
        float override = topOverride(tileset, index);
        // St. Peregrin's Emergency sign, asked for though it's small.
        boolean emergencySign = "location_community_medical_01".equals(tileset)
                && (index >= 84 && index <= 87 || index >= 92 && index <= 95);
        boolean named = tires || fountain || bench || emergencySign || "Bird Bath".equals(name) || override > 0;
        if (!blocks && !named && (startsWithAny(tileset, NOT_FLOORS) || edgeOrAttached(p))) return null;

        String label = fountain ? "Fountain" : override > 0 ? "Sign"
                : name == null ? s.getName() : group == null ? name : group + " " + name;
        int material = material(p, tires, fountain);
        if (raceTires) return new Prop(new Shape[] {square(low ? TIRE_WALL_LOW : TIRE_WALL)}, material, "Tire wall");
        if (blocks && bigTent(tileset, p)) {
            // Ridge on the line between the two tiles, sides about 52 degrees.
            Prop tent = new Prop(new Shape[] {square(BIG_TENT_TOP)}, material, label);
            tent.rampAngle = (float) Math.atan(BIG_TENT_TOP * LEVEL_METERS / (1 - BIG_TENT_INSET));
            tent.halfRamp = true;
            tent.rampInset = BIG_TENT_INSET;
            String facing = p.get("Facing");
            tent.ridgeAlongY = "E".equals(facing) || "W".equals(facing);
            return tent;
        }

        Shape[] shapes = geometry(tileset, index);
        if (shapes.length == 0) shapes = aliasedGeometry(s.getName());
        if (override > 0) {
            if (shapes.length == 0) shapes = new Shape[] {square(override)};
            for (Shape sh : shapes) sh.top = override;
        } else if (shapes.length > 0) {
            // Walk-through only counts ground-standing, garbage-can sized things.
            if (!blocks && !named) {
                Shape main = principal(shapes);
                if (main.bottom > GROUNDED || main.top < WALKTHROUGH_MIN_TOP || main.area() < WALKTHROUGH_MIN_AREA) return null;
            }
        } else {
            if (!blocks && !named) return null; // walk-through with no shape, size unknown
            float fixed = fixedHeight(name);
            // The image misses stacked crates and slanted tops; the item surface catches those.
            if (fixed <= 0 && blocks) fixed = Math.max(pixelHeight(s), surfaceHeight(p));
            if (fixed <= 0 && blocks) fixed = siblingPixelHeight(s, tileset, index, p);
            if (fixed <= 0 && (low || named)) fixed = LOW_PROP;
            if (fixed <= 0) return blocks ? BLOCKER : null;
            shapes = new Shape[] {square(fixed)};
        }
        Prop prop = new Prop(shapes, material, label);
        // Too low to measure (a beach chair's leg rest), a small hop.
        if (blocks && prop.entryTop < MIN_FLOOR) prop = new Prop(new Shape[] {square(MIN_WALKTHROUGH)}, material, label);
        // Walk-through props must clear a step.
        float min = blocks ? MIN_FLOOR : MIN_WALKTHROUGH;
        if (prop.entryTop < min || prop.entryTop > MAX_TOP) return blocks ? BLOCKER : null;
        // Small tents are an A-frame across the tile, about 64 degrees.
        if (smallTent(tileset, p)) {
            prop.rampAngle = (float) Math.atan(prop.entryTop * LEVEL_METERS / 0.5);
            String facing = p.get("Facing");
            prop.ridgeAlongY = "E".equals(facing) || "W".equals(facing);
        }
        return prop;
    }

    private static final IsoFlagType[] EDGE_FLAGS = {
            IsoFlagType.collideN, IsoFlagType.collideW, IsoFlagType.HoppableN, IsoFlagType.HoppableW,
            IsoFlagType.TallHoppableN, IsoFlagType.TallHoppableW, IsoFlagType.WallN, IsoFlagType.WallW,
            IsoFlagType.WallNW, IsoFlagType.WallSE, IsoFlagType.WallNTrans, IsoFlagType.WallWTrans,
            IsoFlagType.windowN, IsoFlagType.windowW, IsoFlagType.WindowN, IsoFlagType.WindowW,
            IsoFlagType.doorN, IsoFlagType.doorW, IsoFlagType.DoorWallN, IsoFlagType.DoorWallW,
            IsoFlagType.attachedN, IsoFlagType.attachedS, IsoFlagType.attachedE, IsoFlagType.attachedW,
            IsoFlagType.attachedNW, IsoFlagType.attachedSE, IsoFlagType.attachedCeiling, IsoFlagType.attachedSurface};
    private static final String[] STAIRS = {"stairsTN", "stairsMN", "stairsBN", "stairsTW", "stairsMW", "stairsBW"};

    /** Edge objects (Ledges), wall-mounted and tabletop items, and stairs aren't ground props. */
    private static boolean edgeOrAttached(PropertyContainer p) {
        for (IsoFlagType f : EDGE_FLAGS) if (p.has(f)) return true;
        for (String st : STAIRS) if (p.has(st)) return true;
        return false;
    }

    /** Measured tops where the geometry is a placeholder (St. Peregrin's sign letters). */
    private static float topOverride(String tileset, int index) {
        if ("signs_one-off_04".equals(tileset) && index >= 14 && index <= 27) return 0.52f;
        return 0;
    }

    private static float fixedHeight(String name) {
        if ("Single Stacked Hay".equals(name)) return 0.18f;
        if ("Double Stacked Hay".equals(name)) return 0.36f;
        return 0;
    }

    private static final java.util.Set<String> STACKS = java.util.Set.of("Crate", "Military Barrier", "Cartbox");

    /** Pixel height minus footprint depth; median error -0.02 level. */
    private static float pixelHeight(IsoSprite s) {
        Texture tex = s.texture;
        if (tex == null) {
            try {
                tex = s.getTextureForCurrentFrame(IsoDirections.N);
            } catch (Throwable t) {
                return 0;
            }
        }
        if (tex == null || tex.getHeightOrig() <= 0) return 0;
        float scale = tex.getHeightOrig() / 128f;
        // The top crate of a stack is drawn up high, so measure from the tile bottom.
        String name = s.getProperties() != null ? s.getProperties().get("CustomName") : null;
        float h = name != null && STACKS.contains(name) ? Math.max(tex.getHeight(), tex.getHeightOrig() - tex.getOffsetY())
                : tex.getHeight();
        float px = h / scale - FOOTPRINT_PX;
        return px > 0 ? px / PX_PER_LEVEL : 0;
    }

    /** The tile's item surface (levels), 0 if none. */
    private static float surfaceHeight(PropertyContainer p) {
        String v = p.get("Surface");
        if (v == null) return 0;
        try {
            return Integer.parseInt(v.trim()) / PX_PER_LEVEL;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Tallest sibling tile of a multi-tile object, for tiles with no image. */
    private static float siblingPixelHeight(IsoSprite s, String tileset, int index, PropertyContainer p) {
        String name = p.get("CustomName");
        if (name == null) return 0;
        String group = p.get("GroupName"), facing = p.get("Facing");
        float best = 0;
        for (int i = Math.max(0, index - 8); i <= index + 8; i++) {
            if (i == index) continue;
            IsoSprite sib = IsoSpriteManager.instance.namedMap.get(tileset + "_" + i);
            if (sib == null || sib.getProperties() == null) continue;
            PropertyContainer sp = sib.getProperties();
            if (!name.equals(sp.get("CustomName")) || !java.util.Objects.equals(group, sp.get("GroupName"))
                    || !java.util.Objects.equals(facing, sp.get("Facing"))) continue;
            float h = pixelHeight(sib);
            if (h <= MAX_TOP) best = Math.max(best, h); // skip tall slices like a tent flap
        }
        return best;
    }

    private static Shape principal(Shape[] shapes) {
        Shape best = shapes[0];
        float area = -1;
        for (Shape s : shapes) {
            float a = s.area();
            if (a > area + 1e-4f || (Math.abs(a - area) <= 1e-4f && s.top > best.top)) {
                area = a;
                best = s;
            }
        }
        return best;
    }

    private static boolean startsWithAny(String s, String[] prefixes) {
        for (String pre : prefixes) if (s.startsWith(pre)) return true;
        return false;
    }

    /** Geometry of the tile this sprite borrows its depth from. */
    private static Shape[] aliasedGeometry(String spriteName) {
        if (spriteName == null) return new Shape[0];
        String alias;
        try {
            alias = TileDepthTextureAssignmentManager.getInstance().getAssignedTileName("game", spriteName);
        } catch (Throwable t) {
            return new Shape[0];
        }
        if (alias == null || alias.equals(spriteName)) return new Shape[0];
        int i = alias.lastIndexOf('_');
        if (i <= 0) return new Shape[0];
        try {
            return geometry(alias.substring(0, i), Integer.parseInt(alias.substring(i + 1)));
        } catch (NumberFormatException e) {
            return new Shape[0];
        }
    }

    /** A sprite's own or aliased geometry, around its square's center. */
    static Shape[] shapes(IsoSprite s) {
        Shape[] g = geometry(tileset(s), s.tileSheetIndex);
        return g.length > 0 ? g : aliasedGeometry(s.getName());
    }

    private static String tileset(IsoSprite s) {
        if (s.tilesetName != null) return s.tilesetName;
        String n = s.getName();
        int i = n == null ? -1 : n.lastIndexOf('_');
        return i > 0 ? n.substring(0, i) : n;
    }

    private static int material(PropertyContainer p, boolean tires, boolean fountain) {
        if (tires) return MAT_CARPET;
        if (fountain || "Stone".equals(p.get("MaterialType"))) return MAT_CONCRETE;
        String m = p.get("Material");
        if (m == null) m = p.get("MaterialType");
        if (m == null) return MAT_WOOD;
        if (m.startsWith("Metal") || m.startsWith("SmallMetal") || m.equals("Electric")) return MAT_METAL;
        if (m.equals("Stone") || m.equals("Brick") || m.equals("Concrete")) return MAT_CONCRETE;
        return MAT_WOOD;
    }

    private static Shape square(float top) {
        Shape s = new Shape();
        s.hx = s.hy = 0.5f;
        s.top = top;
        return s;
    }

    private static Shape[] geometry(String tileset, int index) {
        if (tileset == null || index < 0) return new Shape[0];
        ArrayList<TileGeometryFile.Geometry> list =
                TileGeometryManager.getInstance().getGeometry("game", tileset, index % 8, index / 8);
        if (list == null) return new Shape[0];
        ArrayList<Shape> out = new ArrayList<>();
        for (TileGeometryFile.Geometry g : list) {
            if (g.isBox()) {
                TileGeometryFile.Box b = g.asBox();
                if (Math.abs(b.rotate.x) > 1 || Math.abs(b.rotate.z) > 1) continue;
                float top = (float) ((b.translate.y + b.max.y) / LEVEL_METERS);
                if (top < MIN_FLOOR) continue;
                double yaw = Math.toRadians(b.rotate.y);
                float c = (float) Math.cos(yaw), sn = (float) Math.sin(yaw);
                float mx = (b.min.x + b.max.x) / 2, mz = (b.min.z + b.max.z) / 2;
                Shape s = new Shape();
                // JOML rotateY
                s.cx = b.translate.x + mx * c + mz * sn;
                s.cy = b.translate.z - mx * sn + mz * c;
                s.hx = (b.max.x - b.min.x) / 2;
                s.hy = (b.max.z - b.min.z) / 2;
                s.cos = c;
                s.sin = sn;
                s.top = top;
                s.bottom = (float) ((b.translate.y + b.min.y) / LEVEL_METERS);
                out.add(s);
            } else if (g.isCylinder()) {
                TileGeometryFile.Cylinder cy = g.asCylinder();
                float rx = ((cy.rotate.x % 360) + 360) % 360;
                boolean upright = (Math.abs(rx - 270) < 1 || Math.abs(rx - 90) < 1)
                        && Math.abs(cy.rotate.y) < 1 && Math.abs(cy.rotate.z) < 1;
                if (!upright) continue;
                float top = (float) ((cy.translate.y + cy.height / 2) / LEVEL_METERS);
                if (top < MIN_FLOOR) continue;
                Shape s = new Shape();
                s.round = true;
                s.bottom = (float) ((cy.translate.y - cy.height / 2) / LEVEL_METERS);
                s.cx = cy.translate.x;
                s.cy = cy.translate.z;
                s.r = Math.max(cy.radius1, cy.radius2);
                s.top = top;
                out.add(s);
            }
        }
        return out.toArray(new Shape[0]);
    }

    /** Feet height needed to enter. NaN = never, 0 = clear, else the tallest prop's main surface. */
    static double entryTop(IsoGridSquare sq) {
        if (sq == null) return 0;
        if (!enabledAt(sq)) return sq.isSolid() || sq.isSolidTrans() ? Double.NaN : 0;
        PZArrayList<IsoObject> objects = sq.getObjects();
        double top = 0;
        boolean found = false;
        for (int i = 0; i < objects.size(); i++) {
            IsoObject o = objects.get(i);
            if (o.getSprite() == null || o.getSprite().getProperties() == null || !obstacle(o.getSprite().getProperties())) continue;
            if (o.getSprite().getProperties().has(IsoFlagType.water)) continue; // the water itself, see Water
            Prop p = prop(o);
            if (p == null || p == BLOCKER) return Double.NaN;
            top = Math.max(top, p.entryTop);
            found = true;
        }
        // Unattributed solid square stays solid.
        if (!found && (sq.isSolid() || sq.isSolidTrans()) && !Water.open(sq)) return Double.NaN;
        return top;
    }

    private static boolean smallTent(String tileset, PropertyContainer p) {
        return "camping_01".equals(tileset) && "Tent".equals(p.get("CustomName")) && "Small".equals(p.get("GroupName"));
    }

    private static boolean bigTent(String tileset, PropertyContainer p) {
        return "camping_04".equals(tileset) && "Tent".equals(p.get("CustomName"));
    }

    /** By name, so the server can tell too. */
    static boolean hasTent(IsoGridSquare sq) {
        if (sq == null) return false;
        PZArrayList<IsoObject> objects = sq.getObjects();
        for (int i = 0; i < objects.size(); i++) {
            IsoSprite s = objects.get(i).getSprite();
            if (s == null || s.getProperties() == null) continue;
            String ts = tileset(s);
            if (smallTent(ts, s.getProperties()) || bigTent(ts, s.getProperties())) return true;
        }
        return false;
    }

    static double squareDistance(int gx, int gy, double x, double y) {
        return Math.hypot(Math.max(0, Math.max(gx - x, x - (gx + 1))), Math.max(0, Math.max(gy - y, y - (gy + 1))));
    }

    /** The tent on this square, or null. */
    static Prop ramp(IsoGridSquare sq) {
        if (sq == null || !enabledAt(sq)) return null;
        PZArrayList<IsoObject> objects = sq.getObjects();
        for (int i = 0; i < objects.size(); i++) {
            Prop p = prop(objects.get(i));
            if (p != null && p != BLOCKER && p.rampAngle > 0) return p;
        }
        return null;
    }

    /** Everywhere, or outdoors only. */
    private static boolean enabledAt(IsoGridSquare sq) {
        int mode = Cfg.propMode;
        return mode == MODE_ALL || (mode == MODE_OUTDOOR && sq.getRoom() == null);
    }

    /** What's in the square and whether it's jumpable, for the HUD. */
    static String describe(IsoGridSquare sq) {
        if (sq == null) return "-";
        StringBuilder sb = new StringBuilder();
        PZArrayList<IsoObject> objects = sq.getObjects();
        for (int i = 0; i < objects.size(); i++) {
            IsoObject o = objects.get(i);
            IsoSprite s = o.getSprite();
            if (s == null || s.getProperties() == null) continue;
            Prop p = prop(o);
            boolean blocks = obstacle(s.getProperties());
            if (p == null && !blocks) continue;
            if (sb.length() > 0) sb.append(", ");
            if (p == null || p == BLOCKER) {
                sb.append(s.getName()).append(" blocks");
            } else {
                sb.append(p.name).append(' ').append(String.format("%.2f", p.entryTop)).append(" [").append(s.getName()).append(']');
            }
        }
        return sb.length() == 0 ? "clear" : sb.toString();
    }

    /** The prop topUnder found. */
    static Prop lastProp;
    static int lastPropX, lastPropY;
    /** How far below a ramp's surface you can be and still be put on it. */
    private static final double RAMP_STEP = 0.45;
    /** Collision leeway on a ramp, the body overlaps the slope ahead of its center. */
    static final double RAMP_ALLOW = 0.3;

    /** Ramp surface height at (x, y) above its square's floor. */
    static double rampHeightAt(IsoCell cell, Prop p, int gx, int gy, int z, double x, double y) {
        double u = Physics.clamp01(p.ridgeAlongY ? x - gx : y - gy); // across the ridge
        if (!p.halfRamp) return p.entryTop * Math.max(0, 1 - Math.abs(u - 0.5) / 0.5);
        int side = ridgeSide(cell, p, gx, gy, z);
        if (side == 0) return p.entryTop; // unknown half, flat
        double d = side > 0 ? u : 1 - u; // from the outer edge
        return p.entryTop * Physics.clamp01((d - p.rampInset) / (1 - p.rampInset));
    }

    /** +1 or -1 uphill across the ridge, 0 on it. */
    static int uphill(IsoCell cell, Prop p, int gx, int gy, int z, double x, double y) {
        if (p.halfRamp) return ridgeSide(cell, p, gx, gy, z);
        double u = p.ridgeAlongY ? x - gx : y - gy;
        return u < 0.48 ? 1 : u > 0.52 ? -1 : 0;
    }

    /** Which side of the square the ridge is on. */
    private static int ridgeSide(IsoCell cell, Prop p, int gx, int gy, int z) {
        boolean hi = p.ridgeAlongY ? hasTent(cell.getGridSquare(gx + 1, gy, z)) : hasTent(cell.getGridSquare(gx, gy + 1, z));
        boolean lo = p.ridgeAlongY ? hasTent(cell.getGridSquare(gx - 1, gy, z)) : hasTent(cell.getGridSquare(gx, gy - 1, z));
        return hi == lo ? 0 : hi ? 1 : -1;
    }

    /** Highest prop top under (x, y) your feet can be on, or -1. */
    static double topUnder(IsoCell cell, double x, double y, int level, double feet, double stepUp, double margin) {
        double best = -1;
        lastProp = null;
        int sx = (int) Math.floor(x), sy = (int) Math.floor(y);
        for (int gx = sx - 1; gx <= sx + 1; gx++) {
            for (int gy = sy - 1; gy <= sy + 1; gy++) {
                IsoGridSquare sq = cell.getGridSquare(gx, gy, level);
                if (sq == null || !enabledAt(sq)) continue;
                PZArrayList<IsoObject> objects = sq.getObjects();
                double lx = x - (gx + 0.5), ly = y - (gy + 0.5);
                for (int i = 0; i < objects.size(); i++) {
                    IsoObject o = objects.get(i);
                    Prop p = prop(o);
                    if (p == null || p == BLOCKER) continue;
                    if (p.rampAngle > 0) {
                        // Slope, the floor right under you.
                        if (x < gx || x > gx + 1 || y < gy || y > gy + 1) continue;
                        double top = level + rampHeightAt(cell, p, gx, gy, level, x, y);
                        if (top <= best || feet < top - RAMP_STEP) continue;
                        best = top;
                        lastProp = p;
                        lastPropX = gx;
                        lastPropY = gy;
                        continue;
                    }
                    // Solid props block their whole square, so you stand anywhere over it too.
                    double whole = level + p.entryTop;
                    if (whole > best && feet >= whole - stepUp && obstacle(o.getSprite().getProperties())
                            && squareDistance(gx, gy, x, y) <= margin) {
                        best = whole;
                        lastProp = p;
                        lastPropX = gx;
                        lastPropY = gy;
                    }
                    for (Shape s : p.shapes) {
                        double top = level + s.top;
                        double m = s.thin() ? Math.max(margin, Cfg.fenceFooting) : margin;
                        if (top <= best || feet < top - stepUp || !s.contains(lx, ly, m)) continue;
                        best = top;
                        lastProp = p;
                        lastPropX = gx;
                        lastPropY = gy;
                    }
                }
            }
        }
        return best;
    }

    /** Car-area pass. -1 if a solid square blocks the move, 1 if you're above them all, 0 if none. */
    static int squaresAt(IsoCell cell, double ox, double oy, double x, double y, double feet, double radius, double stepUp, boolean airborne) {
        int level = (int) Math.floor(feet);
        lastBlock = null;
        boolean any = false;
        for (int gx = (int) Math.floor(x - radius); gx <= (int) Math.floor(x + radius); gx++) {
            for (int gy = (int) Math.floor(y - radius); gy <= (int) Math.floor(y + radius); gy++) {
                IsoGridSquare sq = cell.getGridSquare(gx, gy, level);
                if (sq == null || !(sq.isSolid() || sq.isSolidTrans())) continue;
                // Moving away from it never blocks.
                if (squareDistance(gx, gy, x, y) > squareDistance(gx, gy, ox, oy) + 1e-6) {
                    any = true;
                    continue;
                }
                // Water blocks only when you're in it and on the ground.
                if (Water.open(sq) && !airborne && feet < level + Water.SURFACE) return blocked(sq, "water", feet - level, Water.SURFACE);
                Prop slope = ramp(sq);
                if (slope != null) {
                    // Tent, only its surface nearest you counts.
                    double h = rampHeightAt(cell, slope, gx, gy, level, Math.max(gx, Math.min(gx + 1, x)), Math.max(gy, Math.min(gy + 1, y)));
                    if (feet < level + h - RAMP_ALLOW) return blocked(sq, "tent side", feet - level, h - RAMP_ALLOW);
                    any = true;
                    continue;
                }
                double top = entryTop(sq);
                if (Double.isNaN(top) || feet < level + top - stepUp) return blocked(sq, null, feet - level, top - stepUp);
                any = true;
            }
        }
        return any ? 1 : 0;
    }

    /** Why squaresAt last blocked you, for the overlay. */
    static String lastBlock;

    private static int blocked(IsoGridSquare sq, String what, double feet, double need) {
        if (Cfg.wireHud) {
            lastBlock = String.format("square %d,%d: %s, feet %.2f < %s", sq.x, sq.y, what != null ? what : describe(sq), feet,
                    Double.isNaN(need) ? "never (not jumpable)" : String.format("%.2f", need));
        }
        return -1;
    }
}

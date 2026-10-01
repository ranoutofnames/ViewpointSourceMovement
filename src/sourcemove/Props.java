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

/**
 * Props you can jump onto and over. Heights come from the game's own tile geometry (media/tileGeometry.txt,
 * loaded by TileGeometryManager): boxes and upright cylinders per tile, x/z in tiles relative to the
 * square's center (x east, z south), y in meters (one Z-level = 2.449 m). Where there is none, from the
 * sprite's opaque pixel height, which matches geometry to within 0.1 level for 80% of props.
 */
final class Props {
    private Props() {}

    static final int MODE_OFF = 0, MODE_OUTDOOR = 1, MODE_ALL = 2;
    static final int MAT_CONCRETE = 2, MAT_WOOD = 7, MAT_CARPET = 8, MAT_METAL = 12;

    private static final double LEVEL_METERS = 2.44949;
    /** Only things you could plausibly climb: up to 0.9 level (2.2 m); taller objects are wall-height. */
    private static final float MAX_TOP = 0.9f;
    /** Shapes lower than this are base plates and feet, not something to stand on (or step into). */
    private static final float MIN_FLOOR = 0.08f;
    /** Non-solid props must top out above the 0.12 step-up, so walking through them never lifts you. */
    private static final float MIN_WALKTHROUGH = 0.15f;
    /**
     * Walk-through objects smaller than a garbage can (0.35-0.41 level tall, 0.36-0.45 square tiles) stay
     * clutter you walk through rather than something you land on.
     */
    private static final float WALKTHROUGH_MIN_TOP = 0.33f, WALKTHROUGH_MIN_AREA = 0.3f;
    /** A non-solid object's shape counts as standing on the ground if it starts this low (levels). */
    private static final float GROUNDED = 0.05f;
    /** Pixels per level in a 1x tile (a wall is 96 px tall), and the footprint depth an object's image adds. */
    private static final float PX_PER_LEVEL = 96f, FOOTPRINT_PX = 24f;
    private static final float TIRE_WALL = 0.35f, TIRE_WALL_LOW = 0.2f;
    /** Two-tile tents (camping_04): ridge 1.15 m; each side's slope starts 0.1 tile in from the outer edge. */
    private static final float BIG_TENT_TOP = 0.47f, BIG_TENT_INSET = 0.1f;
    /** Objects the game flags IsLow but has no shape for: about counter height. */
    private static final float LOW_PROP = 0.35f;

    /** One solid part of a prop: an oriented box or an upright cylinder, top in levels. */
    static final class Shape {
        float cx, cy, hx, hy, cos = 1, sin, r, top, bottom;
        boolean round;

        /** Thinner than 0.2 tiles (a sign panel, a rail, a post): gets fence-style footing. */
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

    /** A prop sprite's shapes plus the footstep material to use on top of it. */
    static final class Prop {
        final Shape[] shapes;
        final float entryTop;
        final int material;
        final String name;
        /** A-frame sides you can walk up and trimp off (tents): side angle in radians, 0 = none. */
        float rampAngle;
        /** The ridge runs north-south (sides face east and west); else east-west. */
        boolean ridgeAlongY;
        /**
         * One side per square (two-tile tents): the slope rises across the square to the ridge on the edge it
         * shares with the tent's other half. Otherwise the whole A-frame is in one square, ridge down its middle.
         */
        boolean halfRamp;
        /** How far in from the square's outer edge the slope starts (tiles). */
        float rampInset;

        Prop(Shape[] shapes, int material, String name) {
            this.shapes = shapes;
            this.material = material;
            this.name = name;
            // The principal surface (largest footprint) is what you step onto: a bench's seat, not its
            // backrest; a bed's mattress, not its headboard; a fountain's basin, not its spout.
            this.entryTop = shapes.length == 0 ? 0 : principal(shapes).top;
        }
    }

    /** Sentinel: blocks movement and isn't jumpable. */
    private static final Prop BLOCKER = new Prop(new Shape[0], 0, null);
    private static final Map<IsoSprite, Prop> cache = new IdentityHashMap<>();
    private static int cachedMode = -1;

    private static boolean obstacle(PropertyContainer p) {
        return p.has(IsoFlagType.solid) || p.has(IsoFlagType.solidtrans);
    }

    /** null = not a prop and not in the way; {@link #BLOCKER} = in the way; else a jumpable prop. */
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

    /** Tilesets whose solid objects are never props: trees, tents and shelters, cliff/terrain blends. */
    private static final String[] NEVER = {"vegetation_trees", "vegetation_foliage", "f_", "d_plants", "blends_", "radio_tower", "e_"};

    /** Bushes and hedges are never something to stand on (the game tags plants "vegitation", sic). */
    private static boolean bush(PropertyContainer p, String name) {
        return p.has("vegitation") || "Bush".equals(name) || "Hedge".equals(name);
    }
    /** Non-solid objects that make poor footing even with a shape: bedding, plants, curtains, floor decals. */
    private static final String[] NOT_FLOORS = {"camping_02", "vegetation_", "fixtures_windows_curtains", "floors_", "fixtures_stairs", "damaged_objects"};

    /**
     * Every solid object is a prop you can jump onto and over, at its real height, if that height can be
     * found: its own tile geometry, else the geometry of the tile the game aliases it to for depth
     * (tileDepthTextureAssignments.txt: rotations and sibling tiles of multi-tile objects), else fixed
     * heights for known cases, else its sprite's pixel height, else a generic low height for objects the
     * game flags IsLow. Anything taller than 0.9 level stays solid. Non-solid objects you can walk through
     * become things you can stand on when they're low (IsLow) or stand on the ground with a known shape
     * (signs, chairs, toilets, gravestones, the low tire stacks).
     */
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

        // Racetrack tire walls (Irvington speedway): straight, end and low pieces; only some are tagged Rubber,
        // and their geometry is a one-level placeholder.
        boolean raceTires = "recreational_sports_01".equals(tileset) && index >= 136 && index <= 149;
        boolean tires = raceTires || "Tires".equals(name) || "Tire".equals(name);
        boolean fountain = "location_community_park_01".equals(tileset) && index >= 40 && index <= 48;
        boolean bench = name != null && name.endsWith("Bench") || "Low Bench".equals(group);
        float override = topOverride(tileset, index);
        // St. Peregrin Hospital's low Emergency sign: asked for by name, though it's below the walk-through cutoff.
        boolean emergencySign = "location_community_medical_01".equals(tileset)
                && (index >= 84 && index <= 87 || index >= 92 && index <= 95);
        boolean named = tires || fountain || bench || emergencySign || "Bird Bath".equals(name) || override > 0;
        if (!blocks && !named && (startsWithAny(tileset, NOT_FLOORS) || edgeOrAttached(p))) return null;

        String label = fountain ? "Fountain" : override > 0 ? "Sign"
                : name == null ? s.getName() : group == null ? name : group + " " + name;
        int material = material(p, tires, fountain);
        if (raceTires) return new Prop(new Shape[] {square(low ? TIRE_WALL_LOW : TIRE_WALL)}, material, "Tire wall");
        if (blocks && bigTent(tileset, p)) {
            // Two tiles wide, ridge on the line between them (tileGeometry: the gable is a triangle 1.8 tiles
            // wide and 1.15 m tall, the visible roof panel 53.7 degrees), sides about 52 degrees.
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
            // You walk through non-solid things; only ground-standing ones (not wall posters, shelves,
            // hanging signs) are something to land on.
            // Walk-through clutter: only ground-standing things at least garbage-can sized are something to land on.
            if (!blocks && !named) {
                Shape main = principal(shapes);
                if (main.bottom > GROUNDED || main.top < WALKTHROUGH_MIN_TOP || main.area() < WALKTHROUGH_MIN_AREA) return null;
            }
        } else {
            if (!blocks && !named) return null; // walk-through with no known shape: can't tell its size
            float fixed = fixedHeight(name);
            if (fixed <= 0 && blocks) fixed = pixelHeight(s);
            if (fixed <= 0 && blocks) fixed = siblingPixelHeight(s, tileset, index, p);
            if (fixed <= 0 && (low || named)) fixed = LOW_PROP;
            if (fixed <= 0) return blocks ? BLOCKER : null;
            shapes = new Shape[] {square(fixed)};
        }
        Prop prop = new Prop(shapes, material, label);
        // Something you walk through must be taller than a step, or walking over it would bob you up.
        float min = blocks ? MIN_FLOOR : MIN_WALKTHROUGH;
        if (prop.entryTop < min || prop.entryTop > MAX_TOP) return blocks ? BLOCKER : null;
        // Small tents are A-frames spanning the tile: sides rise from its edges to the ridge (about 64 degrees).
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

    /**
     * Walls, fences, windows and doors sit on square edges and have their own heights in {@link Ledges};
     * wall-mounted, ceiling and tabletop objects aren't ground props; stairs have their own Z.
     */
    private static boolean edgeOrAttached(PropertyContainer p) {
        for (IsoFlagType f : EDGE_FLAGS) if (p.has(f)) return true;
        for (String st : STAIRS) if (p.has(st)) return true;
        return false;
    }

    /**
     * Measured tops (levels) where the game's geometry is a full-height placeholder. The St. Peregrin
     * Hospital name letters stand 0.20-0.52 level (measured per pixel column against the tile's ground line).
     */
    private static float topOverride(String tileset, int index) {
        if ("signs_one-off_04".equals(tileset) && index >= 14 && index <= 27) return 0.52f;
        return 0;
    }

    /** Heights (levels) for props with no geometry at all. */
    private static float fixedHeight(String name) {
        if ("Single Stacked Hay".equals(name)) return 0.18f;
        if ("Double Stacked Hay".equals(name)) return 0.36f;
        return 0;
    }

    /**
     * Height from the sprite's trimmed image: its opaque pixels span the object's height plus the depth of
     * its footprint. Checked against every solid prop with geometry: median error -0.02 level.
     */
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
        float px = tex.getHeight() / scale - FOOTPRINT_PX;
        return px > 0 ? px / PX_PER_LEVEL : 0;
    }

    /**
     * Multi-tile objects (big tents) have solid tiles with no image of their own: the neighbouring tiles
     * draw over them. Use the tallest sibling of the same object (same name, group and facing, nearby in
     * the tileset) so the object doesn't have unjumpable holes.
     */
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
            if (h <= MAX_TOP) best = Math.max(best, h); // skip odd tall slices (a tent's flap tile)
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

    /** The tile whose depth texture (and so shape) this sprite borrows, e.g. the other rotation of a crate. */
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
                // JOML rotateY: x' = x cos + z sin, z' = -x sin + z cos
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

    /**
     * How high your feet must be to move into this square: NaN if something in it can't be jumped,
     * 0 if nothing blocks, else the lowest top of the tallest prop in it (you can climb from a bench seat
     * over its backrest, but not walk into the seat from the ground).
     */
    static double entryTop(IsoGridSquare sq) {
        if (sq == null) return 0;
        if (!enabledAt(sq)) return sq.isSolid() || sq.isSolidTrans() ? Double.NaN : 0;
        PZArrayList<IsoObject> objects = sq.getObjects();
        double top = 0;
        boolean found = false;
        for (int i = 0; i < objects.size(); i++) {
            IsoObject o = objects.get(i);
            if (o.getSprite() == null || o.getSprite().getProperties() == null || !obstacle(o.getSprite().getProperties())) continue;
            if (o.getSprite().getProperties().has(IsoFlagType.water)) continue; // the water itself: see Water
            Prop p = prop(o);
            if (p == null || p == BLOCKER) return Double.NaN;
            top = Math.max(top, p.entryTop);
            found = true;
        }
        // A solid square whose blocker we couldn't attribute to a sprite stays solid (open water is Water's).
        if (!found && (sq.isSolid() || sq.isSolidTrans()) && !Water.open(sq)) return Double.NaN;
        return top;
    }

    private static boolean smallTent(String tileset, PropertyContainer p) {
        return "camping_01".equals(tileset) && "Tent".equals(p.get("CustomName")) && "Small".equals(p.get("GroupName"));
    }

    /** The two-tile tents (yellow, blue, brown, green), camping_04. */
    private static boolean bigTent(String tileset, PropertyContainer p) {
        return "camping_04".equals(tileset) && "Tent".equals(p.get("CustomName"));
    }

    /** A tent stands on this square (by name, so it works on a server, which can't measure props). */
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

    /** Distance from (x, y) to square (gx, gy); 0 inside it. */
    static double squareDistance(int gx, int gy, double x, double y) {
        return Math.hypot(Math.max(0, Math.max(gx - x, x - (gx + 1))), Math.max(0, Math.max(gy - y, y - (gy + 1))));
    }

    /** The trimpable prop (a tent) on this square, or null. */
    static Prop ramp(IsoGridSquare sq) {
        if (sq == null || !enabledAt(sq)) return null;
        PZArrayList<IsoObject> objects = sq.getObjects();
        for (int i = 0; i < objects.size(); i++) {
            Prop p = prop(objects.get(i));
            if (p != null && p != BLOCKER && p.rampAngle > 0) return p;
        }
        return null;
    }

    /** Props count on this square: everywhere, or only outside buildings. */
    private static boolean enabledAt(IsoGridSquare sq) {
        int mode = Cfg.propMode;
        return mode == MODE_ALL || (mode == MODE_OUTDOOR && sq.getRoom() == null);
    }

    /** HUD: what blocks this square and whether it's jumpable, e.g. "Tire wall 0.35" or "industry_01_53 blocks". */
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

    /** Output of {@link #topUnder}: the prop you're standing on. */
    static Prop lastProp;
    /** Square of {@link #lastProp}. */
    static int lastPropX, lastPropY;
    /**
     * How far below a ramp's surface you can be and still be put on it: more than one frame's climb up a steep
     * tent side, so walking up it never loses the floor. Its edges are what keep you from walking into its middle.
     */
    private static final double RAMP_STEP = 0.45;
    /**
     * Collision leeway on a ramp: the body (radius 0.3) overlaps the slope ahead of its center, which on a tent's
     * 52 degree side is ~0.16 level higher, plus a frame's climb. With only the 0.12 step-up you'd catch on the
     * other half of a tent near its ridge, going up or down.
     */
    static final double RAMP_ALLOW = 0.3;

    /** Height (levels above its square's floor) of a ramp prop's surface at (x, y), clamped into its square. */
    static double rampHeightAt(IsoCell cell, Prop p, int gx, int gy, int z, double x, double y) {
        double u = clamp01(p.ridgeAlongY ? x - gx : y - gy); // across the ridge
        if (!p.halfRamp) return p.entryTop * Math.max(0, 1 - Math.abs(u - 0.5) / 0.5);
        int side = ridgeSide(cell, p, gx, gy, z);
        if (side == 0) return p.entryTop; // can't tell which half: flat
        double d = side > 0 ? u : 1 - u; // distance from the outer edge
        return p.entryTop * clamp01((d - p.rampInset) / (1 - p.rampInset));
    }

    /** Uphill direction across the ridge axis at (x, y): +1, -1, or 0 on the ridge or flat. */
    static int uphill(IsoCell cell, Prop p, int gx, int gy, int z, double x, double y) {
        if (p.halfRamp) return ridgeSide(cell, p, gx, gy, z);
        double u = p.ridgeAlongY ? x - gx : y - gy;
        return u < 0.48 ? 1 : u > 0.52 ? -1 : 0;
    }

    /** Which edge of a two-tile tent's square the ridge is on: toward the neighbour that's the tent's other half. */
    private static int ridgeSide(IsoCell cell, Prop p, int gx, int gy, int z) {
        boolean hi = p.ridgeAlongY ? hasTent(cell.getGridSquare(gx + 1, gy, z)) : hasTent(cell.getGridSquare(gx, gy + 1, z));
        boolean lo = p.ridgeAlongY ? hasTent(cell.getGridSquare(gx - 1, gy, z)) : hasTent(cell.getGridSquare(gx, gy - 1, z));
        return hi == lo ? 0 : hi ? 1 : -1;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /** Absolute Z of the highest prop top under (x,y) that your feet can be on (within stepUp), or -1. */
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
                    Prop p = prop(objects.get(i));
                    if (p == null || p == BLOCKER) continue;
                    if (p.rampAngle > 0) {
                        // A slope: the floor right under you, following the A-frame.
                        if (x < gx || x > gx + 1 || y < gy || y > gy + 1) continue;
                        double top = level + rampHeightAt(cell, p, gx, gy, level, x, y);
                        if (top <= best || feet < top - RAMP_STEP) continue;
                        best = top;
                        lastProp = p;
                        lastPropX = gx;
                        lastPropY = gy;
                        continue;
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

    /**
     * For CollideWithObstacles, moving from (ox,oy) to (x,y): -1 if a solid square overlapping the circle at (x,y) is in your way,
     * 1 if there are solid squares and you're above all of them, 0 if there are none. Open water counts as
     * cleared while airborne or standing above it ({@link Water}).
     */
    static int squaresAt(IsoCell cell, double ox, double oy, double x, double y, double feet, double radius, double stepUp, boolean airborne) {
        int level = (int) Math.floor(feet);
        boolean any = false;
        for (int gx = (int) Math.floor(x - radius); gx <= (int) Math.floor(x + radius); gx++) {
            for (int gy = (int) Math.floor(y - radius); gy <= (int) Math.floor(y + radius); gy++) {
                IsoGridSquare sq = cell.getGridSquare(gx, gy, level);
                if (sq == null || !(sq.isSolid() || sq.isSolidTrans())) continue;
                // Moving away from it (stepping or falling off its edge): never in your way. Vanilla can't
                // resolve a body that already overlaps a solid square and would stop you dead.
                if (squareDistance(gx, gy, x, y) > squareDistance(gx, gy, ox, oy) + 1e-6) {
                    any = true;
                    continue;
                }
                // Open water: only while above it, and then over any prop standing in it.
                if (Water.open(sq) && !airborne && feet < level + Water.SURFACE) return -1;
                Prop slope = ramp(sq);
                if (slope != null) {
                    // A tent: only its surface at the point of the square nearest you has to be cleared.
                    double h = rampHeightAt(cell, slope, gx, gy, level, Math.max(gx, Math.min(gx + 1, x)), Math.max(gy, Math.min(gy + 1, y)));
                    if (feet < level + h - RAMP_ALLOW) return -1;
                    any = true;
                    continue;
                }
                double top = entryTop(sq);
                if (Double.isNaN(top) || feet < level + top - stepUp) return -1;
                any = true;
            }
        }
        return any ? 1 : 0;
    }
}

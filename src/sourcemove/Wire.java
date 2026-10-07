package sourcemove;

import java.util.ArrayList;

import zombie.characters.FallingConstants;
import zombie.characters.IsoPlayer;
import zombie.core.SpriteRenderer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.pathfind.VehiclePoly;
import zombie.ui.TextManager;
import zombie.ui.UIFont;
import zombie.util.list.PZArrayList;
import zombie.vehicles.BaseVehicle;

/** Collision overlay. Top and side wireframes of what collision sees, plus a log of what blocked you. */
final class Wire {
    private Wire() {}


    private static final int LOG = 8;
    private static final String[] logText = new String[LOG];
    private static final long[] logAt = new long[LOG];
    private static final int[] logCount = new int[LOG];
    private static int logHead;

    /** Log a line; repeats only bump a count. */
    static void log(String s) {
        if (s == null) return;
        long now = System.nanoTime();
        int last = (logHead + LOG - 1) % LOG;
        if (s.equals(logText[last])) {
            logAt[last] = now;
            logCount[last]++;
            return;
        }
        if (logText[last] != null && logCount[last] > 1) Log.info("collision: (repeated " + logCount[last] + " times)");
        // Also to console.txt, with position and motion.
        IsoPlayer p = Mover.self;
        if (p != null) {
            Log.info(String.format("collision: %s | at %.2f,%.2f z %.2f, vel %.2f,%.2f vz %.2f, %s, on %s",
                    s, p.getX(), p.getY(), p.getZ(), Mover.velX(), Mover.velY(), -p.getLastFallSpeed(),
                    Mover.grounded ? "grounded" : "airborne", Floors.floorName(p)));
        } else {
            Log.info("collision: " + s);
        }
        logText[logHead] = s;
        logAt[logHead] = now;
        logCount[logHead] = 1;
        logHead = (logHead + 1) % LOG;
    }


    private static float pushX0, pushY0, pushNx, pushNy, pushRx, pushRy;
    private static boolean pushKept;
    private static long pushAt;

    /** The car-area pass pushed you. */
    static void pushback(float x, float y, float nx, float ny, float rx, float ry, boolean kept, String why) {
        pushX0 = x;
        pushY0 = y;
        pushNx = nx;
        pushNy = ny;
        pushRx = rx;
        pushRy = ry;
        pushKept = kept;
        pushAt = System.nanoTime();
        log((kept ? "car-area pushback KEPT: " : "car-area pushback ignored: ") + (why == null ? "?" : why));
    }

    private static int blockX, blockY;
    private static boolean blockN, blockS, blockE, blockW;
    private static long blockAt;

    /** The grid collision stopped you on these sides. */
    static void gridBlock(IsoGridSquare sq, boolean n, boolean s, boolean e, boolean w, boolean clearing, String why) {
        if (sq != null) {
            blockX = sq.x;
            blockY = sq.y;
        }
        blockN = n;
        blockS = s;
        blockE = e;
        blockW = w;
        blockAt = System.nanoTime();
        String sides = (n ? "N" : "") + (s ? "S" : "") + (e ? "E" : "") + (w ? "W" : "");
        log("blocked " + sides + (clearing ? " (rising, will clear: speed kept)" : "")
                + ": " + why);
    }


    private static final int MAP = 260, SIDE_W = 340, SIDE_H = MAP, GAP = 10;
    private static final double RANGE = 2.6;
    private static final double SIDE_BACK = 0.5, SIDE_AHEAD = 3.0, SIDE_BELOW = 0.35, SIDE_ABOVE = 1.45;
    private static final float[] RED = {1f, 0.25f, 0.2f}, ORANGE = {1f, 0.6f, 0.1f}, YELLOW = {1f, 0.95f, 0.2f},
            GREEN = {0.3f, 1f, 0.35f}, CYAN = {0.3f, 0.9f, 1f}, BLUE = {0.3f, 0.5f, 1f}, MAGENTA = {1f, 0.35f, 0.9f},
            PURPLE = {0.75f, 0.5f, 1f}, WHITE = {1f, 1f, 1f}, GREY = {0.6f, 0.6f, 0.6f};

    private static double px, py, fx, fy, scale, cx, cy;
    private static double clipX0, clipY0, clipX1, clipY1;
    private static final float[] roofOut = new float[1];

    /** Both views and the log at (x, y). */
    static void draw(double x, double y) {
        IsoPlayer p = Mover.self;
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (p == null || cell == null) return;
        topView(p, cell, x, y);
        sideView(p, cell, x + MAP + GAP, y);
        logView(x, y + MAP + 6);
    }


    private static void topView(IsoPlayer p, IsoCell cell, double x0, double y0) {
        SpriteRenderer.instance.renderRect((int) x0, (int) y0, MAP, MAP, 0f, 0f, 0f, 0.6f);
        clip(x0, y0, x0 + MAP, y0 + MAP);
        px = p.getX();
        py = p.getY();
        double len = Math.hypot(p.getForwardDirectionX(), p.getForwardDirectionY());
        fx = len > 1e-4 ? p.getForwardDirectionX() / len : 0;
        fy = len > 1e-4 ? p.getForwardDirectionY() / len : -1;
        scale = MAP / (2 * RANGE);
        cx = x0 + MAP / 2.0;
        cy = y0 + MAP / 2.0;
        double feet = p.getZ();
        int level = (int) Math.floor(feet);
        double rel = feet - level;
        int r = (int) Math.ceil(RANGE * 1.42) + 1;
        int sx = (int) Math.floor(px), sy = (int) Math.floor(py);

        for (int i = -r; i <= r + 1; i++) {
            wline(sx + i, sy - r, sx + i, sy + r + 1, GREY, 0.25f, 1);
            wline(sx - r, sy + i, sx + r + 1, sy + i, GREY, 0.25f, 1);
        }
        for (int gx = sx - r; gx <= sx + r; gx++) {
            for (int gy = sy - r; gy <= sy + r; gy++) {
                IsoGridSquare sq = cell.getGridSquare(gx, gy, level);
                if (open(sq, level)) {
                    // No floor at this level.
                    wline(gx + 0.2, gy + 0.2, gx + 0.8, gy + 0.8, PURPLE, 0.35f, 1);
                    wline(gx + 0.8, gy + 0.2, gx + 0.2, gy + 0.8, PURPLE, 0.35f, 1);
                    continue;
                }
                squareView(cell, sq, gx, gy, level, rel);
            }
        }
        // Edges on top.
        for (int gx = sx - r; gx <= sx + r; gx++) {
            for (int gy = sy - r; gy <= sy + r; gy++) {
                IsoGridSquare sq = cell.getGridSquare(gx, gy, level);
                if (sq == null) continue;
                edgeView(sq, gx, gy, true, rel);
                edgeView(sq, gx, gy, false, rel);
            }
        }
        // Cars, chassis and the body-radius outline (dim).
        ArrayList<BaseVehicle> cars = Rides.nearby(cell, (float) px, (float) py);
        for (int i = 0; i < cars.size(); i++) {
            BaseVehicle v = cars.get(i);
            quad(v.getPolyPlusRadius(), PURPLE, 0.35f);
            quad(v.getPoly(), PURPLE, 1f);
            float roof = Rides.roofZ(v);
            if (!Float.isNaN(roof)) wtext(v.getX(), v.getY(), String.format("roof %.2f", roof - level), PURPLE);
        }
        long now = System.nanoTime();
        // Grid block sides, for a second.
        if (now - blockAt < 1_000_000_000L) {
            float a = 1f - (now - blockAt) / 1e9f;
            if (blockN) wline(blockX, blockY, blockX + 1, blockY, RED, a, 4);
            if (blockS) wline(blockX, blockY + 1, blockX + 1, blockY + 1, RED, a, 4);
            if (blockW) wline(blockX, blockY, blockX, blockY + 1, RED, a, 4);
            if (blockE) wline(blockX + 1, blockY, blockX + 1, blockY + 1, RED, a, 4);
        }
        // Pushback, where you were going and where you ended up.
        if (now - pushAt < 1_000_000_000L) {
            float a = 1f - (now - pushAt) / 1e9f;
            float[] col = pushKept ? RED : GREEN;
            wline(pushX0, pushY0, pushNx, pushNy, WHITE, a, 1);
            wline(pushNx, pushNy, pushRx, pushRy, col, a, 3);
            wcross(pushNx, pushNy, 0.06, col, a);
        }
        // You, body, velocity, facing.
        wcircle(px, py, 0.3, WHITE, 1f, 16);
        wcross(px, py, 0.04, WHITE, 1f);
        double vx = Mover.velX(), vy = Mover.velY();
        wline(px, py, px + vx * 0.25, py + vy * 0.25, GREEN, 1f, 2);
        wline(px, py, px + fx * 0.45, py + fy * 0.45, WHITE, 0.5f, 1);

        clip(-1e9, -1e9, 1e9, 1e9);
        text(x0 + 4, y0 + 2, String.format("top view  z %.2f (level %d +%.2f)", feet, level, rel), WHITE, 1f);
        text(x0 + 4, y0 + MAP - 16, "on " + Floors.floorName(p), WHITE, 0.8f);
    }

    /** Solid squares by whether you can get in from here, and prop shapes. */
    private static void squareView(IsoCell cell, IsoGridSquare sq, int gx, int gy, int level, double rel) {
        boolean solid = sq.isSolid() || sq.isSolidTrans();
        if (Water.open(sq)) {
            wbox(gx + 0.08, gy + 0.08, gx + 0.92, gy + 0.92, BLUE, 0.8f, 1);
            wtext(gx + 0.5, gy + 0.3, "water", BLUE);
        } else if (solid) {
            Props.Prop ramp = Props.ramp(sq);
            double entry = Props.entryTop(sq);
            float[] col;
            String label;
            if (ramp != null) {
                col = YELLOW;
                label = String.format("tent %.2f", ramp.entryTop);
            } else if (Double.isNaN(entry)) {
                col = RED;
                label = "solid";
            } else {
                col = rel >= entry - Ledges.STEP_UP ? GREEN : ORANGE;
                label = String.format("in %.2f", entry);
            }
            // The grid blocks the whole square.
            wbox(gx + 0.03, gy + 0.03, gx + 0.97, gy + 0.97, col, 0.9f, 2);
            wtext(gx + 0.5, gy + 0.18, label, col);
        }
        if (sq.HasStairs()) wtext(gx + 0.5, gy + 0.5, "stairs", CYAN);
        // Prop shapes, with footing margin (dim).
        PZArrayList<IsoObject> objects = sq.getObjects();
        for (int i = 0; i < objects.size(); i++) {
            Props.Prop prop = Props.prop(objects.get(i));
            if (!Props.jumpable(prop) || prop.rampAngle > 0) continue;
            for (Props.Shape s : prop.shapes) {
                float[] col = rel >= s.top - Ledges.STEP_UP ? GREEN : YELLOW;
                double m = s.thin() ? Math.max(0.15, Cfg.fenceFooting()) : 0.15;
                shape(gx + 0.5, gy + 0.5, s, 0, col, 1f);
                shape(gx + 0.5, gy + 0.5, s, m, col, 0.3f);
                wtext(gx + 0.5 + s.cx, gy + 0.5 + s.cy - 0.05, String.format("%.2f", s.top), col);
            }
        }
    }

    /** Edge colored by what's on it. */
    private static void edgeView(IsoGridSquare sq, int gx, int gy, boolean north, double rel) {
        double h = Ledges.edgeHeight(sq, north);
        if (h <= 0) return;
        float[] col;
        if (Windows.on(sq, north) != null) col = CYAN;
        else if (Rails.stairs(sq, north) != null) col = MAGENTA;
        else if (h >= Ledges.FULL) col = RED;
        else col = h >= Ledges.TALL_FENCE ? ORANGE : YELLOW;
        if (north) wline(gx, gy, gx + 1, gy, col, 1f, 3);
        else wline(gx, gy, gx, gy + 1, col, 1f, 3);
        if (h < Ledges.FULL) {
            wtext(north ? gx + 0.5 : gx, north ? gy - 0.12 : gy + 0.4, String.format("%.2f", h), rel >= h - Ledges.STEP_UP ? GREEN : col);
        }
    }

    private static void shape(double ox, double oy, Props.Shape s, double margin, float[] col, float a) {
        if (s.round) {
            wcircle(ox + s.cx, oy + s.cy, s.r + margin, col, a, 14);
            return;
        }
        double hx = s.hx + margin, hy = s.hy + margin;
        double[] lx = {-hx, hx, hx, -hx}, ly = {-hy, -hy, hy, hy};
        double prevX = 0, prevY = 0, firstX = 0, firstY = 0;
        for (int i = 0; i < 4; i++) {
            // Inverse of Shape.contains' rotation.
            double wx = ox + s.cx + lx[i] * s.cos + ly[i] * s.sin;
            double wy = oy + s.cy - lx[i] * s.sin + ly[i] * s.cos;
            if (i == 0) {
                firstX = wx;
                firstY = wy;
            } else {
                wline(prevX, prevY, wx, wy, col, a, 1);
            }
            prevX = wx;
            prevY = wy;
        }
        wline(prevX, prevY, firstX, firstY, col, a, 1);
    }

    private static void quad(VehiclePoly q, float[] col, float a) {
        if (q == null) return;
        wline(q.x1, q.y1, q.x2, q.y2, col, a, 1);
        wline(q.x2, q.y2, q.x3, q.y3, col, a, 1);
        wline(q.x3, q.y3, q.x4, q.y4, col, a, 1);
        wline(q.x4, q.y4, q.x1, q.y1, col, a, 1);
    }

    /** Forward is up. */
    private static double mapX(double wx, double wy) {
        double dx = wx - px, dy = wy - py;
        return cx + (dx * -fy + dy * fx) * scale;
    }

    private static double mapY(double wx, double wy) {
        double dx = wx - px, dy = wy - py;
        return cy - (dx * fx + dy * fy) * scale;
    }

    private static void wline(double ax, double ay, double bx, double by, float[] c, float a, int w) {
        line(mapX(ax, ay), mapY(ax, ay), mapX(bx, by), mapY(bx, by), c, a, w);
    }

    private static void wbox(double ax, double ay, double bx, double by, float[] c, float a, int w) {
        wline(ax, ay, bx, ay, c, a, w);
        wline(bx, ay, bx, by, c, a, w);
        wline(bx, by, ax, by, c, a, w);
        wline(ax, by, ax, ay, c, a, w);
    }

    private static void wcross(double x, double y, double r, float[] c, float a) {
        wline(x - r, y - r, x + r, y + r, c, a, 1);
        wline(x - r, y + r, x + r, y - r, c, a, 1);
    }

    private static void wcircle(double x, double y, double r, float[] c, float a, int n) {
        for (int i = 0; i < n; i++) {
            double a0 = 2 * Math.PI * i / n, a1 = 2 * Math.PI * (i + 1) / n;
            wline(x + r * Math.cos(a0), y + r * Math.sin(a0), x + r * Math.cos(a1), y + r * Math.sin(a1), c, a, 1);
        }
    }

    private static void wtext(double wx, double wy, String s, float[] c) {
        double x = mapX(wx, wy), y = mapY(wx, wy);
        if (x < clipX0 || x > clipX1 - 10 || y < clipY0 || y > clipY1 - 12) return;
        int w = TextManager.instance.MeasureStringX(UIFont.Small, s);
        text(x - w / 2.0, y - 7, s, c, 0.9f);
    }


    private static double sx0, sy0, ppt, ppl, zLow;

    private static double sideX(double t) {
        return sx0 + (t + SIDE_BACK) * ppt;
    }

    private static double sideY(double z) {
        return sy0 + SIDE_H - (z - zLow) * ppl;
    }

    private static void sline(double t0, double z0, double t1, double z1, float[] c, float a, int w) {
        line(sideX(t0), sideY(z0), sideX(t1), sideY(z1), c, a, w);
    }

    private static void sideView(IsoPlayer p, IsoCell cell, double x0, double y0) {
        SpriteRenderer.instance.renderRect((int) x0, (int) y0, SIDE_W, SIDE_H, 0f, 0f, 0f, 0.6f);
        clip(x0, y0, x0 + SIDE_W, y0 + SIDE_H);
        double feet = p.getZ();
        int level = (int) Math.floor(feet);
        sx0 = x0;
        sy0 = y0;
        zLow = level - SIDE_BELOW;
        ppt = SIDE_W / (SIDE_BACK + SIDE_AHEAD);
        ppl = SIDE_H / (SIDE_BELOW + SIDE_ABOVE);
        double vx = Mover.velX(), vy = Mover.velY(), speed = Math.hypot(vx, vy);
        double ux, uy;
        if (speed > 0.3) {
            ux = vx / speed;
            uy = vy / speed;
        } else {
            double len = Math.hypot(p.getForwardDirectionX(), p.getForwardDirectionY());
            ux = len > 1e-4 ? p.getForwardDirectionX() / len : 0;
            uy = len > 1e-4 ? p.getForwardDirectionY() / len : -1;
            speed = 0;
        }
        final double dirX = ux, dirY = uy;
        double ox = p.getX(), oy = p.getY();

        sline(-SIDE_BACK, level, SIDE_AHEAD, level, GREY, 0.5f, 1);
        sline(-SIDE_BACK, level + 1, SIDE_AHEAD, level + 1, GREY, 0.3f, 1);

        // Floor along the way.
        int n = 140;
        double prevT = 0, prevZ = 0;
        for (int i = 0; i <= n; i++) {
            double t = -SIDE_BACK + (SIDE_AHEAD + SIDE_BACK) * i / n;
            double z = floorAt(cell, ox + ux * t, oy + uy * t, level);
            if (i > 0) {
                sline(prevT, prevZ, t, prevZ, GREEN, 0.9f, 2);
                if (z != prevZ) sline(t, prevZ, t, z, GREEN, 0.9f, 2);
            }
            prevT = t;
            prevZ = z;
        }

        // Edges and solid-square entry heights at each boundary.
        double ex = ox + ux * SIDE_AHEAD, ey = oy + uy * SIDE_AHEAD;
        double bx = ox - ux * SIDE_BACK, by = oy - uy * SIDE_BACK;
        Physics.gridWalk(bx, by, ex, ey, 16, (ax, ay, cx2, cy2) -> {
            double t = crossingT(bx, by, dirX, dirY, ax, ay, cx2, cy2) - SIDE_BACK;
            double h = Ledges.crossingHeight(cell, level, ax, ay, cx2, cy2, ox + dirX * t, oy + dirY * t);
            IsoObject window = Windows.between(cell, level, ax, ay, cx2, cy2);
            if (window != null) {
                sline(t, level, t, level + Windows.SILL, CYAN, 1f, 3);
                sline(t, level + Windows.TOP, t, level + 1, CYAN, 1f, 3);
            } else if (h > 0) {
                sline(t, level, t, level + h, h >= Ledges.FULL ? RED : ORANGE, 1f, 3);
            }
            IsoGridSquare target = cell.getGridSquare(cx2, cy2, level);
            if (target == null) {
                stext(t + 0.05, level + 0.95, "no square", PURPLE);
            } else if ((target.isSolid() || target.isSolidTrans()) && Props.ramp(target) == null) {
                double entry = Props.entryTop(target);
                double top = Double.isNaN(entry) ? Ledges.FULL : entry;
                sline(t + 0.03, level, t + 0.03, level + top, Double.isNaN(entry) ? RED : ORANGE, 0.8f, 1);
                stext(t + 0.06, level + Math.min(top, 1.2) + 0.08, Double.isNaN(entry) ? "solid" : String.format("in %.2f", entry),
                        Double.isNaN(entry) ? RED : ORANGE);
            }
            return true;
        });

        // Body and the step-up line.
        sline(-0.3, feet, 0.3, feet, WHITE, 1f, 1);
        sline(-0.3, feet + 0.73, 0.3, feet + 0.73, WHITE, 1f, 1);
        sline(-0.3, feet, -0.3, feet + 0.73, WHITE, 1f, 1);
        sline(0.3, feet, 0.3, feet + 0.73, WHITE, 1f, 1);
        sline(-SIDE_BACK, feet + Ledges.STEP_UP, SIDE_AHEAD, feet + Ledges.STEP_UP, WHITE, 0.3f, 1);

        // Jump arc.
        double vz = -p.getLastFallSpeed();
        if (!Mover.grounded || vz > 0) {
            double g = FallingConstants.IsoFallAcceleration;
            double t0 = 0, z0 = feet;
            for (int i = 1; i <= 40; i++) {
                double tau = 1.2 * i / 40;
                double t = speed * tau, z = feet + vz * tau - 0.5 * g * tau * tau;
                sline(t0, z0, t, z, CYAN, 0.9f, 1);
                if (t > SIDE_AHEAD || z < zLow) break;
                t0 = t;
                z0 = z;
            }
        }

        clip(-1e9, -1e9, 1e9, 1e9);
        text(x0 + 4, y0 + 2, String.format("side view along %s  %.1f tiles/s  vz %.2f lv/s",
                speed > 0 ? "velocity" : "facing", speed, vz), WHITE, 1f);
    }

    /** Distance along the line where it crosses from a to b. */
    private static double crossingT(double x0, double y0, double ux, double uy, int ax, int ay, int bx, int by) {
        if (ax != bx && Math.abs(ux) > 1e-6) return (Math.max(ax, bx) - x0) / ux;
        if (ay != by && Math.abs(uy) > 1e-6) return (Math.max(ay, by) - y0) / uy;
        return 0;
    }

    /** No floor at this level. */
    private static boolean open(IsoGridSquare sq, int level) {
        return sq == null || level > 0 && !sq.TreatAsSolidFloor() && !sq.HasStairs() && !sq.hasSlopedSurface();
    }

    /** Highest standable thing at (x, y), or below where there's no square. */
    private static double floorAt(IsoCell cell, double x, double y, int level) {
        IsoGridSquare sq = cell.getGridSquare((int) Math.floor(x), (int) Math.floor(y), level);
        double best;
        if (open(sq, level)) {
            best = level > 0 ? level - 1 : level;
        } else {
            best = sq.getApparentZ((float) (x - sq.x), (float) (y - sq.y));
        }
        best = Math.max(best, Props.topUnder(cell, x, y, level, 1e9, 0, 0));
        best = Math.max(best, Ledges.fenceTopUnder(cell, x, y, level, 0.05));
        if (Rides.roofUnder(cell, (float) x, (float) y, 1e9, 0f, 0, roofOut) != null) best = Math.max(best, roofOut[0]);
        return best;
    }

    private static void stext(double t, double z, String s, float[] c) {
        double x = sideX(t), y = sideY(z);
        if (x < clipX0 || x > clipX1 - 20 || y < clipY0 || y > clipY1 - 12) return;
        text(x, y - 12, s, c, 0.9f);
    }


    private static void logView(double x0, double y0) {
        long now = System.nanoTime();
        double y = y0;
        text(x0, y, "collision log (newest first)", WHITE, 1f);
        y += 15;
        for (int i = 1; i <= LOG; i++) {
            int k = (logHead - i + LOG * 2) % LOG;
            if (logText[k] == null) break;
            double age = (now - logAt[k]) / 1e9;
            float a = (float) Math.max(0.45, 1 - age / 20);
            String count = logCount[k] > 1 ? " (x" + logCount[k] + ")" : "";
            text(x0, y, String.format("%5.1fs  %s%s", age, logText[k], count), WHITE, a);
            y += 15;
        }
    }


    private static void clip(double x0, double y0, double x1, double y1) {
        clipX0 = x0;
        clipY0 = y0;
        clipX1 = x1;
        clipY1 = y1;
    }

    /** Screen line clipped to the panel (Liang-Barsky). */
    private static void line(double x0, double y0, double x1, double y1, float[] c, float a, int w) {
        double t0 = 0, t1 = 1, dx = x1 - x0, dy = y1 - y0;
        double[] p = {-dx, dx, -dy, dy}, q = {x0 - clipX0, clipX1 - x0, y0 - clipY0, clipY1 - y0};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) return;
            } else {
                double r = q[i] / p[i];
                if (p[i] < 0) {
                    if (r > t1) return;
                    if (r > t0) t0 = r;
                } else {
                    if (r < t0) return;
                    if (r < t1) t1 = r;
                }
            }
        }
        SpriteRenderer.instance.renderlinef(null, (float) (x0 + dx * t0), (float) (y0 + dy * t0),
                (float) (x0 + dx * t1), (float) (y0 + dy * t1), c[0], c[1], c[2], a, w);
    }

    private static void text(double x, double y, String s, float[] c, float a) {
        TextManager.instance.DrawString(UIFont.Small, x, y, s, c[0], c[1], c[2], a);
    }
}

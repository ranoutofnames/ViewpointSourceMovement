package sourcemove;

/** Quake/Source movement math, pure so it can be tested without the game. */
public final class Physics {
    private Physics() {}

    public static final class Vel {
        public double x, y;

        public double speed() {
            return Math.hypot(x, y);
        }
    }

    /** Ground friction; stopSpeed makes slow motion stop crisply. */
    public static void friction(Vel v, double friction, double stopSpeed, double dt) {
        double speed = v.speed();
        if (speed < 1e-6) {
            v.x = v.y = 0;
            return;
        }
        double control = Math.max(speed, stopSpeed);
        double newSpeed = Math.max(0, speed - control * friction * dt);
        double scale = newSpeed / speed;
        v.x *= scale;
        v.y *= scale;
    }

    public static void accelerate(Vel v, double wishX, double wishY, double wishSpeed, double accel, double dt) {
        double addSpeed = wishSpeed - (v.x * wishX + v.y * wishY);
        if (addSpeed <= 0) return;
        double accelSpeed = Math.min(accel * dt * wishSpeed, addSpeed);
        v.x += accelSpeed * wishX;
        v.y += accelSpeed * wishY;
    }

    /** Air acceleration. Only the projected speed is capped, which is what lets strafing gain speed. */
    public static void airAccelerate(Vel v, double wishX, double wishY, double wishSpeed, double accel, double dt, double airCap) {
        double capped = Math.min(wishSpeed, airCap);
        double addSpeed = capped - (v.x * wishX + v.y * wishY);
        if (addSpeed <= 0) return;
        double accelSpeed = Math.min(accel * wishSpeed * dt, addSpeed);
        v.x += accelSpeed * wishX;
        v.y += accelSpeed * wishY;
    }

    /** Source ClipVelocity against a ramp rising along (ux, uy); returns the new vertical speed. */
    public static double clipRamp(Vel v, double vz, double ux, double uy, double angle) {
        double s = Math.sin(angle), c = Math.cos(angle);
        double nx = -ux * s, ny = -uy * s, nz = c;
        double backoff = v.x * nx + v.y * ny + vz * nz;
        if (backoff >= 0) return vz;
        v.x -= backoff * nx;
        v.y -= backoff * ny;
        return vz - backoff * nz;
    }

    public static double jumpSpeed(double apex, double g) {
        return Math.sqrt(2 * g * Math.max(0, apex));
    }

    /** One step of gridWalk; false refuses it. */
    public interface Step {
        boolean ok(int ax, int ay, int bx, int by);
    }

    /** Squares a segment crosses (Amanatides-Woo), diagonal through exact corners; false if refused or too long. */
    public static boolean gridWalk(double x0, double y0, double x1, double y1, int maxSteps, Step step) {
        int x = (int) Math.floor(x0), y = (int) Math.floor(y0);
        int ex = (int) Math.floor(x1), ey = (int) Math.floor(y1);
        double dx = x1 - x0, dy = y1 - y0;
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1;
        double tdx = dx != 0 ? Math.abs(1 / dx) : Double.POSITIVE_INFINITY;
        double tdy = dy != 0 ? Math.abs(1 / dy) : Double.POSITIVE_INFINITY;
        double tx = dx != 0 ? (dx > 0 ? x + 1 - x0 : x0 - x) * tdx : Double.POSITIVE_INFINITY;
        double ty = dy != 0 ? (dy > 0 ? y + 1 - y0 : y0 - y) * tdy : Double.POSITIVE_INFINITY;
        for (int n = 0; x != ex || y != ey; n++) {
            if (n >= maxSteps) return false;
            int nx = x, ny = y;
            if (Math.abs(tx - ty) < 1e-9) {
                nx += sx;
                ny += sy;
                tx += tdx;
                ty += tdy;
            } else if (tx < ty) {
                nx += sx;
                tx += tdx;
            } else {
                ny += sy;
                ty += tdy;
            }
            if (!step.ok(x, y, nx, ny)) return false;
            x = nx;
            y = ny;
        }
        return true;
    }

    /** Impact speed of a fall safeDrop shorter. */
    public static double forgiveDrop(double impactSpeed, double g, double safeDrop) {
        if (impactSpeed <= 0) return impactSpeed;
        double height = impactSpeed * impactSpeed / (2 * g) - safeDrop;
        return height <= 0 ? 0 : Math.sqrt(2 * g * height);
    }

    public static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }
}

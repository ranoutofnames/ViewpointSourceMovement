package sourcemove;

/** Run: java -cp build/classes;build/test sourcemove.PhysicsTest */
public final class PhysicsTest {
    private static int failures;

    public static void main(String[] args) {
        groundAccelCapsAtWishSpeed();
        frictionStops();
        airStrafeGainsSpeed();
        airForwardDoesNotGain();
        jumpApex();
        bhopSequences();
        parkourChain();
        reasonableFalls();
        gridWalks();
        rampClips();
        if (failures > 0) {
            System.out.println(failures + " FAILURE(S)");
            System.exit(1);
        }
        System.out.println("all physics tests passed");
    }

    static void groundAccelCapsAtWishSpeed() {
        Physics.Vel v = new Physics.Vel();
        double dt = 1.0 / 66;
        for (int i = 0; i < 200; i++) {
            Physics.friction(v, 4, 1.36, dt);
            Physics.accelerate(v, 1, 0, 3.4, 10, dt);
        }
        check("ground speed converges below wish speed", v.speed() > 3.0 && v.speed() <= 3.4 + 1e-9, v.speed());
    }

    static void frictionStops() {
        Physics.Vel v = new Physics.Vel();
        v.x = 3.4;
        double dt = 1.0 / 66;
        int ticks = 0;
        while (v.speed() > 0 && ticks < 1000) {
            Physics.friction(v, 4, 1.36, dt);
            ticks++;
        }
        check("friction stops within ~1 s", ticks < 70, ticks);
    }

    /** Hold strafe while turning the view at the optimal rate: speed must keep rising (bhop/strafe-jump gain). */
    static void airStrafeGainsSpeed() {
        Physics.Vel v = new Physics.Vel();
        v.x = 3.4;
        double dt = 1.0 / 66, wish = 3.4, cap = 0.12 * 3.4;
        for (int i = 0; i < 66 * 3; i++) {
            double heading = Math.atan2(v.y, v.x);
            // Wish direction a bit wider than the cap angle, so the projected speed sits under the cap (optimal-ish strafe).
            double ang = heading + Math.acos(Math.min(1, 0.5 * cap / Math.max(v.speed(), 1e-6)));
            Physics.airAccelerate(v, Math.cos(ang), Math.sin(ang), wish, 100, dt, cap);
        }
        check("3 s of air strafing gains >1.5x speed", v.speed() > 3.4 * 1.5, v.speed());
    }

    static void airForwardDoesNotGain() {
        Physics.Vel v = new Physics.Vel();
        v.x = 3.4;
        double dt = 1.0 / 66;
        for (int i = 0; i < 66; i++) Physics.airAccelerate(v, 1, 0, 3.4, 100, dt, 0.12 * 3.4);
        check("holding forward in the air does not add speed", Math.abs(v.speed() - 3.4) < 1e-9, v.speed());
    }

    static void jumpApex() {
        double g = 5.0010414, v0 = Physics.jumpSpeed(0.35, g);
        // Integrate like the engine's updateFalling at 60 FPS and record the peak.
        double z = 0, v = -v0, dt = 1.0 / 60, peak = 0;
        for (int i = 0; i < 120; i++) {
            double dz = v * dt + 0.5 * g * dt * dt;
            z -= dz;
            v += g * dt;
            peak = Math.max(peak, z);
            if (z < 0) break;
        }
        check("jump apex ~0.35 levels", Math.abs(peak - 0.35) < 0.02, peak);
    }

    /**
     * Whole hop sequences with Mover's defaults (66 tick, friction 4, stopspeed 0.4*run, airaccel 100,
     * air cap 0.12*run, run 3.4 tiles/s, 0.35-level jump = ~49 air ticks). Strafing is the optimal
     * perpendicular wish direction (gain per tick = cap^2 / 2v).
     */
    static void bhopSequences() {
        double perfectNoStrafe = hops(8, 10, 0, false);
        double heldNoStrafe = hops(8, 10, 1, false);
        double heldStrafe = hops(3.4, 40, 1, true);
        double perfectStrafe = hops(3.4, 40, 0, true);
        check("perfectly timed presses, no strafing: keeps speed (Source)", Math.abs(perfectNoStrafe - 8) < 1e-6, perfectNoStrafe);
        check("held autohop, no strafing: loses >30% in 10 hops", heldNoStrafe < 8 * 0.7, heldNoStrafe);
        check("held autohop + strafing: climbs well past sprint (5)", heldStrafe > 7, heldStrafe);
        check("perfect presses + strafing: faster than autohop", perfectStrafe > heldStrafe * 1.3, perfectStrafe);
    }

    static double hops(double start, int n, int groundTicks, boolean strafe) {
        double dt = 1.0 / 66, run = 3.4, cap = 0.12 * run, stop = 0.4 * run;
        int airTicks = (int) Math.round(2 * Physics.jumpSpeed(0.35, 5.0010414) / 5.0010414 / dt);
        Physics.Vel v = new Physics.Vel();
        v.x = start;
        for (int h = 0; h < n; h++) {
            for (int i = 0; i < groundTicks; i++) {
                Physics.friction(v, 4, stop, dt);
                Physics.accelerate(v, v.x / v.speed(), v.y / v.speed(), run, 10, dt);
            }
            for (int i = 0; i < airTicks; i++) {
                if (!strafe) continue; // holding W along velocity adds nothing, same as no input
                double ang = Math.atan2(v.y, v.x) + Math.PI / 2;
                Physics.airAccelerate(v, Math.cos(ang), Math.sin(ang), run, 100, dt, cap);
            }
        }
        System.out.printf("    %2d hops, %d ground tick(s)/hop, strafe=%-5s: %.2f -> %.2f tiles/s%n", n, groundTicks, strafe, start, v.speed());
        return v.speed();
    }

    /** Mirrors Ledges/Cfg: low fence 0.40, tall fence 0.85, roof 1.0; jump 0.55; tired -8%/level. */
    static void parkourChain() {
        double jump = 0.55, low = 0.40, tall = 0.85, roof = 1.0;
        check("ground -> low fence", 0 + jump > low + 0.1, jump);
        check("ground -> tall fence is out of reach", 0 + jump < tall, jump);
        check("low fence -> tall fence", low + jump > tall + 0.05, low + jump);
        check("tall fence -> one-storey roof", tall + jump > roof + 0.3, tall + jump);
        double tired2 = jump * (1 - 0.08 * 2);
        check("Tired level 2 still makes low -> tall", low + tired2 > tall, low + tired2);
        double tired3 = jump * (1 - 0.08 * 3);
        check("Tired level 3 can't make low -> tall", low + tired3 < tall, low + tired3);
    }

    /** Vanilla: damage starts at 0.5 levels of fall; reasonable mode forgives 1.0 more by default. */
    static void reasonableFalls() {
        double g = 5.0010414, safe = 1.0;
        double noDamage = Math.sqrt(2 * g * 0.5);
        check("jump off a tall fence (1.40 lv drop): no damage", Physics.forgiveDrop(Math.sqrt(2 * g * 1.40), g, safe) < noDamage, 1.40);
        double roofJump = Physics.forgiveDrop(Math.sqrt(2 * g * 1.55), g, safe);
        check("jump off a roof (1.55 lv drop): barely damaging", roofJump > noDamage && roofJump < Math.sqrt(2 * g * 0.6), roofJump);
        check("three storeys (3 lv) still hurts", Physics.forgiveDrop(Math.sqrt(2 * g * 3), g, safe) > Math.sqrt(2 * g * 1.5), 3);
    }

    private static void check(String name, boolean ok, Object got) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + " (got " + got + ")");
        if (!ok) failures++;
    }

    static String walk(double x0, double y0, double x1, double y1) {
        StringBuilder sb = new StringBuilder();
        boolean ok = Physics.gridWalk(x0, y0, x1, y1, 16, (ax, ay, bx, by) -> {
            sb.append(ax).append(',').append(ay).append('>').append(bx).append(',').append(by).append(' ');
            return true;
        });
        return (ok ? "" : "FAIL ") + sb.toString().trim();
    }

    static void gridWalks() {
        String s;
        s = walk(0.5, 0.5, 2.5, 0.5);
        check("walk east two squares", s.equals("0,0>1,0 1,0>2,0"), s);
        s = walk(0.5, 0.5, 1.5, 1.5);
        check("walk through a corner is one diagonal step", s.equals("0,0>1,1"), s);
        s = walk(0.2, 0.5, 0.8, 2.5);
        check("walk south two squares", s.equals("0,0>0,1 0,1>0,2"), s);
        s = walk(2.5, 2.5, 0.5, 1.7);
        check("walk west and north", s.equals("2,2>1,2 1,2>1,1 1,1>0,1"), s);
        s = walk(3.4, 3.4, 3.6, 3.9);
        check("same square: no steps", s.isEmpty(), s);
        s = walk(0.5, 0.5, 30.5, 0.5);
        check("too long a walk is refused", s.startsWith("FAIL"), s);
        boolean refused = !Physics.gridWalk(0.5, 0.5, 3.5, 0.5, 16, (ax, ay, bx, by) -> bx != 2);
        check("a refused step stops the walk", refused, refused);
    }

    static void rampClips() {
        Physics.Vel v = new Physics.Vel();
        v.x = 10;
        double vz = Physics.clipRamp(v, 0, 1, 0, Math.toRadians(45));
        check("45 deg ramp at 10: lift v sin cos = 5", Math.abs(vz - 5) < 1e-9, vz);
        check("45 deg ramp at 10: keeps v cos^2 = 5 forward", Math.abs(v.x - 5) < 1e-9, v.x);
        v.x = -10;
        vz = Physics.clipRamp(v, 0, 1, 0, Math.toRadians(45));
        check("running down a ramp: no lift", vz == 0 && v.x == -10, vz);
        v.x = 10;
        vz = Physics.clipRamp(v, 20, 1, 0, Math.toRadians(30));
        check("jumping steeper than the ramp: unchanged", vz == 20 && v.x == 10, vz);
        // PZ stairs: a level (2.449 m) over three 1 m tiles, ~39 deg. Bhop speed 10 tiles/s:
        double stairs = Math.atan(2.44949 / 3);
        v.x = 10;
        vz = Physics.clipRamp(v, 0, 1, 0, stairs) / 2.44949;
        check("stairs at 10 tiles/s launch (above 140 u/s = 1.09 lv/s)", vz > 1.09, vz);
        v.x = 5;
        vz = Physics.clipRamp(v, 0, 1, 0, stairs) / 2.44949;
        check("stairs at sprint speed (5) stay on the ground", vz < 1.09, vz);
    }
}

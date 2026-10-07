package sourcemove;

/** Runtime settings. Mod Options are pushed from Lua, Sandbox options are read live, the rest is fixed tuning. */
public final class Cfg {
    private Cfg() {}

    public static volatile boolean enabled = true;
    /** Only while Viewpoint's first-person or over-the-shoulder camera is on. */
    public static volatile boolean fpOnly = true;

    public static final int FALL_NONE = 0, FALL_REASONABLE = 1, FALL_VANILLA = 2;
    /** NONE has no fall damage, REASONABLE only past safeDrop, VANILLA is untouched. */
    public static int fallMode() { return (int) Sandbox.num(Sandbox.P + "fallMode", FALL_REASONABLE + 1) - 1; }
    /** Free drop height (levels) in REASONABLE, on top of vanilla's 0.5. */
    public static double safeDrop() { return Sandbox.num(Sandbox.P + "safeDrop", 1.0); }
    /** Hard landings still knock you to your knees in REASONABLE. */
    public static boolean fallKnockdown() { return Sandbox.bool(Sandbox.P + "fallKnockdown", true); }

    public static volatile int jumpKey = 57; // Space
    /** Holding jump re-hops; off = Source, a fresh press on the ground each time. */
    public static boolean autohop() { return Sandbox.bool(Sandbox.P + "autohop", false); }
    /** Ground ticks paid per held-jump hop; 0 = free. */
    public static final int autohopGroundTicks = 0;
    public static volatile boolean wheelJump = false;
    /** How long an air press is kept for the landing; 0 = Source. */
    public static double jumpBufferMs() { return Sandbox.num(Sandbox.P + "jumpBufferMs", 60); }
    public static volatile boolean jumpAnim = true;
    public static final boolean stableJumpCam = true;
    /** How far from a fence line you can stand on it (tiles). */
    public static double fenceFooting() { return Sandbox.num(Sandbox.P + "fenceFooting", 0.35); }
    /** Added to car roof height (levels). */
    public static double carRoofOffset() { return Sandbox.num(Sandbox.P + "carRoofOffset", -0.10); }
    public static boolean windowJump() { return Sandbox.bool(Sandbox.P + "windowJump", true); }
    /** Smash closed windows at windowCrashSpeed or faster. */
    public static boolean windowCrash() { return Sandbox.bool(Sandbox.P + "windowCrash", true); }
    public static double windowCrashSpeed() { return Sandbox.num(Sandbox.P + "windowCrashSpeed", 5.5); }
    public static boolean windowDamage() { return Sandbox.bool(Sandbox.P + "windowDamage", true); }
    /** Coming down on barbed wire cuts and trips you. */
    public static boolean barbedWire() { return Sandbox.bool(Sandbox.P + "barbedWire", true); }
    /** Where solid props are jumpable, off, outdoors or everywhere. */
    public static final int propMode = 2;

    /** Endurance per jump in bat swings; 0.125 is a quarter of a knife stab. */
    public static double jumpExertion() { return Sandbox.num(Sandbox.P + "jumpExertion", 0.125); }
    /** Carried weight raises jump cost. */
    public static boolean exertionWeight() { return Sandbox.bool(Sandbox.P + "exertionWeight", true); }
    /** -8% jump height per Endurance moodle level. */
    public static boolean tiredJumps() { return Sandbox.bool(Sandbox.P + "tiredJumps", false); }
    /** -10% jump height per Heavy Load moodle level. */
    public static boolean heavyJumps() { return Sandbox.bool(Sandbox.P + "heavyJumps", false); }
    public static boolean exhaustedNoJump() { return Sandbox.bool(Sandbox.P + "exhaustedNoJump", true); }

    /** Landing sounds, takeoff footstep, silent feet in the air. */
    public static boolean sounds() { return Sandbox.bool(Sandbox.P + "sounds", true); }

    /** Horizontal physics ticks per second (Source 64-128). */
    public static double tickrate() { return Sandbox.num(Sandbox.P + "tickrate", 66); }
    public static double accelerate() { return Sandbox.num(Sandbox.P + "accelerate", 10); }
    public static double airAccelerate() { return Sandbox.num(Sandbox.P + "airAccelerate", 100); }
    public static double friction() { return Sandbox.num(Sandbox.P + "friction", 4); }
    /** stopspeed as a fraction of run speed (Source 100/250). */
    public static final double stopSpeedRatio = 0.4;
    /** Air wish-speed cap as a fraction of run speed (Source 30/250). */
    public static final double airCapRatio = 0.12;
    /** Jump apex (levels); 0.55 clears a low fence from the ground, a roof from a tall fence. */
    public static double jumpHeight() { return Sandbox.num(Sandbox.P + "jumpHeight", 0.55); }
    /** Running into stairs, slopes or tent sides clips velocity up the ramp; above 140 u/s you launch. */
    public static boolean trimp() { return Sandbox.bool(Sandbox.P + "trimp", true); }
    /** Horizontal speed cap (tiles/s); 0 = none. */
    public static double maxSpeed() { return Sandbox.num(Sandbox.P + "maxSpeed", 0); }
    /** Head height above the feet (levels), for ceilings. */
    public static final double headroom = 0.72;

    /** How high above a zombie you can still be grabbed (levels); vanilla's 0.2 made any jump safe. */
    public static double zombieReach() { return Sandbox.num(Sandbox.P + "zombieReach", 0.45); }

    public static boolean pulldown() { return Sandbox.bool(Sandbox.P + "pulldown", true); }
    /** Zombies in reach needed before a pull-down. */
    public static int pulldownGroup() { return (int) Sandbox.num(Sandbox.P + "pulldownGroup", 2); }
    /** Pull-down rate per second once enough are in reach; each extra zombie adds as much again. */
    public static final double pulldownRate = 1.5;
    /** Zombies thump the prop you stand on until it breaks. */
    public static boolean propBreak() { return Sandbox.bool(Sandbox.P + "propBreak", true); }
    /** Grab range (tiles); vanilla attack range is 0.72. */
    public static final double pulldownRange = 0.8;
    /** Speed kept when grabbed. */
    public static double pulldownKeep() { return Sandbox.num(Sandbox.P + "pulldownKeep", 0.2); }
    /** Seconds before you can jump again after a grab. */
    public static double pulldownLockout() { return Sandbox.num(Sandbox.P + "pulldownLockout", 0.6); }

    /** MP on-foot speed limit (tiles/s) for mod users, instead of the anti-cheat's 20; 0 = none. */
    public static double mpSpeedLimit() { return Sandbox.num(Sandbox.P + "mpSpeedLimit", 60); }

    /** Collision wireframe overlay and log. */
    public static volatile boolean wireHud = false;
}

package sourcemove;

/** Runtime settings, pushed from Lua (Mod Options per player, Sandbox for gameplay). */
public final class Cfg {
    private Cfg() {}

    public static volatile boolean enabled = true;
    /** Only while Viewpoint's first-person or over-the-shoulder camera is on. */
    public static volatile boolean fpOnly = true;

    public static final int FALL_NONE = 0, FALL_REASONABLE = 1, FALL_VANILLA = 2;
    /** NONE has no fall damage, REASONABLE only past safeDrop, VANILLA is untouched. */
    public static volatile int fallMode = FALL_REASONABLE;
    /** Free drop height (levels) in REASONABLE, on top of vanilla's 0.5. */
    public static volatile double safeDrop = 1.0;
    /** Hard landings still knock you to your knees in REASONABLE. */
    public static volatile boolean fallKnockdown = true;

    public static volatile int jumpKey = 57; // Space
    /** Holding jump re-hops; off = Source, a fresh press on the ground each time. */
    public static volatile boolean autohop = false;
    /** Ground ticks paid per held-jump hop; 0 = free. */
    public static volatile int autohopGroundTicks = 0;
    public static volatile boolean wheelJump = false;
    /** How long an air press is kept for the landing; 0 = Source. */
    public static volatile double jumpBufferMs = 60;
    public static volatile boolean jumpAnim = true;
    public static volatile boolean stableJumpCam = true;
    /** How far from a fence line you can stand on it (tiles). */
    public static volatile double fenceFooting = 0.35;
    /** Added to car roof height (levels). */
    public static volatile double carRoofOffset = -0.10;
    public static volatile boolean windowJump = true;
    /** Smash closed windows at windowCrashSpeed or faster. */
    public static volatile boolean windowCrash = true;
    public static volatile double windowCrashSpeed = 5.5;
    public static volatile boolean windowDamage = true;
    /** Coming down on barbed wire cuts and trips you. */
    public static volatile boolean barbedWire = true;
    /** Where solid props are jumpable, off, outdoors or everywhere. */
    public static volatile int propMode = 2;

    /** Endurance per jump in bat swings; 0.125 is a quarter of a knife stab. */
    public static volatile double jumpExertion = 0.125;
    /** Carried weight raises jump cost. */
    public static volatile boolean exertionWeight = true;
    /** -8% jump height per Endurance moodle level. */
    public static volatile boolean tiredJumps = false;
    /** -10% jump height per Heavy Load moodle level. */
    public static volatile boolean heavyJumps = false;
    public static volatile boolean exhaustedNoJump = true;

    /** Landing sounds, takeoff footstep, silent feet in the air. */
    public static volatile boolean sounds = true;

    /** Horizontal physics ticks per second (Source 64-128). */
    public static volatile double tickrate = 66;
    public static volatile double accelerate = 10;
    public static volatile double airAccelerate = 100;
    public static volatile double friction = 4;
    /** stopspeed as a fraction of run speed (Source 100/250). */
    public static volatile double stopSpeedRatio = 0.4;
    /** Air wish-speed cap as a fraction of run speed (Source 30/250). */
    public static volatile double airCapRatio = 0.12;
    /** Jump apex (levels); 0.55 clears a low fence from the ground, a roof from a tall fence. */
    public static volatile double jumpHeight = 0.55;
    /** Running into stairs, slopes or tent sides clips velocity up the ramp; above 140 u/s you launch. */
    public static volatile boolean trimp = true;
    /** Horizontal speed cap (tiles/s); 0 = none. */
    public static volatile double maxSpeed = 0;
    /** Head height above the feet (levels), for ceilings. */
    public static volatile double headroom = 0.72;

    /** How high above a zombie you can still be grabbed (levels); vanilla's 0.2 made any jump safe. */
    public static volatile double zombieReach = 0.45;

    public static volatile boolean pulldown = true;
    /** Zombies in reach needed before a pull-down. */
    public static volatile int pulldownGroup = 2;
    /** Pull-down rate per second once enough are in reach; each extra zombie adds as much again. */
    public static volatile double pulldownRate = 1.5;
    /** Grab range (tiles); vanilla attack range is 0.72. */
    public static volatile double pulldownRange = 0.8;
    /** Speed kept when grabbed. */
    public static volatile double pulldownKeep = 0.2;
    /** Seconds before you can jump again after a grab. */
    public static volatile double pulldownLockout = 0.6;

    /** MP on-foot speed limit (tiles/s) for mod users, instead of the anti-cheat's 20; 0 = none. */
    public static volatile double mpSpeedLimit = 60;

    public static volatile boolean debugHud = false;
    /** Collision wireframe overlay and log. */
    public static volatile boolean wireHud = false;
}

package sourcemove;

/**
 * Runtime settings. Pushed from Lua via {@link LuaApi}: per-player preferences from Mod Options, gameplay from
 * Sandbox options (on a server, by its own Lua). Defaults match the Lua defaults.
 */
public final class Cfg {
    private Cfg() {}

    public static volatile boolean enabled = true;
    /** Only take over while Viewpoint's first-person (or its over-the-shoulder) camera is active. */
    public static volatile boolean fpOnly = true;

    public static final int FALL_NONE = 0, FALL_REASONABLE = 1, FALL_VANILLA = 2;
    /**
     * NONE: no fall damage or falling animation. REASONABLE: vanilla damage, but only for the part of a
     * drop beyond {@link #safeDrop}, and no falling animation (you keep air control). VANILLA: untouched.
     */
    public static volatile int fallMode = FALL_REASONABLE;
    /** Extra drop height (Z-levels) that is free in REASONABLE mode, on top of vanilla's own 0.5. */
    public static volatile double safeDrop = 1.0;
    /** REASONABLE mode: hard landings still put you on your knees and drop held items. */
    public static volatile boolean fallKnockdown = true;

    public static volatile int jumpKey = 57; // Keyboard.KEY_SPACE
    /**
     * Holding jump re-hops on landing. Off = Source rules: a jump needs a fresh press made while on the
     * ground (CGameMovement::CheckJumpButton "don't pogo stick"); presses made in the air are ignored.
     */
    public static volatile boolean autohop = false;
    /** Ground ticks (friction + ground accel) simulated before each held-jump hop. 0 = free, like CS:GO autobhop. */
    public static volatile int autohopGroundTicks = 0;
    public static volatile boolean wheelJump = false;
    /** How long an in-air press stays queued for the landing. 0 = Source (air presses don't count). */
    public static volatile double jumpBufferMs = 60;
    /** Play the sprinting fence-leap animation while airborne from a jump. */
    public static volatile boolean jumpAnim = true;
    /** Hold the first-person camera steady while the leap animation plays (it would swing the head bone). */
    public static volatile boolean stableJumpCam = true;
    /** How far from a fence line (tiles) you can be and still stand on it. */
    public static volatile double fenceFooting = 0.35;
    /** Added to the computed car roof height (levels). Default lowered so a full jump lands on a car comfortably. */
    public static volatile double carRoofOffset = -0.10;
    /** Jump through open, broken and empty windows. */
    public static volatile boolean windowJump = true;
    /** Crash through closed windows when moving at least {@link #windowCrashSpeed} tiles/s. */
    public static volatile boolean windowCrash = true;
    public static volatile double windowCrashSpeed = 5.5;
    /** Glass cuts from jumping or crashing through windows. */
    public static volatile boolean windowDamage = true;
    /** Where solid props are jumpable: {@link Props#MODE_OFF}, MODE_OUTDOOR (outside buildings), MODE_ALL. */
    public static volatile int propMode = 2;

    /**
     * Endurance per jump, in long-blunt swings (a baseball bat swing). Swing cost scales with weapon weight;
     * a hunting knife (1.0) is half a bat (2.0), so 0.125 = a quarter of a knife stab.
     */
    public static volatile double jumpExertion = 0.125;
    /** Carried weight raises jump exertion (vanilla scales running cost by load too). */
    public static volatile boolean exertionWeight = true;
    /** Each Endurance moodle level lowers jumps by 8%. */
    public static volatile boolean tiredJumps = false;
    /** Each Heavy Load moodle level lowers jumps by 10%. */
    public static volatile boolean heavyJumps = false;
    /** No jumping at the last Endurance moodle level (exhausted). */
    public static volatile boolean exhaustedNoJump = true;

    /** Surface-aware landing sounds (PZ's LandLight/LandHeavy events), takeoff footstep, no airborne footsteps. */
    public static volatile boolean sounds = true;

    /** Fixed simulation rate for horizontal physics (Source: 64/66/100/128). */
    public static volatile double tickrate = 66;
    public static volatile double accelerate = 10;
    public static volatile double airAccelerate = 100;
    public static volatile double friction = 4;
    /** stopspeed as a fraction of the learned run speed (Source: 100/250). */
    public static volatile double stopSpeedRatio = 0.4;
    /** Air wish-speed cap as a fraction of the learned run speed (Source: 30/250). */
    public static volatile double airCapRatio = 0.12;
    /**
     * Jump apex in Z-levels (1 level = 2.449 m). 0.55 (1.35 m) clears a low fence (0.40) from the ground,
     * a tall fence (0.85) from a low fence, and a one-storey roof (1.0) from a tall fence.
     */
    public static volatile double jumpHeight = 0.55;
    /**
     * Trimping, as in Source: running into stairs, slopes or a small tent's side clips your velocity along
     * the ramp, turning part of it upward; above 140 u/s you leave the ground. Jumps into a ramp are clipped too.
     */
    public static volatile boolean trimp = true;
    /** Horizontal speed cap in tiles/s; 0 = unlimited. */
    public static volatile double maxSpeed = 0;
    /** Top of head above the feet, in Z-levels, used to stop jumps at ceilings. */
    public static volatile double headroom = 0.72;

    /**
     * How far above a zombie your feet can be and still get grabbed/bitten (levels). Vanilla allows 0.2,
     * which made any jump untouchable. 0.45 (1.1 m): low fences aren't safe, tall fences and car roofs are.
     */
    public static volatile double zombieReach = 0.45;

    /** Zombies next to you can grab you out of the air and drag you back down. */
    public static volatile boolean pulldown = true;
    /** How many zombies must be in grab reach before they can pull you down: one alone can't. */
    public static volatile int pulldownGroup = 2;
    /**
     * Grab rate (Poisson, per second airborne) once {@link #pulldownGroup} zombies are in reach; each zombie
     * beyond that adds as much again.
     */
    public static volatile double pulldownRate = 1.5;
    /** How close (tiles, horizontal) a zombie must be to grab you. Vanilla attack range is 0.72. */
    public static volatile double pulldownRange = 0.8;
    /** Fraction of horizontal speed you keep when grabbed. */
    public static volatile double pulldownKeep = 0.2;
    /** Seconds after a grab before you can jump again. */
    public static volatile double pulldownLockout = 0.6;

    /**
     * Multiplayer: the server's on-foot speed limit (tiles/s) for players using this mod, replacing the
     * anti-cheat's fixed 20. 0 = no limit.
     */
    public static volatile double mpSpeedLimit = 60;

    public static volatile boolean debugHud = false;
}

package sourcemove;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import zombie.GameTime;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.CharacterStat;
import zombie.characters.FallingConstants;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.animation.AnimationClip;
import zombie.input.GameKeyboard;
import zombie.input.Mouse;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.Vector2;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.scripting.objects.MoodleType;

/** Source movement for the local player. Input, acceleration, jumping, ground state, in tiles and tiles/s; vertical is the engine's lastFallSpeed. */
public final class Mover {
    private Mover() {}

    static final int SNEAK = 0, WALK = 1, RUN = 2, SPRINT = 3;
    /** States we drive; anything else (climbing, falling, sitting) is vanilla. */
    private static final Set<String> OWNED_STATES = Set.of(
            "idle", "movement", "run", "sprint", "strafe", "aim", "aim-strafe", "aim-sneak",
            "turning", "turning180", "turningAim180", "turningIdle180", "turningMovement180", "slowidleblend");
    /** Also driven in the air, so swinging mid-hop keeps momentum. */
    private static final Set<String> AIR_OWNED_STATES = Set.of(
            "melee", "shove", "shoveAim", "shoveWithFirearm", "shoveWithHandgun", "ranged");

    public static volatile IsoPlayer self;
    /** We drive self this frame. */
    public static volatile boolean owned;

    static boolean baseActive;
    static boolean airborneUnderMod;
    static boolean grounded = true;

    static final Physics.Vel vel = new Physics.Vel();
    private static double tickAcc;
    static long frame;
    private static boolean wasOwned;
    private static float prevX, prevY;
    private static double prevDt;

    private static float wishX, wishY;
    private static long wishFrame = Long.MIN_VALUE / 2;

    private static boolean lastJumpDown, jumpDown, jumpEdge;
    static long jumpQueuedAt;

    private static boolean groundedBeforeFalling = true;
    static float fallSpeedBeforeFalling;
    static boolean jumpedThisAir;
    static long airStartNanos;

    /** Learned ground speed per mode (tiles/s), tuned from root motion. */
    static final double[] maxSpeed = {1.0, 1.9, 3.4, 5.0};
    private static double modeHeldTime;
    private static int lastMode = -1;

    private static final Vector2 tmp = new Vector2();
    private static Field isOnGroundField;
    static String lastState = "";

    /** True to skip vanilla root motion this frame. */
    public static boolean onDeferredMovement(IsoGameCharacter chr) {
        if (!(chr instanceof IsoPlayer p) || !isLocal(p)) return false;
        if (p != self) resetFor(p);
        frame++;

        double dt = GameTime.getInstance().getTimeDelta();
        if (!(dt > 0)) dt = 0;
        dt = Math.min(dt, 0.1);
        Status.measureSpeed(p, dt);

        // MP only once the server has answered.
        baseActive = Cfg.enabled && !GameServer.server && (!GameClient.client || Net.serverReady)
                && (!Cfg.fpOnly || Viewpoint.active());
        pollJumpKey();

        boolean own = computeOwns(p);
        owned = own;
        if (!own) {
            wasOwned = false;
            prevX = p.getX();
            prevY = p.getY();
            prevDt = dt;
            Net.frame(p, false, grounded, false, animOn, null);
            return false;
        }
        if (!wasOwned) {
            // Take over at vanilla's speed so it doesn't snap.
            vel.x = vel.y = 0;
            if (prevDt > 0) {
                vel.x = (p.getX() - prevX) / prevDt;
                vel.y = (p.getY() - prevY) / prevDt;
                double s = vel.speed();
                if (s > 20) { vel.x *= 20 / s; vel.y *= 20 / s; }
            }
            tickAcc = 0;
        }
        wasOwned = true;

        // Eat the root motion and learn speeds from it.
        tmp.set(0, 0);
        if (p.hasAnimationPlayer() && p.getAnimationPlayer() != null) {
            p.getAnimationPlayer().getDeferredMovement(tmp, true);
        }
        boolean wishing = frame - wishFrame <= 1 && (wishX != 0 || wishY != 0);
        int mode = p.isSprinting() ? SPRINT : p.isRunning() ? RUN : p.isSneaking() ? SNEAK : WALK;
        IsoGridSquare under = p.getCurrentSquare();
        // Stair and slope animation pace isn't run speed.
        boolean level = under == null || !(under.HasStairs() || under.hasSlopedSurface());
        learnSpeed(mode, wishing && level, tmp.getLength(), dt);

        double wishSpeed = wishing ? maxSpeed[mode] : 0;
        double runSpeed = maxSpeed[RUN];
        double tick = 1.0 / Math.max(10, Cfg.tickrate);

        handleJump(p, tick, wishSpeed, runSpeed);
        if (grounded && Cfg.trimp) Ramps.tryTrimp(p);
        if (grounded) Zombies.pulledThisAir = false;
        else if (Cfg.pulldown && !Zombies.pulledThisAir) Zombies.tryPulldown(p, dt);
        tickAcc += dt;
        int n = 0;
        while (tickAcc >= tick && n < 64) {
            if (grounded) {
                groundTick(tick, wishSpeed, runSpeed);
            } else if (wishSpeed > 0) {
                Physics.airAccelerate(vel, wishX, wishY, wishSpeed, Cfg.airAccelerate, tick, Cfg.airCapRatio * runSpeed);
            }
            tickAcc -= tick;
            n++;
        }
        if (n == 64) tickAcc = 0;

        if (Cfg.maxSpeed > 0) {
            double s = vel.speed();
            if (s > Cfg.maxSpeed) { vel.x *= Cfg.maxSpeed / s; vel.y *= Cfg.maxSpeed / s; }
        }

        Floors.carry(p, Floors.carryOut);
        double dx = vel.x * dt + Floors.carryOut[0], dy = vel.y * dt + Floors.carryOut[1];
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (cell != null) {
            // Don't walk into unloaded chunks; the engine would snap you back.
            int z = (int) Math.floor(p.getZ());
            if (dx != 0 && cell.getChunkForGridSquare((int) Math.floor(p.getX() + dx), (int) Math.floor(p.getY()), z) == null) {
                vel.x = 0;
                dx = 0;
            }
            if (dy != 0 && cell.getChunkForGridSquare((int) Math.floor(p.getX()), (int) Math.floor(p.getY() + dy), z) == null) {
                vel.y = 0;
                dy = 0;
            }
        }

        prevX = p.getX();
        prevY = p.getY();
        prevDt = dt;
        if (dx != 0 || dy != 0) p.moveUnmodded((float) dx, (float) dy);
        Net.frame(p, true, grounded, Floors.floorKind != Floors.FLOOR_GROUND, animOn, grounded ? Floors.ride : null);
        return true;
    }

    private static boolean computeOwns(IsoPlayer p) {
        String state = p.getCurrentActionContextStateName();
        lastState = state == null ? "?" : state;
        if (!baseActive || state == null) return false;
        if (p.isDead() || p.isAsleep() || p.getVehicle() != null || p.isSeatedInVehicle()) return false;
        if (p.isSitOnGround() || p.isSittingOnFurniture() || p.isClimbing()) return false;
        if (p.isRagdoll() || p.isGrappling() || p.isBeingGrappled()) return false;
        if (p.getPath2() != null || p.hasTimedActions()) return false;
        return OWNED_STATES.contains(state) || (!grounded && AIR_OWNED_STATES.contains(state));
    }

    private static void learnSpeed(int mode, boolean wishing, float rootLen, double dt) {
        if (mode != lastMode || !wishing || !grounded) {
            modeHeldTime = 0;
            lastMode = mode;
            return;
        }
        modeHeldTime += dt;
        // Skip vanilla's 0.55 s speed ramp.
        if (modeHeldTime < 0.7 || dt <= 0 || rootLen <= 0) return;
        double s = rootLen / dt;
        if (s < 0.2 || s > 25) return;
        double rate = s > maxSpeed[mode] ? 3.0 : 0.5;
        maxSpeed[mode] += (s - maxSpeed[mode]) * Math.min(1, rate * dt);
    }

    /** Source ground tick, friction then accelerate. */
    private static void groundTick(double tick, double wishSpeed, double runSpeed) {
        Physics.friction(vel, Cfg.friction, Cfg.stopSpeedRatio * runSpeed, tick);
        if (wishSpeed > 0) Physics.accelerate(vel, wishX, wishY, wishSpeed, Cfg.accelerate, tick);
    }

    /** Polled every frame so press edges survive ownership changes. */
    private static void pollJumpKey() {
        int key = Cfg.jumpKey;
        boolean down = key > 0 && GameKeyboard.isKeyDown(key);
        boolean wheel = Cfg.wheelJump && Mouse.wheelDelta != 0;
        jumpEdge = (down && !lastJumpDown) || wheel;
        jumpDown = down;
        lastJumpDown = down;
        // Source drops air presses; only the optional buffer keeps one.
        if (jumpEdge && !grounded && Cfg.jumpBufferMs > 0) jumpQueuedAt = System.nanoTime();
    }

    /** Source CheckJumpButton, a fresh press on the ground before friction; autohop pays ground ticks per hop. */
    private static void handleJump(IsoPlayer p, double tick, double wishSpeed, double runSpeed) {
        if (!grounded) return;
        long now = System.nanoTime();
        boolean buffered = jumpQueuedAt != 0 && now - jumpQueuedAt <= (long) (Cfg.jumpBufferMs * 1_000_000L);
        boolean pressed = jumpEdge || buffered;
        boolean held = !pressed && Cfg.autohop && jumpDown;
        if (!pressed && !held) return;
        if (now < Zombies.jumpLockUntil || ceilingBlocked(p)) return;

        int tired = p.getMoodles().getMoodleLevel(MoodleType.ENDURANCE);
        if (Cfg.exhaustedNoJump && tired >= 4) return;
        double height = Cfg.jumpHeight;
        if (Cfg.tiredJumps) height *= 1 - 0.08 * tired;
        if (Cfg.heavyJumps) height *= 1 - 0.10 * p.getMoodles().getMoodleLevel(MoodleType.HEAVY_LOAD);

        if (held) {
            for (int i = 0; i < Cfg.autohopGroundTicks; i++) groundTick(tick, wishSpeed, runSpeed);
        }
        // Takeoff step, as in Source.
        if (Cfg.sounds) p.DoFootstepSound(1.0f);
        exert(p);
        Landing.rememberLand(p);

        double v0 = Physics.jumpSpeed(height, FallingConstants.IsoFallAcceleration);
        if (Cfg.trimp && Ramps.groundRamp(p)) v0 += Ramps.slopeLift(); // a jump up a ramp leaves from the slope
        p.setLastFallSpeed((float) -v0);
        float animSpeed = 1;
        if (Cfg.jumpAnim) {
            animSpeed = vaultAnimSpeed(p, 2 * v0 / FallingConstants.IsoFallAcceleration);
            // A/B leap nodes so a quick re-jump restarts the clip.
            jumpAlt = !jumpAlt;
            p.setVariable(ANIM_ALT_VAR, jumpAlt);
            p.setVariable(ANIM_VAR, true);
            p.setVariable(ANIM_SPEED_VAR, animSpeed);
            animOn = true;
            if (Cfg.stableJumpCam) JumpCam.lockCamera(p);
        }
        Net.jumped(Cfg.jumpAnim, animSpeed);
        grounded = false;
        jumpQueuedAt = 0;
        jumpedThisAir = true;
        airStartNanos = now;
        airborneUnderMod = true;
    }

    /** One bat swing's endurance (weight 2 * 0.18 * 0.3 * 0.04). */
    private static final double BAT_SWING_ENDURANCE = 2.0 * 0.18 * 0.3 * 0.04;

    private static void exert(IsoPlayer p) {
        double cost = Cfg.jumpExertion * BAT_SWING_ENDURANCE * p.getFatigueMod()
                * p.getCharacterTraits().getTraitEnduranceLossModifier();
        if (Cfg.exertionWeight && p.getInventory() != null && p.getInventory().getMaxWeight() > 0) {
            cost *= 1 + Math.min(2, p.getInventory().getCapacityWeight() / p.getInventory().getMaxWeight());
        }
        if (cost > 0) p.getStats().remove(CharacterStat.ENDURANCE, (float) cost);
    }

    /** Anim-set variables for our jump nodes (tools/gen_jump_anims.py). */
    static final String ANIM_VAR = "SMJump", ANIM_SPEED_VAR = "SMJumpSpeed", ANIM_ALT_VAR = "SMJumpAlt";
    private static boolean animOn, jumpAlt;
    private static final Map<String, Float> clipSeconds = new HashMap<>();

    /** Vanilla's sprint vault clip for the held weapon. */
    private static String leapClip(IsoGameCharacter c) {
        String weapon = c.getVariableString("Weapon");
        if (weapon == null) return "Bob_ValultOver_Sprint";
        return switch (weapon.toLowerCase()) {
            case "2handed", "spear" -> "Bob_ValultOver_Sprint_Bat";
            case "firearm" -> "Bob_ValultOver_Sprint_Rifle";
            case "handgun" -> "Bob_ValultOver_Sprint_Hgun";
            case "heavy" -> "Bob_ValultOver_Sprint_2H_heavy";
            default -> "Bob_ValultOver_Sprint";
        };
    }

    /** Stretch the leap clip over the airtime. */
    private static float vaultAnimSpeed(IsoGameCharacter c, double airtime) {
        String name = leapClip(c);
        Float seconds = clipSeconds.get(name);
        if (seconds == null) {
            AnimationClip clip = ModelManager.instance.getAnimationClip(name);
            if (clip != null && clip.getDuration() > 0) {
                seconds = clip.getDuration();
                clipSeconds.put(name, seconds);
                Log.info("leap clip " + name + ": " + seconds + " s");
            }
        }
        if (seconds == null || airtime <= 0) return 1;
        return (float) Math.max(0.4, Math.min(3.0, seconds / airtime));
    }

    public static void onInput(IsoPlayer p) {
        if (p != self) return;
        float x = p.playerMoveDir.x, y = p.playerMoveDir.y;
        float len = (float) Math.hypot(x, y);
        if (len > 1e-4f) {
            wishX = x / len;
            wishY = y / len;
        } else {
            wishX = wishY = 0;
        }
        wishFrame = frame;
    }

    /** We own this character's airtime, on now or on when it started. */
    static boolean modAir(IsoGameCharacter c) {
        return c == self && (baseActive || airborneUnderMod);
    }

    /** Hide the falling state so you keep air control. */
    public static boolean fallOverride(IsoGameCharacter c) {
        return modAir(c) && Cfg.fallMode != Cfg.FALL_VANILLA;
    }

    public static void onUpdateFallingEnter(IsoGameCharacter c) {
        if (c != self) return;
        groundedBeforeFalling = grounded;
        fallSpeedBeforeFalling = c.getLastFallSpeed();
        if (fallOverride(c) && c.getLastFallSpeed() < 0 && ceilingBlocked(c)) c.setLastFallSpeed(0);
    }

    public static void onUpdateFallingExit(IsoGameCharacter c) {
        if (c != self) return;
        grounded = readOnGround(c);
        if (groundedBeforeFalling && !grounded && !jumpedThisAir) { // walked off an edge
            airStartNanos = System.nanoTime();
            Landing.rememberLand(c);
            if (Cfg.wireHud && walkedOn != null) {
                Wire.log(String.format("walked off %s at %.2f: no floor within step-up here", walkedOn, c.getZ()));
            }
        }
        walkedOn = Cfg.wireHud && grounded ? Floors.floorName(c) : null;
        boolean air = modAir(c);
        if (!groundedBeforeFalling && grounded && air && Ramps.rampBounce(c)) {
            grounded = false; // landed on a ramp moving up it fast, still flying
        } else if (!groundedBeforeFalling && grounded) {
            if (Cfg.wireHud) Wire.log(String.format("landed on %s at %.2f, falling %.2f lv/s", Floors.floorName(c), c.getZ(), fallSpeedBeforeFalling));
            int landing = Cfg.sounds && air ? Landing.landingKind(fallSpeedBeforeFalling) : 0;
            if (landing > 0) Landing.playLanding(c, landing);
            Net.landed(landing);
            // In the water, not on a prop in it.
            if (air && Floors.floorKind == Floors.FLOOR_GROUND && Water.open(c.getCurrentSquare())) Landing.leaveWater(c);
            jumpedThisAir = false;
            c.setVariable(ANIM_VAR, false);
            animOn = false;
            // Hold through the leap's 0.2 s blend-out.
            if (JumpCam.camLocked) JumpCam.camReleaseAt = System.nanoTime() + 250_000_000L;
        }
        Barbs.update(c, air, grounded, !groundedBeforeFalling && grounded);
        if (fallOverride(c)) {
            c.setbFalling(false);
            c.setFallTime(0);
        }
        airborneUnderMod = air && !grounded;
        Floors.updateRide();
    }

    /** Last frame's floor, for the log. */
    private static String walkedOn;

    /** Never play the falling animation. */
    public static boolean onIsFalling(IsoGameCharacter c, boolean ret) {
        return ret && !(c == self ? fallOverride(c) : Remote.fallsOverridden(c));
    }

    static boolean ceilingBlocked(IsoGameCharacter c) {
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (cell == null) return false;
        double z = c.getZ();
        int level = (int) Math.floor(z);
        IsoGridSquare above = cell.getGridSquare((int) Math.floor(c.getX()), (int) Math.floor(c.getY()), level + 1);
        if (above == null || !(above.TreatAsSolidFloor() || above.isSolidFloor())) return false;
        return z + Cfg.headroom >= level + 1;
    }

    private static boolean readOnGround(IsoGameCharacter c) {
        try {
            if (isOnGroundField == null) {
                isOnGroundField = IsoGameCharacter.class.getDeclaredField("isOnGround");
                isOnGroundField.setAccessible(true);
            }
            return isOnGroundField.getBoolean(c);
        } catch (Throwable t) {
            return c.getLastFallSpeed() == 0;
        }
    }

    private static boolean isLocal(IsoPlayer p) {
        return p.isLocalPlayer() && p == IsoPlayer.getInstance();
    }

    /** No 0.75 stair slowdown while we drive. */
    public static float onMovementMod(IsoPlayer p, float ret) {
        return p == self && owned ? 1f : ret;
    }

    /** Velocity relative to the ground, including the car you ride. */
    static double velX() {
        if (Floors.ride == null) return vel.x;
        Rides.velocity(Floors.ride, Floors.carVel);
        return vel.x + Floors.carVel[0];
    }

    static double velY() {
        if (Floors.ride == null) return vel.y;
        Rides.velocity(Floors.ride, Floors.carVel);
        return vel.y + Floors.carVel[1];
    }

    private static void resetFor(IsoPlayer p) {
        self = p;
        vel.x = vel.y = 0;
        grounded = true;
        airborneUnderMod = false;
        wasOwned = false;
        owned = false;
        tickAcc = 0;
        jumpQueuedAt = 0;
        groundedBeforeFalling = true;
        jumpedThisAir = false;
        JumpCam.camLocked = false;
        animOn = false;
        Floors.ride = Floors.floorVehicle = null;
        Landing.hasLastLand = false;
        Floors.floorKind = Floors.FLOOR_GROUND;
    }
}

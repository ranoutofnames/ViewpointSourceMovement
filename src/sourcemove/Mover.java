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
import zombie.characters.Moodles.Moodles;
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
    /** Last unowned frame was a car, a timed action or a ride mod. */
    private static boolean wasParked;

    private static float wishX, wishY;
    private static long wishFrame = Long.MIN_VALUE / 2;

    private static boolean lastJumpDown, jumpDown, jumpEdge;
    /** In a swing that began in the air. */
    private static boolean airSwing;
    static long jumpQueuedAt;

    private static boolean groundedBeforeFalling = true;
    static float fallSpeedBeforeFalling;
    static boolean jumpedThisAir;
    static long airStartNanos;

    /** Vanilla full speed per mode (tiles/s). */
    private static final double[] BASE_SPEED = {1.0, 1.9, 3.4, 5.0};
    /** Learned speed stays within this of vanilla. */
    private static final double LEARN_CAP = 1.25;
    /** Root motion this far over vanilla is a ride mod (bike, skateboard), so vanilla moves us. */
    private static final double RIDE_RATIO = 1.6;
    /** A mod's own animation this far under vanilla is a ride too (standing on the board). */
    private static final double RIDE_SLOW_RATIO = 0.3;
    private static boolean riding;
    private static double rideSpeed;
    /** Learned ground speed (tiles/s) per mode, then aiming per mode, then sneak-run and sneak-sprint. */
    static final double[] maxSpeed = new double[10];
    static { resetSpeeds(); }
    private static double modeHeldTime;
    private static int lastSlot = -1;

    private static final Vector2 tmp = new Vector2();
    private static Field isOnGroundField;
    static String lastState = "";
    /** Why we don't drive, for the debug overlay. */
    static String blockedBy, jumpBlockedBy;

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
        baseActive = Cfg.enabled && !GameServer.server && (!GameClient.client || Net.serverReady())
                && (!Cfg.fpOnly || Viewpoint.active());
        pollJumpKey();
        // Also through timed actions on the prop.
        Zombies.updateRaised(p, grounded);

        boolean own = computeOwns(p);
        owned = own;
        if (!own) {
            wasOwned = false;
            if (riding) checkRideOver(p, dt);
            prevX = p.getX();
            prevY = p.getY();
            prevDt = dt;
            // A ride's speed isn't ours to keep either.
            wasParked = p.getVehicle() != null || !p.getCharacterActions().isEmpty() || p.getIgnoreMovement()
                    || "ride mod".equals(blockedBy) || "movement locked".equals(blockedBy);
            Net.frame(p, false, grounded, false, animOn, null);
            return false;
        }
        if (!wasOwned) {
            // Take over at vanilla's speed so it doesn't snap.
            vel.x = vel.y = 0;
            if (prevDt > 0 && !wasParked) {
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
        int mode = modeOf(p);
        IsoGridSquare under = p.getCurrentSquare();
        // Stair and slope animation pace isn't run speed.
        boolean level = under == null || !(under.HasStairs() || under.hasSlopedSurface());
        int slot = slotOf(p, mode);
        // A hit slow is moveUnmodded's own.
        learnSpeed(p, mode, slot, wishing && level && p.getSlowFactor() <= 0, tmp.getLength(), dt);

        double wishSpeed = wishing ? maxSpeed[slot] : 0;
        double tick = 1.0 / Math.max(10, Cfg.tickrate());

        handleJump(p, tick, wishSpeed);
        if (grounded && Cfg.trimp()) Ramps.tryTrimp(p);
        if (grounded) Zombies.pulledThisAir = false;
        else if (Cfg.pulldown() && !Zombies.pulledThisAir) Zombies.tryPulldown(p, dt);
        tickAcc += dt;
        int n = 0;
        while (tickAcc >= tick && n < 64) {
            if (grounded) {
                groundTick(tick, wishSpeed);
            } else if (wishSpeed > 0) {
                Physics.airAccelerate(vel, wishX, wishY, wishSpeed, Cfg.airAccelerate(), tick, Cfg.airCapRatio * BASE_SPEED[RUN]);
            }
            tickAcc -= tick;
            n++;
        }
        if (n == 64) tickAcc = 0;

        if (Cfg.maxSpeed() > 0) {
            double s = vel.speed();
            if (s > Cfg.maxSpeed()) { vel.x *= Cfg.maxSpeed() / s; vel.y *= Cfg.maxSpeed() / s; }
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
        blockedBy = whyNotOwned(p, state);
        airSwing = AIR_OWNED_STATES.contains(state) && blockedBy == null && (airSwing || !grounded);
        return blockedBy == null;
    }

    /** First thing keeping us from driving, null when we drive. */
    private static String whyNotOwned(IsoPlayer p, String state) {
        if (!Cfg.enabled) return "disabled";
        if (GameClient.client && !Net.serverReady()) return "waiting for server";
        if (Cfg.fpOnly && !Viewpoint.active()) return "not first person";
        if (!baseActive || state == null) return "inactive";
        if (p.isDead() || p.isAsleep()) return "dead or asleep";
        if (p.getVehicle() != null || p.isSeatedInVehicle()) return "in vehicle";
        if (p.isRagdoll() || p.isGrappling() || p.isBeingGrappled()) return "ragdoll or grapple";
        if (p.getPath2() != null) return "pathing";
        // Not hasTimedActions, its IsPerformingAnAction flag can outlive a broken action.
        if (!p.getCharacterActions().isEmpty()) return "timed action";
        // Ride mods move the player themselves.
        if (riding) return "ride mod";
        if (p.getIgnoreMovement() || !p.isDeferredMovementEnabled()
                || p.getVariableBoolean("HorseRiding") || p.getVariableBoolean("SkateboardActive")) return "movement locked";
        if (OWNED_STATES.contains(state)) return null;
        // A swing from mid-hop stays ours after landing, and a jump press cuts a ground swing short (Space is also shove).
        if (AIR_OWNED_STATES.contains(state) && (!grounded || airSwing || jumpWanted())) return null;
        return "state " + state;
    }

    /** Puts movement and jump state back to rest, on toggle and on a new character. */
    static void softReset() {
        vel.x = vel.y = 0;
        tickAcc = 0;
        wasOwned = false;
        owned = false;
        wishX = wishY = 0;
        airborneUnderMod = false;
        jumpedThisAir = false;
        jumpQueuedAt = 0;
        jumpBlockedBy = null;
        // A key already held isn't a fresh press.
        lastJumpDown = Cfg.jumpKey > 0 && GameKeyboard.isKeyDown(Cfg.jumpKey);
        airSwing = false;
        riding = false;
        rideSpeed = 0;
        modeHeldTime = 0;
        Barbs.snagged = false;
        Zombies.jumpLockUntil = 0;
        JumpCam.camLocked = false;
        Landing.inLanding = false;
        animOn = false;
        IsoPlayer p = self;
        grounded = p == null || readOnGround(p);
        groundedBeforeFalling = grounded;
        if (p != null) p.setVariable(ANIM_VAR, false);
    }

    /** New local character, its own learned speeds, dry land and floor. */
    private static void resetFor(IsoPlayer p) {
        self = p;
        resetSpeeds();
        lastSlot = -1;
        prevDt = 0;
        wasParked = false;
        walkedOn = null;
        Landing.hasLastLand = false;
        Floors.reset();
        softReset();
    }

    /** Forget the character and world, on game start and the main menu. */
    public static void resetSession() {
        resetFor(null);
        Zombies.reset();
        Windows.lastCrossed = null;
        Ledges.lastRailStairs = null;
        Props.lastObject = null;
    }

    private static void learnSpeed(IsoPlayer p, int mode, int slot, boolean wishing, float rootLen, double dt) {
        if (slot != lastSlot || !wishing || !grounded) {
            modeHeldTime = 0;
            rideSpeed = 0;
            lastSlot = slot;
            return;
        }
        modeHeldTime += dt;
        // Skip vanilla's 0.55 s speed ramp.
        if (modeHeldTime < 0.7 || dt <= 0) return;
        double s = rootLen / dt;
        rideSpeed = modeHeldTime - dt < 0.7 ? s : rideSpeed + (s - rideSpeed) * Math.min(1, 2 * dt);
        if (modeHeldTime >= 1.2) {
            // A mod's own animation only gets the learning margin, a vanilla one sped up gets more.
            boolean mod = ModAnims.playing(p);
            double base = BASE_SPEED[mode];
            if (rideSpeed > base * (mod ? LEARN_CAP : RIDE_RATIO) || mod && rideSpeed < base * RIDE_SLOW_RATIO) {
                riding = true;
                return;
            }
        }
        if (s < 0.2 || s > 25) return;
        double rate = s > maxSpeed[slot] ? 3.0 : 1.5;
        maxSpeed[slot] += (s - maxSpeed[slot]) * Math.min(1, rate * dt);
        maxSpeed[slot] = Math.min(maxSpeed[slot], baseOf(slot) * LEARN_CAP);
    }

    /** Aim strafes and sneak-runs have their own pace, so they learn apart from the plain mode. */
    private static int slotOf(IsoPlayer p, int mode) {
        if (p.isAiming()) return 4 + mode;
        if (mode >= RUN && p.isSneaking()) return 6 + mode;
        return mode;
    }

    private static double baseOf(int slot) {
        return BASE_SPEED[slot < 4 ? slot : slot < 8 ? slot - 4 : slot - 6];
    }

    private static void resetSpeeds() {
        for (int i = 0; i < maxSpeed.length; i++) maxSpeed[i] = baseOf(i);
    }

    private static int modeOf(IsoPlayer p) {
        return p.isSprinting() ? SPRINT : p.isRunning() ? RUN : p.isSneaking() ? SNEAK : WALK;
    }

    /** Take back over once you let go of the keys, or a ride mod slows to walking pace. */
    private static void checkRideOver(IsoPlayer p, double dt) {
        double moved = prevDt > 0 ? Math.hypot(p.getX() - prevX, p.getY() - prevY) / prevDt : 0;
        rideSpeed += (moved - rideSpeed) * Math.min(1, 2 * dt);
        boolean wishing = frame - wishFrame <= 1 && (wishX != 0 || wishY != 0);
        boolean slowed = rideSpeed < BASE_SPEED[modeOf(p)] * LEARN_CAP && !ModAnims.playing(p);
        if (!baseActive || !wishing || slowed || p.getVehicle() != null) {
            riding = false;
            rideSpeed = 0;
            modeHeldTime = 0;
        }
    }

    /** Source ground tick, friction then accelerate. */
    private static void groundTick(double tick, double wishSpeed) {
        Physics.friction(vel, Cfg.friction(), Cfg.stopSpeedRatio * BASE_SPEED[RUN], tick);
        if (wishSpeed > 0) Physics.accelerate(vel, wishX, wishY, wishSpeed, Cfg.accelerate(), tick);
    }

    /** Jump key state for the debug overlay. */
    static String keyState() {
        return String.format("jump key %d down=%s", Cfg.jumpKey, jumpDown);
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
        if (jumpEdge && !grounded && Cfg.jumpBufferMs() > 0) jumpQueuedAt = System.nanoTime();
    }

    private static boolean buffered(long now) {
        return jumpQueuedAt != 0 && now - jumpQueuedAt <= (long) (Cfg.jumpBufferMs() * 1_000_000L);
    }

    /** A press, a buffered press or a held autohop. */
    private static boolean jumpWanted() {
        return jumpEdge || buffered(System.nanoTime()) || Cfg.autohop() && jumpDown;
    }

    /** Source CheckJumpButton, a fresh press on the ground before friction; autohop pays ground ticks per hop. */
    private static void handleJump(IsoPlayer p, double tick, double wishSpeed) {
        if (!grounded) return;
        long now = System.nanoTime();
        boolean pressed = jumpEdge || buffered(now);
        boolean held = !pressed && Cfg.autohop() && jumpDown;
        if (!pressed && !held) return;
        if (now < Zombies.jumpLockUntil) { jumpBlockedBy = "grabbed"; return; }
        if (ceilingBlocked(p)) { jumpBlockedBy = "ceiling"; return; }

        Moodles moodles = p.getMoodles();
        int tired = moodles != null ? moodles.getMoodleLevel(MoodleType.ENDURANCE) : 0;
        if (Cfg.exhaustedNoJump() && tired >= 4) { jumpBlockedBy = "exhausted"; return; }
        jumpBlockedBy = null;
        double height = Cfg.jumpHeight();
        if (Cfg.tiredJumps()) height *= 1 - 0.08 * tired;
        if (Cfg.heavyJumps() && moodles != null) height *= 1 - 0.10 * moodles.getMoodleLevel(MoodleType.HEAVY_LOAD);

        if (held) {
            for (int i = 0; i < Cfg.autohopGroundTicks; i++) groundTick(tick, wishSpeed);
        }
        // Takeoff step, as in Source.
        if (Cfg.sounds()) p.DoFootstepSound(1.0f);
        exert(p);
        Landing.rememberLand(p);

        double v0 = Physics.jumpSpeed(height, FallingConstants.IsoFallAcceleration);
        if (Cfg.trimp() && Ramps.groundRamp(p)) v0 += Ramps.slopeLift(); // a jump up a ramp leaves from the slope
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
        double cost = Cfg.jumpExertion() * BAT_SWING_ENDURANCE * p.getFatigueMod()
                * p.getCharacterTraits().getTraitEnduranceLossModifier();
        if (Cfg.exertionWeight() && p.getInventory() != null && p.getInventory().getMaxWeight() > 0) {
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
        return modAir(c) && Cfg.fallMode() != Cfg.FALL_VANILLA;
    }

    public static void onUpdateFallingEnter(IsoGameCharacter c) {
        if (c != self) return;
        Floors.beginQuery();
        groundedBeforeFalling = grounded;
        fallSpeedBeforeFalling = c.getLastFallSpeed();
        if (fallOverride(c) && c.getLastFallSpeed() < 0 && ceilingBlocked(c)) c.setLastFallSpeed(0);
    }

    public static void onUpdateFallingExit(IsoGameCharacter c) {
        if (c != self) return;
        Floors.endQuery();
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
            int landing = Cfg.sounds() && air ? Landing.landingKind(fallSpeedBeforeFalling) : 0;
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

    /** First local player; getInstance() switches between splitscreen players mid-update, and SP animals call themselves local. */
    private static boolean isLocal(IsoPlayer p) {
        return p == IsoPlayer.players[0];
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
}

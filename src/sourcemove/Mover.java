package sourcemove;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.joml.Vector2f;

import zombie.GameTime;
import zombie.WorldSoundManager;
import zombie.ai.State;
import zombie.ai.states.AttackNetworkState;
import zombie.ai.states.AttackState;
import zombie.ai.states.LungeState;
import zombie.ai.states.PathFindState;
import zombie.ai.states.WalkTowardState;
import zombie.ai.states.ZombieIdleState;
import zombie.ai.states.ZombieTurnAlerted;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.CharacterStat;
import zombie.characters.FallingConstants;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.animation.AnimationClip;
import zombie.input.GameKeyboard;
import zombie.input.Mouse;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.Vector2;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.vehicles.BaseVehicle;
import zombie.scripting.objects.MoodleType;

/**
 * Source-style movement for the local player. Driven from the patches:
 *   doDeferredMovement (frame start)   -> {@link #onDeferredMovement}: jump + horizontal physics, replaces root motion
 *   updateMovementFromInput (exit)     -> {@link #onInput}: capture the world-space wish direction (already rotated by Viewpoint)
 *   updateFalling (enter/exit)         -> ceiling clamp, ground state, fall-state suppression
 *   DoLand / isFalling                 -> no fall damage, no falling animation
 *   postupdate (enter/exit)            -> clip velocity against whatever the engine's collision blocked
 * Horizontal state is in tiles and tiles/s; vertical uses the engine's own integrator (lastFallSpeed, levels/s).
 */
public final class Mover {
    private Mover() {}

    private static final int SNEAK = 0, WALK = 1, RUN = 2, SPRINT = 3;
    /** Action states where we drive movement. Everything else (climbing, falling, bumps, sitting...) is vanilla. */
    private static final Set<String> OWNED_STATES = Set.of(
            "idle", "movement", "run", "sprint", "strafe", "aim", "aim-strafe", "aim-sneak",
            "turning", "turning180", "turningAim180", "turningIdle180", "turningMovement180", "slowidleblend");
    /** Extra states we keep driving while airborne, so swinging mid-hop doesn't kill momentum. */
    private static final Set<String> AIR_OWNED_STATES = Set.of(
            "melee", "shove", "shoveAim", "shoveWithFirearm", "shoveWithHandgun", "ranged");

    /** The local player seen this frame (identity checks in hot advice paths). */
    public static volatile IsoPlayer self;
    /** Source movement drives {@link #self} this frame. */
    public static volatile boolean owned;

    private static boolean baseActive;
    private static boolean airborneUnderMod;
    private static boolean grounded = true;

    private static final Physics.Vel vel = new Physics.Vel();
    private static double tickAcc;
    private static long frame;
    private static boolean wasOwned;
    private static float prevX, prevY;
    private static double prevDt;

    private static float wishX, wishY;
    private static long wishFrame = Long.MIN_VALUE / 2;

    private static boolean lastJumpDown, jumpDown, jumpEdge;
    private static long jumpQueuedAt;

    private static boolean groundedBeforeFalling = true;
    private static float fallSpeedBeforeFalling;
    private static boolean jumpedThisAir;
    private static long airStartNanos;

    /** Learned ground speeds per mode (tiles/s), seeded with rough vanilla values and tuned from root motion. */
    private static final double[] maxSpeed = {1.0, 1.9, 3.4, 5.0};
    private static double modeHeldTime;
    private static int lastMode = -1;

    private static float preX, preY;
    private static boolean postActive;

    private static final Vector2 tmp = new Vector2();
    private static Field isOnGroundField;
    private static String lastState = "";

    // ---------------------------------------------------------------- frame start

    /** @return true to skip vanilla root-motion movement this frame. */
    public static boolean onDeferredMovement(IsoGameCharacter chr) {
        if (!(chr instanceof IsoPlayer p) || !isLocal(p)) return false;
        if (p != self) resetFor(p);
        frame++;

        double dt = GameTime.getInstance().getTimeDelta();
        if (!(dt > 0)) dt = 0;
        dt = Math.min(dt, 0.1);
        measureSpeed(p, dt);

        // Multiplayer: only once the server has answered (it relays our jumps and knows them in its anti-cheat).
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
            // Pick up whatever speed vanilla had us moving at, so the hand-off doesn't snap.
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

        // Consume the animation's root motion (vanilla would have applied it) and learn speeds from it.
        tmp.set(0, 0);
        if (p.hasAnimationPlayer() && p.getAnimationPlayer() != null) {
            p.getAnimationPlayer().getDeferredMovement(tmp, true);
        }
        boolean wishing = frame - wishFrame <= 1 && (wishX != 0 || wishY != 0);
        int mode = p.isSprinting() ? SPRINT : p.isRunning() ? RUN : p.isSneaking() ? SNEAK : WALK;
        IsoGridSquare under = p.getCurrentSquare();
        // Animation pace on stairs and slopes isn't your running speed: don't learn from it.
        boolean level = under == null || !(under.HasStairs() || under.hasSlopedSurface());
        learnSpeed(mode, wishing && level, tmp.getLength(), dt);

        double wishSpeed = wishing ? maxSpeed[mode] : 0;
        double runSpeed = maxSpeed[RUN];
        double tick = 1.0 / Math.max(10, Cfg.tickrate);

        handleJump(p, tick, wishSpeed, runSpeed);
        if (grounded && Cfg.trimp) tryTrimp(p);
        if (grounded) pulledThisAir = false;
        else if (Cfg.pulldown && !pulledThisAir) tryPulldown(p, dt);
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

        carry(p, carryOut);
        double dx = vel.x * dt + carryOut[0], dy = vel.y * dt + carryOut[1];
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (cell != null) {
            // Don't run into chunks that haven't streamed in; the engine would snap us back to the last square.
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
        Net.frame(p, true, grounded, floorKind != FLOOR_GROUND, animOn, grounded ? ride : null);
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
        // Skip the vanilla momentum ramp (0.55 s) so start-up doesn't drag the estimate down.
        if (modeHeldTime < 0.7 || dt <= 0 || rootLen <= 0) return;
        double s = rootLen / dt;
        if (s < 0.2 || s > 25) return;
        double rate = s > maxSpeed[mode] ? 3.0 : 0.5;
        maxSpeed[mode] += (s - maxSpeed[mode]) * Math.min(1, rate * dt);
    }

    /** One Source ground tick: Friction() then WalkMove's Accelerate(). */
    private static void groundTick(double tick, double wishSpeed, double runSpeed) {
        Physics.friction(vel, Cfg.friction, Cfg.stopSpeedRatio * runSpeed, tick);
        if (wishSpeed > 0) Physics.accelerate(vel, wishX, wishY, wishSpeed, Cfg.accelerate, tick);
    }

    /** Polled every frame so press edges stay correct across ownership changes. */
    private static void pollJumpKey() {
        int key = Cfg.jumpKey;
        boolean down = key > 0 && GameKeyboard.isKeyDown(key);
        boolean wheel = Cfg.wheelJump && Mouse.wheelDelta != 0;
        jumpEdge = (down && !lastJumpDown) || wheel;
        jumpDown = down;
        lastJumpDown = down;
        // Source spends a press made in the air (m_nOldButtons |= IN_JUMP); only an optional buffer keeps it.
        if (jumpEdge && !grounded && Cfg.jumpBufferMs > 0) jumpQueuedAt = System.nanoTime();
    }

    /**
     * Source CheckJumpButton rules: jump on a fresh press while grounded, before friction, so a perfectly
     * timed hop keeps all speed and every late tick costs friction. Holding only re-hops with autohop, and
     * each such hop pays {@link Cfg#autohopGroundTicks} ground ticks, so speed bleeds off unless you strafe.
     */
    private static void handleJump(IsoPlayer p, double tick, double wishSpeed, double runSpeed) {
        if (!grounded) return;
        long now = System.nanoTime();
        boolean buffered = jumpQueuedAt != 0 && now - jumpQueuedAt <= (long) (Cfg.jumpBufferMs * 1_000_000L);
        boolean pressed = jumpEdge || buffered;
        boolean held = !pressed && Cfg.autohop && jumpDown;
        if (!pressed && !held) return;
        if (now < jumpLockUntil || ceilingBlocked(p)) return;

        int tired = p.getMoodles().getMoodleLevel(MoodleType.ENDURANCE);
        if (Cfg.exhaustedNoJump && tired >= 4) return;
        double height = Cfg.jumpHeight;
        if (Cfg.tiredJumps) height *= 1 - 0.08 * tired;
        if (Cfg.heavyJumps) height *= 1 - 0.10 * p.getMoodles().getMoodleLevel(MoodleType.HEAVY_LOAD);

        if (held) {
            for (int i = 0; i < Cfg.autohopGroundTicks; i++) groundTick(tick, wishSpeed, runSpeed);
        }
        // Source plays a surface step on takeoff (CheckJumpButton -> PlayStepSound); this also makes footstep noise.
        if (Cfg.sounds) p.DoFootstepSound(1.0f);
        exert(p);
        rememberLand(p);

        double v0 = Physics.jumpSpeed(height, FallingConstants.IsoFallAcceleration);
        if (Cfg.trimp && groundRamp(p)) v0 = clipOnRamp(v0); // a jump into a ramp gets clipped along it too
        p.setLastFallSpeed((float) -v0);
        float animSpeed = 1;
        if (Cfg.jumpAnim) {
            animSpeed = vaultAnimSpeed(p, 2 * v0 / FallingConstants.IsoFallAcceleration);
            // Alternate between the A/B leap nodes so a quick re-jump starts the clip over.
            jumpAlt = !jumpAlt;
            p.setVariable(ANIM_ALT_VAR, jumpAlt);
            p.setVariable(ANIM_VAR, true);
            p.setVariable(ANIM_SPEED_VAR, animSpeed);
            animOn = true;
            if (Cfg.stableJumpCam) lockCamera(p);
        }
        Net.jumped(Cfg.jumpAnim, animSpeed);
        grounded = false;
        jumpQueuedAt = 0;
        jumpedThisAir = true;
        airStartNanos = now;
        airborneUnderMod = true;
    }

    /**
     * Endurance per jump in long-blunt swings: CombatManager.processWeaponEndurance charges
     * weight * 0.18 * fatigueMods * EnduranceMod * 0.3 * 0.04 per swing; a baseball bat is weight 2, EnduranceMod 1.
     */
    private static final double BAT_SWING_ENDURANCE = 2.0 * 0.18 * 0.3 * 0.04;

    private static void exert(IsoPlayer p) {
        double cost = Cfg.jumpExertion * BAT_SWING_ENDURANCE * p.getFatigueMod()
                * p.getCharacterTraits().getTraitEnduranceLossModifier();
        if (Cfg.exertionWeight && p.getInventory() != null && p.getInventory().getMaxWeight() > 0) {
            cost *= 1 + Math.min(2, p.getInventory().getCapacityWeight() / p.getInventory().getMaxWeight());
        }
        if (cost > 0) p.getStats().remove(CharacterStat.ENDURANCE, (float) cost);
    }

    /** Anim-set variables read by our jump nodes in media/AnimSets/player/<state> (tools/gen_jump_anims.py). */
    static final String ANIM_VAR = "SMJump", ANIM_SPEED_VAR = "SMJumpSpeed", ANIM_ALT_VAR = "SMJumpAlt";
    private static boolean animOn, jumpAlt;
    private static final Map<String, Float> clipSeconds = new HashMap<>();

    /** Sprinting fence leap per held-weapon type, as chosen by vanilla's climbfence/vaultOverSprint*.xml. */
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

    /** Play the whole leap clip over the jump's airtime. */
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

    // ---------------------------------------------------------------- input

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

    // ---------------------------------------------------------------- vertical

    /** The mod is driving this character's air time: it's on, or it was on when this fall/jump started. */
    private static boolean modAir(IsoGameCharacter c) {
        return c == self && (baseActive || airborneUnderMod);
    }

    /** Hide the falling state (bFalling, falling animation, input lockout) so you keep air control. */
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
            rememberLand(c);
        }
        boolean air = modAir(c);
        if (!groundedBeforeFalling && grounded && air && rampBounce(c)) {
            grounded = false; // came down onto a ramp moving up it fast: clipped along it and still flying
        } else if (!groundedBeforeFalling && grounded) {
            int landing = Cfg.sounds && air ? landingKind(fallSpeedBeforeFalling) : 0;
            if (landing > 0) playLanding(c, landing);
            Net.landed(landing);
            // In the water itself, not on a prop standing in it.
            if (air && floorKind == FLOOR_GROUND && Water.open(c.getCurrentSquare())) leaveWater(c);
            jumpedThisAir = false;
            c.setVariable(ANIM_VAR, false);
            animOn = false;
            // Keep holding while the leap blends out (m_BlendOutTime 0.2 s), then Viewpoint's smoothing eases back.
            if (camLocked) camReleaseAt = System.nanoTime() + 250_000_000L;
        }
        if (fallOverride(c)) {
            c.setbFalling(false);
            c.setFallTime(0);
        }
        airborneUnderMod = air && !grounded;
        updateRide();
    }

    /**
     * Where you last left dry land (not a car roof), for water landings far from any shore. Recorded once per
     * takeoff (a jump or walking off an edge), since that's the spot a water landing should return to.
     */
    private static boolean hasLastLand;
    private static float lastLandX, lastLandY, lastLandZ;
    /** How far back (tiles) the last dry-land spot may be and still be used. */
    private static final double LAST_LAND_RANGE = 64;

    private static void rememberLand(IsoGameCharacter c) {
        if (floorKind == FLOOR_VEHICLE || !Water.land(c.getCurrentSquare())) return;
        hasLastLand = true;
        lastLandX = c.getX();
        lastLandY = c.getY();
        lastLandZ = c.getZ();
    }

    /**
     * Landed in open water: back to the nearest dry land within a few tiles, or, out in the middle of a lake,
     * to where you last stood on dry land. Stopped either way.
     */
    private static void leaveWater(IsoGameCharacter c) {
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (cell == null) return;
        double[] at = Water.nearestLand(cell, c.getX(), c.getY(), (int) Math.floor(c.getZ()));
        if (at != null) {
            c.setForceX((float) at[0]);
            c.setForceY((float) at[1]);
        } else if (hasLastLand && Math.hypot(lastLandX - c.getX(), lastLandY - c.getY()) <= LAST_LAND_RANGE) {
            c.setForceX(lastLandX);
            c.setForceY(lastLandY);
            c.setZ(lastLandZ);
            c.setLastZ(lastLandZ);
        } else {
            return;
        }
        vel.x = vel.y = 0;
    }

    // ---------------------------------------------------------------- vehicles

    static final int FLOOR_GROUND = 0, FLOOR_FENCE = 1, FLOOR_VEHICLE = 2, FLOOR_PROP = 3;
    private static final float VEHICLE_FOOTING = 0.1f;
    /** How far past a prop's edge you can stand on it (tiles). */
    private static final double PROP_FOOTING = 0.15;
    private static int floorKind = FLOOR_GROUND;
    private static Props.Prop floorProp;
    private static int floorPropX, floorPropY;
    /** Standing on a stair rail: the stairs it follows (its slope is the rail's). */
    private static IsoGridSquare floorRail;
    private static BaseVehicle floorVehicle, ride;
    private static float rideX, rideY;
    private static double rideYaw;
    private static final float[] roofOut = new float[1];
    private static final double[] carVel = new double[2];

    /**
     * Standing on a roof makes the car your ground entity (Source's base velocity): while riding, our
     * velocity is relative to the car; getting on subtracts the car's velocity, getting off (jumping or
     * sliding off) adds it back, so you leave with its momentum.
     */
    private static void updateRide() {
        BaseVehicle next = grounded && floorKind == FLOOR_VEHICLE ? floorVehicle : null;
        if (next == ride) return;
        if (ride != null) {
            Rides.velocity(ride, carVel);
            vel.x += carVel[0];
            vel.y += carVel[1];
        }
        if (next != null) {
            Rides.velocity(next, carVel);
            vel.x -= carVel[0];
            vel.y -= carVel[1];
            rideX = next.getX();
            rideY = next.getY();
            rideYaw = Rides.yaw(next);
        }
        ride = next;
    }

    /** Displacement this frame from the car carrying you (its move plus its turn around its center). */
    private static void carry(IsoPlayer p, double[] out) {
        out[0] = out[1] = 0;
        if (ride == null || !grounded) return;
        float nx = ride.getX(), ny = ride.getY();
        double yaw = Rides.yaw(ride), turn = yaw - rideYaw, cos = Math.cos(turn), sin = Math.sin(turn);
        double rx = p.getX() - rideX, ry = p.getY() - rideY;
        out[0] = nx + rx * cos - ry * sin - p.getX();
        out[1] = ny + rx * sin + ry * cos - p.getY();
        double vx = vel.x;
        vel.x = vx * cos - vel.y * sin; // relative velocity turns with the car
        vel.y = vx * sin + vel.y * cos;
        rideX = nx;
        rideY = ny;
        rideYaw = yaw;
    }

    private static final double[] carryOut = new double[2];

    /**
     * PolygonalMap2.resolveCollision exit: cars and solid props under your feet aren't in your way.
     * Walls are still handled by the grid collision in postupdate.
     */
    public static void onResolveCollision(IsoGameCharacter c, float nx, float ny, Vector2f result) {
        if (c != self || !owned || result == null || (result.x == nx && result.y == ny)) return;
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (cell == null) return;
        int cars = Rides.vehiclesAt(cell, c.getX(), c.getY(), nx, ny, c.getZ(), 0.3f, Ledges.STEP_UP);
        if (cars < 0) return;
        int props = Props.squaresAt(cell, c.getX(), c.getY(), nx, ny, c.getZ(), 0.3f, Ledges.STEP_UP, !grounded);
        if (props < 0) return;
        // It also treats every window as a wall and holds you off it before the grid collision is reached.
        if (cars > 0 || props > 0 || Windows.nearOpening(c, cell, nx, ny, c.getZ(), 0.35, vel)) result.set(nx, ny);
    }

    /** BaseVehicle.testCollisionWithCharacter exit: a car can't run you over while you're on or above its roof. */
    public static Vector2 onVehicleHitTest(BaseVehicle v, IsoGameCharacter c, Vector2 ret) {
        // Also for another player using this mod (multiplayer: the driver's client tests hits on its riders).
        if (ret == null || (c == self ? !baseActive : !Remote.active(c))) return ret;
        float roof = Rides.roofZ(v);
        return !Float.isNaN(roof) && c.getZ() >= roof - Ledges.STEP_UP ? null : ret;
    }

    // ---------------------------------------------------------------- zombie reach

    private static boolean reachSwapped;
    private static int reachDepth;
    private static float savedZ, savedLastZ;
    private static IsoPlayer reachPlayer;

    /**
     * Zombie deciding to attack (getShouldAttack) or landing a hit (AttackState.triggerPlayerReaction):
     * if the player is above it but within grab reach, count them at its height. Nesting-safe: only the
     * outermost call swaps and restores.
     */
    public static void onZombieAttackEnter(IsoGameCharacter zombie) {
        if (reachDepth++ > 0) return;
        reachSwapped = false;
        // In multiplayer the zombie may be ours while its target is another player using this mod.
        if (!(zombie instanceof IsoZombie z) || !(z.getTarget() instanceof IsoPlayer p)) return;
        if (p == self ? !baseActive : !Remote.active(p)) return;
        float dz = p.getZ() - zombie.getZ();
        if (dz < 0.2f || dz >= Cfg.zombieReach) return;
        savedZ = p.getZ();
        savedLastZ = p.getLastZ();
        p.setZ(zombie.getZ());
        reachPlayer = p;
        reachSwapped = true;
    }

    public static void onZombieAttackExit() {
        if (reachDepth > 0 && --reachDepth > 0) return;
        if (!reachSwapped) return;
        reachSwapped = false;
        IsoPlayer p = reachPlayer;
        reachPlayer = null;
        if (p == null) return;
        p.setZ(savedZ);
        p.setLastZ(savedLastZ);
    }

    // ---------------------------------------------------------------- pulldown

    /** Downward speed a grab yanks you to (levels/s); below vanilla's no-damage landing speed of ~2.2. */
    private static final float PULL_SPEED = 1.5f;
    private static boolean pulledThisAir;
    private static long jumpLockUntil, pulledAt;

    /**
     * A group of zombies in grab range can drag you out of the air (at most one grab per airtime). One zombie
     * alone can't; from {@link Cfg#pulldownGroup} on, the grab rate grows with every extra zombie.
     */
    private static void tryPulldown(IsoPlayer p, double dt) {
        if (dt <= 0 || p.isGhostMode() || p.isZombiesDontAttack()) return;
        IsoCell cell = p.getCell();
        if (cell == null) return;
        double r = Cfg.pulldownRange;
        IsoGridSquare mine = p.getCurrentSquare();
        int grabbers = 0;
        double x = p.getX(), y = p.getY(), z = p.getZ();
        // Only the squares within grab range (each square lists who's on it), not every zombie in the cell.
        for (int level = (int) Math.floor(z - Cfg.zombieReach); level <= (int) Math.floor(z); level++) {
            for (int gx = (int) Math.floor(x - r); gx <= (int) Math.floor(x + r); gx++) {
                for (int gy = (int) Math.floor(y - r); gy <= (int) Math.floor(y + r); gy++) {
                    IsoGridSquare sq = cell.getGridSquare(gx, gy, level);
                    if (sq == null) continue;
                    ArrayList<IsoMovingObject> here = sq.getMovingObjects();
                    for (int i = 0; i < here.size(); i++) {
                        if (!(here.get(i) instanceof IsoZombie zed)) continue;
                        double dx = x - zed.getX(), dy = y - zed.getY();
                        if (dx * dx + dy * dy > r * r) continue;
                        double dz = z - zed.getZ();
                        if (dz < 0 || dz >= Cfg.zombieReach || zed.getTarget() != p || !canGrab(zed)) continue;
                        if (sq != mine && mine != null && sq.isSomethingTo(mine)) continue;
                        grabbers++;
                    }
                }
            }
        }
        int group = Math.max(1, Cfg.pulldownGroup);
        if (grabbers < group) return;
        double rate = Cfg.pulldownRate * (grabbers - group + 1);
        if (Math.random() < 1 - Math.exp(-rate * dt)) pulldown(p);
    }

    /** Upright, facing you and in a chase or attack state (not staggered, knocked down or getting up). */
    private static boolean canGrab(IsoZombie z) {
        if (z.isDead() || z.isOnFloor() || z.isCrawling() || z.isUseless() || z.isKnockedDown() || !z.isFacingTarget()) {
            return false;
        }
        State s = z.getCurrentState();
        // AttackNetworkState: a zombie simulated by another client, attacking.
        return s == LungeState.instance() || s == AttackState.instance() || s == AttackNetworkState.instance() || s == PathFindState.instance()
                || s == WalkTowardState.instance() || s == ZombieIdleState.instance() || s == ZombieTurnAlerted.instance();
    }

    private static void pulldown(IsoPlayer p) {
        vel.x *= Cfg.pulldownKeep;
        vel.y *= Cfg.pulldownKeep;
        if (p.getLastFallSpeed() < PULL_SPEED) p.setLastFallSpeed(PULL_SPEED);
        long now = System.nanoTime();
        jumpLockUntil = now + (long) (Cfg.pulldownLockout * 1e9);
        jumpQueuedAt = 0;
        pulledThisAir = true;
        pulledAt = now;
    }

    private static Field footstepCharacter;

    /** ParameterFootstepMaterial exit: metal on car roofs, wood on fence tops (it only looks at the floor tile). */
    public static float onFootstepMaterial(Object param, float ret) {
        if (floorKind == FLOOR_GROUND || self == null) return ret;
        try {
            if (footstepCharacter == null) {
                footstepCharacter = param.getClass().getDeclaredField("character");
                footstepCharacter.setAccessible(true);
            }
            if (footstepCharacter.get(param) != self) return ret;
        } catch (Throwable t) {
            return ret;
        }
        if (floorKind == FLOOR_PROP && floorProp != null) return floorProp.material;
        return floorKind == FLOOR_VEHICLE ? 12f : 7f; // FootstepMaterial.Metal / Wood
    }

    private static boolean inLanding;

    /** @return true to skip DoLand entirely (FALL_NONE: no damage, pain, or landing knockdown). */
    public static boolean skipLanding(IsoGameCharacter c) {
        // Another player using this mod: their own client lands them (damage, knockdown) and syncs it.
        return modAir(c) && Cfg.fallMode == Cfg.FALL_NONE || Remote.fallsOverridden(c);
    }

    /**
     * FALL_REASONABLE: DoLand's impact speed is re-expressed as a fall height, {@link Cfg#safeDrop} of it
     * is forgiven, and vanilla's damage/injury model runs on the rest.
     */
    public static float landingSpeed(IsoGameCharacter c, float speed) {
        if (!modAir(c) || Cfg.fallMode != Cfg.FALL_REASONABLE || speed <= 0) return speed;
        inLanding = true;
        return (float) Physics.forgiveDrop(speed, FallingConstants.IsoFallAcceleration, Cfg.safeDrop);
    }

    public static void onLandingDone(IsoGameCharacter c) {
        inLanding = false;
    }

    /** @return true to skip the landing knockdown (fallenOnKnees / dropHandItems) when it's turned off. */
    public static boolean skipKnockdown(IsoGameCharacter c) {
        return inLanding && c == self && !Cfg.fallKnockdown;
    }

    // ---------------------------------------------------------------- fences and roofs

    /**
     * getHeightAboveFloor exit: a fence top under your feet is a floor. The engine's own gravity then lands
     * you on it, and walking off it drops you.
     */
    public static float onHeightAboveFloor(IsoGameCharacter c, float ret) {
        if (c != self) return ret;
        floorKind = FLOOR_GROUND;
        floorVehicle = null;
        if (!baseActive) return ret;
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (cell == null) return ret;
        double z = c.getZ();
        double best = -1;
        int kind = FLOOR_GROUND;
        // Step-up only catches you coming down (or already on top): rising past a top isn't a landing, which
        // would zero your upward speed and apply ground friction mid-jump.
        double stepUp = c.getLastFallSpeed() < 0 ? 0 : Ledges.STEP_UP;
        double fence = Ledges.fenceTopUnder(cell, c.getX(), c.getY(), (int) Math.floor(z), Cfg.fenceFooting);
        IsoGridSquare rail = Ledges.lastRailStairs;
        if (fence >= 0 && z >= fence - stepUp) { // well below the top it's a wall to you, not a floor
            best = fence;
            kind = FLOOR_FENCE;
            floorRail = rail;
        }
        BaseVehicle car = Rides.roofUnder(cell, c.getX(), c.getY(), z, VEHICLE_FOOTING, stepUp, roofOut);
        if (car != null && roofOut[0] > best) {
            best = roofOut[0];
            kind = FLOOR_VEHICLE;
        }
        Props.Prop prop = null;
        if (Cfg.propMode != Props.MODE_OFF) {
            // (Ramps, tent sides, keep catching you while rising: that landing is what clips you along them.)
            double top = Props.topUnder(cell, c.getX(), c.getY(), (int) Math.floor(z), z, stepUp, PROP_FOOTING);
            if (top > best) {
                best = top;
                kind = FLOOR_PROP;
                prop = Props.lastProp;
                floorPropX = Props.lastPropX;
                floorPropY = Props.lastPropY;
            }
        }
        if (best < 0 || z - best > ret) return ret;
        floorKind = kind;
        floorVehicle = kind == FLOOR_VEHICLE ? car : null;
        floorProp = prop;
        return (float) (z - best);
    }

    /**
     * shouldIgnoreCollisionWithSquare exit: pass over an edge whose obstacle top is below your feet
     * (fences mid-jump, walking along a fence top), and step from open air onto a roof square one level up.
     * Edges with nothing on them at the same level stay vanilla.
     */
    public static boolean onIgnoreCollision(IsoGameCharacter c, IsoGridSquare target, boolean ret) {
        if (ret || c != self || !owned || target == null) return ret;
        IsoGridSquare from = c.getLastSquare();
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (from == null || cell == null) return false;
        double feet = c.getZ();
        int level = target.getZ();
        int dz = level - from.getZ();
        if (dz < 0 || level > (int) Math.floor(feet)) return false;
        if (dz == 0) {
            // Open, broken or empty windows are openings; closed ones can be crashed through at speed.
            IsoObject window = Windows.between(cell, level, from.getX(), from.getY(), target.getX(), target.getY());
            double into = vel.x * (target.getX() - from.getX()) + vel.y * (target.getY() - from.getY());
            if (window != null && Windows.pass(c, window, feet, level, into, vel.speed(), vel)) return true;
        }
        double top = Ledges.crossingHeight(cell, level, from.getX(), from.getY(), target.getX(), target.getY());
        // Open water: in the air or from a prop standing in it, never wading (landing in it puts you back on land).
        // The engine blocks both into and out of water, so leaving it onto open ground needs letting through too.
        boolean wet = Water.open(target), leavingWater = Water.open(from);
        if (wet && grounded && feet < level + Water.SURFACE) return false;
        if (target.isSolid() || target.isSolidTrans()) {
            // Props (benches, fountains, tires...) have their own heights; anything else solid stays in the way,
            // except a fully solid square, which you can pass over from a level up.
            // A tent's side is a slope from the ground up: only its surface where you step in counts.
            Props.Prop slope = Props.ramp(target);
            double prop = slope != null ? Props.rampHeightAt(cell, slope, target.getX(), target.getY(), level, c.getX(), c.getY())
                    : Props.entryTop(target);
            // The engine treats the whole square as solid, so even a slope starting at the ground needs letting in.
            if (slope != null) return feet >= level + top - Ledges.STEP_UP && feet >= level + prop - Props.RAMP_ALLOW;
            if (Double.isNaN(prop)) {
                if (!target.has(IsoFlagType.solid)) return false;
                prop = Ledges.FULL;
            }
            top = Math.max(top, prop);
        }
        if (top <= 0 && dz == 0) return wet || leavingWater;
        return feet >= level + top - Ledges.STEP_UP; // same leeway as stepping up onto a top
    }

    /** isFalling() feeds the animation graph's "bfalling"; report false so the falling state never plays. */
    public static boolean onIsFalling(IsoGameCharacter c, boolean ret) {
        return ret && !(c == self ? fallOverride(c) : Remote.fallsOverridden(c));
    }

    /**
     * PZ's own landing events (Character/Foley/Land/*) have per-surface samples (concrete, dirt, grass,
     * gravel, metal, snow, water, wood) selected by the emitter's FootstepMaterial parameter, so playing
     * them on the character's emitter gives the right surface. Heavier impacts pick heavier events.
     */
    private static final String[] LAND_EVENTS = {null, "LandLight", "LandHeavy", "LandHeavyFromFall"};
    private static final int[] LAND_RADIUS = {0, 6, 12, 20};

    /** 0 = no landing sound, 1-3 = light, heavy, very heavy (index into {@link #LAND_EVENTS}). */
    private static int landingKind(float impact) {
        long airMs = (System.nanoTime() - airStartNanos) / 1_000_000L;
        if (!jumpedThisAir && airMs < 150) return 0; // stairs, slopes, tiny drops: the walk cycle covers those
        if (impact < FallingConstants.noDamageThreshold) return 1;
        return impact < FallingConstants.hardFallThreshold ? 2 : 3;
    }

    private static void playLanding(IsoGameCharacter c, int kind) {
        int radius = LAND_RADIUS[kind];
        if (c.getEmitter() != null) c.getEmitter().playSoundImpl(LAND_EVENTS[kind], c);
        IsoGridSquare sq = c.getCurrentSquare();
        if (sq != null && sq.getRoom() != null) radius /= 2;
        WorldSoundManager.instance.addSound(c, (int) Math.floor(c.getX()), (int) Math.floor(c.getY()),
                (int) Math.floor(c.getZ()), radius, radius);
    }

    /** @return true to skip an animation footstep: the walk cycle keeps playing in the air, feet don't touch anything. */
    public static boolean onAnimFootstep(IsoGameCharacter c) {
        return Cfg.sounds && (c == self ? owned && !grounded : Remote.airborne(c));
    }

    // ---------------------------------------------------------------- jump camera

    private static boolean camLocked;
    private static float camX, camUp, camZ;
    private static double camAngle;
    private static long camReleaseAt;

    private static double facing(IsoPlayer p) {
        return Math.atan2(p.getForwardDirectionY(), p.getForwardDirectionX());
    }

    private static void lockCamera(IsoPlayer p) {
        float[] off = Viewpoint.eyeOffset();
        if (off == null) return;
        camX = off[0];
        camUp = off[1];
        camZ = off[2];
        camAngle = facing(p);
        camReleaseAt = Long.MAX_VALUE;
        camLocked = true;
    }

    /**
     * After Viewpoint computes the eye: hold the takeoff head offset. The horizontal part is body-relative,
     * so rotate it with the body's facing (Viewpoint's render X/Z are the negated world x/y, and negating
     * both components commutes with rotation).
     */
    public static void onViewpointEye(IsoGameCharacter c, Object frame) {
        if (!camLocked || c != self) return;
        if (!Cfg.stableJumpCam || System.nanoTime() > camReleaseAt) {
            camLocked = false;
            return;
        }
        double a = facing(self) - camAngle, cos = Math.cos(a), sin = Math.sin(a);
        Viewpoint.overrideEye(frame, (float) (camX * cos - camZ * sin), camUp, (float) (camX * sin + camZ * cos));
    }

    // ---------------------------------------------------------------- stairs and slopes

    private static final double LEVEL_M = 2.44949;
    /** Source's NON_JUMP_VELOCITY: moving up faster than 140 u/s (0.01905 m/u) takes you off the ground (levels/s). */
    private static final double NON_JUMP = 140 * 0.01905 / LEVEL_M;
    private static boolean snapGuard;
    private static float snapZ;

    /**
     * Fastest plausible takeoff (levels/s): a full jump plus the most a ramp can add (a clip lifts at most half
     * your speed, at 45 degrees) at the server's speed limit. The server's bound on reported vertical speed.
     */
    static double maxLaunchSpeed() {
        double v0 = Physics.jumpSpeed(Math.max(0.1, Cfg.jumpHeight), FallingConstants.IsoFallAcceleration);
        if (!Cfg.trimp) return v0;
        double fastest = Cfg.mpSpeedLimit > 0 ? Cfg.mpSpeedLimit : 60;
        return v0 + 0.5 * fastest / LEVEL_M;
    }

    /**
     * IsoMovingObject.doStairs / handleSlopedSurface: every frame they set your Z to the stairs' or slope's
     * surface (doStairs whenever you're within 0.95 level of it), which cancels a jump the moment you take off.
     * While you're in a jump or launch, keep your height; the engine's own getHeightAboveFloor (which knows
     * stairs and slopes) lands you on the surface when you come down.
     */
    public static void onSurfaceSnapEnter(IsoMovingObject o) {
        snapGuard = o == self && owned && !grounded && (jumpedThisAir || self.getLastFallSpeed() < 0);
        if (snapGuard) snapZ = o.getZ();
    }

    public static void onSurfaceSnapExit(IsoMovingObject o) {
        if (!snapGuard || o != self) return;
        snapGuard = false;
        if (o.getZ() < snapZ) o.setZ(snapZ);
    }

    /** The ramp in contact: rising along (rampUx, rampUy) at rampAngle radians from horizontal. */
    private static double rampUx, rampUy, rampAngle;

    /**
     * The ramp you're on: a tent's side (uphill toward its ridge), a stair rail, or stairs or a slope (the
     * gradient of the surface, getApparentZ, under your feet).
     */
    private static boolean groundRamp(IsoGameCharacter c) {
        if (floorKind == FLOOR_PROP && floorProp != null && floorProp.rampAngle > 0) {
            IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
            if (cell == null) return false;
            int dir = Props.uphill(cell, floorProp, floorPropX, floorPropY, (int) Math.floor(c.getZ()), c.getX(), c.getY());
            if (dir == 0) return false; // on the ridge
            rampUx = floorProp.ridgeAlongY ? dir : 0;
            rampUy = floorProp.ridgeAlongY ? 0 : dir;
            rampAngle = floorProp.rampAngle;
            return true;
        }
        IsoGridSquare sq = c.getCurrentSquare();
        double fx = c.getX() - (sq != null ? sq.getX() : 0), fy = c.getY() - (sq != null ? sq.getY() : 0), e = 0.1;
        if (floorKind == FLOOR_FENCE && floorRail != null) {
            // A stair rail runs at its stairs' slope: take the stairs' gradient mid-square.
            sq = floorRail;
            fx = fy = 0.5;
        }
        if (sq == null || !(sq.HasStairs() || sq.hasSlopedSurface())) return false;
        double gx = grade(sq, fx - e, fy, fx + e, fy), gy = grade(sq, fx, fy - e, fx, fy + e);
        double g = Math.hypot(gx, gy);
        if (g < 1e-3) return false;
        rampUx = gx / g;
        rampUy = gy / g;
        rampAngle = Math.atan(g * LEVEL_M); // a tile is ~1 m across, a level 2.449 m tall
        return true;
    }

    /** Rise (levels) per tile between two points of a square's surface. */
    private static double grade(IsoGridSquare sq, double ax, double ay, double bx, double by) {
        ax = clamp01(ax);
        ay = clamp01(ay);
        bx = clamp01(bx);
        by = clamp01(by);
        double run = Math.hypot(bx - ax, by - ay);
        if (run < 1e-6) return 0;
        return (sq.getApparentZ((float) bx, (float) by) - sq.getApparentZ((float) ax, (float) ay)) / run;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /** Source ClipVelocity against the current ramp: changes our horizontal velocity, returns new upward levels/s. */
    private static double clipOnRamp(double vzLevels) {
        return Physics.clipRamp(vel, vzLevels * LEVEL_M, rampUx, rampUy, rampAngle) / LEVEL_M;
    }

    /**
     * Trimping, as in Source: running into a ramp clips your velocity along it, turning part of it upward. On
     * the ground that's only a lift-off once it's faster than NON_JUMP_VELOCITY; below that you just keep
     * walking (the ramp is a floor, or for a tent's side a wall). Launch height follows speed and ramp angle.
     * @return true if it launched you
     */
    private static boolean groundTrimp(IsoPlayer p) {
        double vx = vel.x, vy = vel.y;
        double vz = clipOnRamp(0);
        if (vz <= NON_JUMP || ceilingBlocked(p)) {
            vel.x = vx;
            vel.y = vy;
            return false;
        }
        p.setLastFallSpeed((float) -vz);
        grounded = false;
        jumpedThisAir = true;
        airStartNanos = System.nanoTime();
        airborneUnderMod = true;
        rememberLand(p);
        Net.jumped(false, 0);
        return true;
    }

    /**
     * Arriving on a ramp from the air: Source clips your whole velocity along it rather than stopping you, so
     * hitting a tent side or stairs while moving up it fast (or still rising from a jump) keeps you flying.
     */
    private static boolean rampBounce(IsoGameCharacter c) {
        if (!Cfg.trimp || !groundRamp(c)) return false;
        double vz = clipOnRamp(-fallSpeedBeforeFalling);
        if (vz <= NON_JUMP || ceilingBlocked(c)) return false;
        c.setLastFallSpeed((float) -vz);
        jumpedThisAir = true;
        return true;
    }

    /** Running up stairs, a slope or a tent side fast enough launches you. */
    private static void tryTrimp(IsoPlayer p) {
        if (groundRamp(p)) groundTrimp(p);
    }

    private static boolean ceilingBlocked(IsoGameCharacter c) {
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

    // ---------------------------------------------------------------- collision

    public static void onPostUpdateEnter(IsoMovingObject o) {
        postActive = owned && o == self;
        if (postActive) {
            preX = o.getX();
            preY = o.getY();
        }
    }

    public static void onPostUpdateExit(IsoMovingObject o) {
        if (!postActive || o != self) return;
        postActive = false;
        if (!(o.isCollidedN() || o.isCollidedS() || o.isCollidedE() || o.isCollidedW() || o.isCollidedWithVehicle())) return;
        // Rising toward something this jump will still clear: keep going instead of stopping dead on its face.
        if (willClear(self)) return;
        // Grid walls are axis-aligned: the engine zeroes the blocked axis, so do the same to velocity (ClipVelocity, overbounce 1).
        if (o.isCollidedN() || o.isCollidedS()) vel.y = 0;
        if (o.isCollidedE() || o.isCollidedW()) vel.x = 0;
        if (o.isCollidedWithVehicle()) {
            // Polygon obstacle (furniture/vehicle) redirected us: keep only the velocity along the actual motion.
            double ax = o.getX() - preX, ay = o.getY() - preY;
            double len = Math.hypot(ax, ay);
            if (len < 1e-5) {
                vel.x = vel.y = 0;
            } else {
                double ux = ax / len, uy = ay / len;
                double d = Math.max(0, vel.x * ux + vel.y * uy);
                vel.x = ux * d;
                vel.y = uy * d;
            }
        }
    }

    /** How far ahead (tiles) to look for what we ran into: the body radius (0.3) plus a little. */
    private static final double AHEAD = 0.45;

    /**
     * Still rising, and the rest of this jump gets our feet over what's just ahead (within step-up)? Then a
     * collision is only early contact: the engine holds us against the face until we're high enough, and our
     * speed carries us on over it. Without this, touching the side a moment before the apex (the body's radius
     * makes contact before the feet reach the edge) kills all speed and the jump falls back down.
     */
    private static boolean willClear(IsoGameCharacter c) {
        if (grounded) return false;
        double v = -c.getLastFallSpeed();
        if (v <= 0) return false;
        double need = obstacleAhead(c);
        if (Double.isNaN(need)) return false;
        double apex = c.getZ() + v * v / (2 * FallingConstants.IsoFallAcceleration);
        return apex >= need - Ledges.STEP_UP;
    }

    /**
     * Height (absolute Z) of the tallest thing just ahead along our velocity: edges out of this square,
     * solid props, car roofs. NaN if something there can't be jumped onto at all.
     */
    private static double obstacleAhead(IsoGameCharacter c) {
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        double s = vel.speed();
        if (cell == null || s < 1e-3) return Double.NaN;
        double px = c.getX() + vel.x / s * AHEAD, py = c.getY() + vel.y / s * AHEAD;
        int level = (int) Math.floor(c.getZ());
        int sx = (int) Math.floor(c.getX()), sy = (int) Math.floor(c.getY());
        double need = 0;
        for (int gx = (int) Math.floor(px - 0.3); gx <= (int) Math.floor(px + 0.3); gx++) {
            for (int gy = (int) Math.floor(py - 0.3); gy <= (int) Math.floor(py + 0.3); gy++) {
                if ((gx == sx && gy == sy) || Math.abs(gx - sx) > 1 || Math.abs(gy - sy) > 1) continue;
                double h = Ledges.crossingHeight(cell, level, sx, sy, gx, gy);
                IsoGridSquare sq = cell.getGridSquare(gx, gy, level);
                if (sq != null && (sq.isSolid() || sq.isSolidTrans())) {
                    double top = Props.entryTop(sq);
                    if (Double.isNaN(top)) return Double.NaN;
                    h = Math.max(h, top);
                }
                need = Math.max(need, h);
            }
        }
        for (BaseVehicle car : Rides.nearby(cell, (float) px, (float) py)) {
            if (!Rides.over(car, (float) px, (float) py, 0.3f)) continue;
            float roof = Rides.roofZ(car);
            if (Float.isNaN(roof)) return Double.NaN;
            need = Math.max(need, roof - level);
        }
        return level + need;
    }

    /** @return true to skip the sprint-into-wall stumble while we drive movement. */
    public static boolean onCheckHitWall(IsoMovingObject o) {
        return owned && o == self;
    }

    // ---------------------------------------------------------------- misc

    private static boolean isLocal(IsoPlayer p) {
        return p.isLocalPlayer() && p == IsoPlayer.getInstance();
    }

    private static double measuredSpeed, hudX, hudY;
    private static boolean hudSeen;

    /** Speed from how far you actually moved, smoothed over ~0.15 s: for the overlay when vanilla moves you. */
    private static void measureSpeed(IsoPlayer p, double dt) {
        if (hudSeen && dt > 0) {
            double m = Math.hypot(p.getX() - hudX, p.getY() - hudY) / dt;
            if (m < 200) measuredSpeed += (m - measuredSpeed) * Math.min(1, dt / 0.15); // skip teleports
        }
        hudX = p.getX();
        hudY = p.getY();
        hudSeen = true;
    }

    /** Horizontal speed for the speed overlay (tiles/s): ours while we drive you, else measured. */
    static double hudSpeed() {
        return owned ? Math.hypot(velX(), velY()) : measuredSpeed;
    }

    /** getGlobalMovementMod exit: no vanilla speed scaling (0.75 on stairs) while we drive your movement. */
    public static float onMovementMod(IsoPlayer p, float ret) {
        return p == self && owned ? 1f : ret;
    }

    /** Ground-relative velocity (tiles/s): ours plus the car we're riding. */
    static double velX() {
        if (ride == null) return vel.x;
        Rides.velocity(ride, carVel);
        return vel.x + carVel[0];
    }

    static double velY() {
        if (ride == null) return vel.y;
        Rides.velocity(ride, carVel);
        return vel.y + carVel[1];
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
        camLocked = false;
        animOn = false;
        ride = floorVehicle = null;
        hasLastLand = false;
        floorKind = FLOOR_GROUND;
    }

    private static String nearestRoof(IsoPlayer p) {
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (cell == null) return "";
        BaseVehicle best = null;
        double bestD = 4;
        for (BaseVehicle v : Rides.nearby(cell, p.getX(), p.getY())) {
            double d = Math.hypot(v.getX() - p.getX(), v.getY() - p.getY());
            if (d < bestD) { bestD = d; best = v; }
        }
        if (best == null) return "";
        float roof = Rides.roofZ(best);
        return String.format(" | nearest car roof %.2f lv (%s)", roof, best.getScript() != null ? best.getScript().getName() : "?");
    }

    static String status() {
        IsoPlayer p = self;
        if (p == null) return "Source movement: no player";
        double s = vel.speed();
        return String.format(
                "Source movement %s%s | owned=%s state=%s grounded=%s\n"
                        + "speed %.2f tiles/s (~%.0f u/s) | z=%.3f vz=%.2f\n"
                        + "learned max  sneak %.2f  walk %.2f  run %.2f  sprint %.2f\n"
                        + "floor: %s%s%s\n"
                        + "ahead: %s",
                Cfg.enabled ? "ON" : "OFF", !GameClient.client ? "" : !Net.serverReady ? " (waiting for server)"
                        : Net.serverJava ? " (multiplayer)" : " (multiplayer, server has no Java side)",
                owned, lastState, grounded,
                s, s / 0.01905, p.getZ(), -p.getLastFallSpeed(),
                maxSpeed[SNEAK], maxSpeed[WALK], maxSpeed[RUN], maxSpeed[SPRINT],
                floorKind == FLOOR_VEHICLE ? "car roof" : floorKind == FLOOR_FENCE ? (floorRail != null ? "stair rail" : "fence top")
                        : floorKind == FLOOR_PROP && floorProp != null ? floorProp.name + " (" + String.format("%.2f", p.getZ()) + " lv)" : "ground",
                nearestRoof(p),
                pulledAt != 0 && System.nanoTime() - pulledAt < 1_500_000_000L ? " | GRABBED" : "",
                aheadSquare(p));
    }

    /** The square about one step in front of you and what's in it, for identifying unjumpable props. */
    private static String aheadSquare(IsoPlayer p) {
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (cell == null) return "-";
        float fx = p.getForwardDirectionX(), fy = p.getForwardDirectionY();
        IsoGridSquare sq = cell.getGridSquare((int) Math.floor(p.getX() + fx * 0.8f),
                (int) Math.floor(p.getY() + fy * 0.8f), (int) Math.floor(p.getZ()));
        return Props.describe(sq);
    }
}

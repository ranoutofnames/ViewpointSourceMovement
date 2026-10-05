package sourcemove;

import zombie.WorldSoundManager;
import zombie.characters.IsoGameCharacter;
import zombie.characters.FallingConstants;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;

/** Fall damage, landing sounds, airborne footsteps, water landings. */
public final class Landing {
    private Landing() {}

    /** Where you last left dry land, for water landings far from shore. */
    static boolean hasLastLand;
    private static float lastLandX, lastLandY, lastLandZ;
    /** Max distance back to the last dry-land spot (tiles). */
    private static final double LAST_LAND_RANGE = 64;

    static void rememberLand(IsoGameCharacter c) {
        if (Floors.floorKind == Floors.FLOOR_VEHICLE || !Water.land(c.getCurrentSquare())) return;
        hasLastLand = true;
        lastLandX = c.getX();
        lastLandY = c.getY();
        lastLandZ = c.getZ();
    }

    /** Landed in water, back to the nearest shore or where you last left land. */
    static void leaveWater(IsoGameCharacter c) {
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
        Mover.vel.x = Mover.vel.y = 0;
    }

    private static boolean inLanding;

    /** Skip DoLand entirely in FALL_NONE. */
    public static boolean skipLanding(IsoGameCharacter c) {
        // Other mod users land on their own client.
        return Mover.modAir(c) && Cfg.fallMode == Cfg.FALL_NONE || Remote.fallsOverridden(c);
    }

    /** REASONABLE forgives safeDrop of the fall, vanilla damage for the rest. */
    public static float landingSpeed(IsoGameCharacter c, float speed) {
        if (!Mover.modAir(c) || Cfg.fallMode != Cfg.FALL_REASONABLE || speed <= 0) return speed;
        inLanding = true;
        return (float) Physics.forgiveDrop(speed, FallingConstants.IsoFallAcceleration, Cfg.safeDrop);
    }

    public static void onLandingDone(IsoGameCharacter c) {
        inLanding = false;
    }

    /** Skip the landing knockdown when it's off. */
    public static boolean skipKnockdown(IsoGameCharacter c) {
        return inLanding && c == Mover.self && !Cfg.fallKnockdown;
    }

    /** PZ's landing events, surface picked by the emitter's footstep material. */
    private static final String[] LAND_EVENTS = {null, "LandLight", "LandHeavy", "LandHeavyFromFall"};
    private static final int[] LAND_RADIUS = {0, 6, 12, 20};

    /** 0 = none, 1-3 = light, heavy, very heavy. */
    static int landingKind(float impact) {
        long airMs = (System.nanoTime() - Mover.airStartNanos) / 1_000_000L;
        if (!Mover.jumpedThisAir && airMs < 150) return 0; // stairs and small drops get no landing sound
        if (impact < FallingConstants.noDamageThreshold) return 1;
        return impact < FallingConstants.hardFallThreshold ? 2 : 3;
    }

    static void playLanding(IsoGameCharacter c, int kind) {
        int radius = LAND_RADIUS[kind];
        if (c.getEmitter() != null) c.getEmitter().playSoundImpl(LAND_EVENTS[kind], c);
        IsoGridSquare sq = c.getCurrentSquare();
        if (sq != null && sq.getRoom() != null) radius /= 2;
        WorldSoundManager.instance.addSound(c, (int) Math.floor(c.getX()), (int) Math.floor(c.getY()),
                (int) Math.floor(c.getZ()), radius, radius);
    }

    /** No footsteps in the air. */
    public static boolean onAnimFootstep(IsoGameCharacter c) {
        return Cfg.sounds && (c == Mover.self ? Mover.owned && !Mover.grounded : Remote.airborne(c));
    }
}

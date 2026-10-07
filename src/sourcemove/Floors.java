package sourcemove;

import java.lang.reflect.Field;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.Vector2;
import zombie.vehicles.BaseVehicle;

/** What you stand on besides the ground, fence tops, rails, props and car roofs. */
public final class Floors {
    private Floors() {}

    static final int FLOOR_GROUND = 0, FLOOR_FENCE = 1, FLOOR_VEHICLE = 2, FLOOR_PROP = 3;
    private static final float VEHICLE_FOOTING = 0.1f;
    /** How far past a prop's edge you can stand (tiles). */
    private static final double PROP_FOOTING = 0.15;
    static int floorKind = FLOOR_GROUND;
    static Props.Prop floorProp;
    /** The object that prop is. */
    static IsoObject floorObject;
    static int floorPropX, floorPropY;
    /** The stairs of the rail you stand on. */
    static IsoGridSquare floorRail;
    static BaseVehicle floorVehicle, ride;
    /** Top of the floor we found (levels), NaN on the ground. */
    private static double floorTop = Double.NaN;
    /** Inside self's updateFalling; only that query publishes the floor. */
    private static boolean querying, queried;
    private static float rideX, rideY;
    private static double rideYaw;
    private static final float[] roofOut = new float[1];
    static final double[] carVel = new double[2];

    /** A car roof is your ground entity, velocity is relative to it and you keep its momentum leaving. */
    static void updateRide() {
        BaseVehicle next = Mover.grounded && floorKind == FLOOR_VEHICLE ? floorVehicle : null;
        if (next == ride) return;
        if (ride != null) {
            Rides.velocity(ride, carVel);
            Mover.vel.x += carVel[0];
            Mover.vel.y += carVel[1];
        }
        if (next != null) {
            Rides.velocity(next, carVel);
            Mover.vel.x -= carVel[0];
            Mover.vel.y -= carVel[1];
            rideX = next.getX();
            rideY = next.getY();
            rideYaw = Rides.yaw(next);
        }
        ride = next;
    }

    /** How the car under you moved and turned this frame. */
    static void carry(IsoPlayer p, double[] out) {
        out[0] = out[1] = 0;
        if (ride == null || !Mover.grounded) return;
        float nx = ride.getX(), ny = ride.getY();
        double yaw = Rides.yaw(ride), turn = yaw - rideYaw, cos = Math.cos(turn), sin = Math.sin(turn);
        double rx = p.getX() - rideX, ry = p.getY() - rideY;
        out[0] = nx + rx * cos - ry * sin - p.getX();
        out[1] = ny + rx * sin + ry * cos - p.getY();
        double vx = Mover.vel.x;
        Mover.vel.x = vx * cos - Mover.vel.y * sin; // relative velocity turns with the car
        Mover.vel.y = vx * sin + Mover.vel.y * cos;
        rideX = nx;
        rideY = ny;
        rideYaw = yaw;
    }

    static final double[] carryOut = new double[2];

    /** testCollisionWithCharacter exit. Cars can't hit you on or above their roof. */
    public static Vector2 onVehicleHitTest(BaseVehicle v, IsoGameCharacter c, Vector2 ret) {
        // Also other mod users (the driver's client tests hits on riders).
        if (ret == null || (c == Mover.self ? !Mover.baseActive : !Remote.active(c))) return ret;
        float roof = Rides.roofZ(v);
        return !Float.isNaN(roof) && c.getZ() >= roof - Ledges.STEP_UP ? null : ret;
    }

    /** The footstep parameters' private character field. */
    private static final ClassValue<Field> FOOTSTEP_CHARACTER = new ClassValue<>() {
        @Override
        protected Field computeValue(Class<?> type) {
            try {
                Field f = type.getDeclaredField("character");
                f.setAccessible(true);
                return f;
            } catch (Throwable t) {
                return null;
            }
        }
    };

    /** A footstep parameter of ours while we stand on something besides the ground. */
    private static boolean offGround(Object param) {
        if (floorKind == FLOOR_GROUND || Mover.self == null) return false;
        Field f = FOOTSTEP_CHARACTER.get(param.getClass());
        try {
            return f != null && f.get(param) == Mover.self;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Footstep material, metal on cars, wood on fences, the prop's own on props. */
    public static float onFootstepMaterial(Object param, float ret) {
        if (!offGround(param)) return ret;
        if (floorKind == FLOOR_PROP && floorProp != null) return floorProp.material;
        return floorKind == FLOOR_VEHICLE ? 12f : 7f; // Metal / Wood
    }

    /** Second footstep layer, no ground puddles, glass or leaves up there. */
    public static float onFootstepMaterial2(Object param, float ret) {
        return offGround(param) ? 0f : ret; // None
    }

    static void beginQuery() {
        querying = true;
        queried = false;
    }

    /** No query this frame (climbing, seated) means no floor of ours. */
    static void endQuery() {
        querying = false;
        if (!queried) clearFloor();
    }

    private static void clearFloor() {
        floorKind = FLOOR_GROUND;
        floorVehicle = null;
        floorTop = Double.NaN;
    }

    /** Drops the floor, the car you ride and every object reference. */
    static void reset() {
        clearFloor();
        ride = null;
        floorProp = null;
        floorObject = null;
        floorRail = null;
    }

    /** getHeightAboveFloor exit. Fence tops, props and car roofs count as floor. */
    public static float onHeightAboveFloor(IsoGameCharacter c, float ret) {
        if (c != Mover.self) return ret;
        // Other callers (the shadow renderer) get the floor updateFalling found.
        if (!querying) return Double.isNaN(floorTop) ? ret : Math.min(ret, (float) (c.getZ() - floorTop));
        queried = true;
        clearFloor();
        if (!Mover.baseActive) return ret;
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (cell == null) return ret;
        double z = c.getZ();
        double best = -1;
        int kind = FLOOR_GROUND;
        // No step-up while rising, or a jump would land mid-air.
        double stepUp = c.getLastFallSpeed() < 0 ? 0 : Ledges.STEP_UP;
        double fence = Ledges.fenceTopUnder(cell, c.getX(), c.getY(), (int) Math.floor(z), Cfg.fenceFooting(), z + stepUp);
        IsoGridSquare rail = Ledges.lastRailStairs;
        if (fence >= 0 && z >= fence - stepUp) { // well below the top it's a wall, not a floor
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
        IsoObject object = null;
        if (Cfg.propMode != Props.MODE_OFF) {
            // Ramps catch you rising too, that landing is what trimps you.
            double top = Props.topUnder(cell, c.getX(), c.getY(), (int) Math.floor(z), z, stepUp, PROP_FOOTING);
            if (top > best) {
                best = top;
                kind = FLOOR_PROP;
                prop = Props.lastProp;
                object = Props.lastObject;
                floorPropX = Props.lastPropX;
                floorPropY = Props.lastPropY;
            }
        }
        if (best < 0 || z - best > ret) return ret;
        floorKind = kind;
        floorVehicle = kind == FLOOR_VEHICLE ? car : null;
        floorProp = prop;
        floorObject = object;
        floorTop = best;
        return (float) (z - best);
    }

    static String floorName(IsoGameCharacter c) {
        return floorKind == FLOOR_VEHICLE ? "car roof" : floorKind == FLOOR_FENCE ? (floorRail != null ? "stair rail" : "fence top")
                : floorKind == FLOOR_PROP && floorProp != null ? floorProp.name + " (" + String.format("%.2f", c.getZ()) + " lv)" : "ground";
    }
}

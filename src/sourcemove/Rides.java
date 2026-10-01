package sourcemove;

import java.util.ArrayList;

import org.joml.Vector3f;

import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.scripting.objects.VehicleScript;
import zombie.vehicles.BaseVehicle;

/**
 * Vehicle roofs as surfaces. Uses the same chassis box the engine uses for character collision
 * (BaseVehicle.testCollisionWithCharacter): local box centered at centerOfMassOffset with size extents,
 * both already scaled by the model scale. Physics units are tiles horizontally and 2.449 per Z-level.
 */
final class Rides {
    private Rides() {}

    private static final float LEVEL_METERS = 2.44949f;
    private static final float SEARCH = 6f;
    private static final Vector3f tmp = new Vector3f();
    private static final ArrayList<BaseVehicle> near = new ArrayList<>();

    /**
     * Cars whose center is within {@link #SEARCH} tiles of (x,y). Looks only in the chunks around (each
     * chunk lists the cars on it, as IsoGridSquare.getVehicleContainer uses), not at every loaded car.
     * The list is reused: don't hold on to it across calls.
     */
    static ArrayList<BaseVehicle> nearby(IsoCell cell, float x, float y) {
        near.clear();
        int size = IsoChunkMap.CHUNK_SIZE_IN_SQUARES;
        int cx0 = (int) Math.floor((x - SEARCH) / size), cx1 = (int) Math.floor((x + SEARCH) / size);
        int cy0 = (int) Math.floor((y - SEARCH) / size), cy1 = (int) Math.floor((y + SEARCH) / size);
        for (int cy = cy0; cy <= cy1; cy++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                IsoChunk chunk = cell.getChunk(cx, cy);
                if (chunk == null) continue;
                for (int i = 0; i < chunk.vehicles.size(); i++) {
                    BaseVehicle v = chunk.vehicles.get(i);
                    if (Math.abs(v.getX() - x) <= SEARCH && Math.abs(v.getY() - y) <= SEARCH) near.add(v);
                }
            }
        }
        return near;
    }

    /** Absolute Z (levels) of the vehicle's roof, or NaN if its physics transform isn't live. */
    static float roofZ(BaseVehicle v) {
        VehicleScript s = v.getScript();
        if (s == null) return Float.NaN;
        float originLevels = v.jniTransform.origin.y / LEVEL_METERS;
        if (Math.abs(originLevels - v.getZ()) > 1f) return Float.NaN; // transform not live (no physics nearby)
        Vector3f ext = s.getExtents(), com = s.getCenterOfMassOffset();
        return (v.jniTransform.origin.y + com.y + ext.y / 2f) / LEVEL_METERS + (float) Cfg.carRoofOffset;
    }

    /** Is (x,y) over the vehicle's chassis box, grown by {@code margin} tiles? */
    static boolean over(BaseVehicle v, float x, float y, float margin) {
        VehicleScript s = v.getScript();
        if (s == null) return false;
        Vector3f ext = s.getExtents(), com = s.getCenterOfMassOffset();
        Vector3f local = v.getLocalPos(x, y, 0f, tmp);
        return local.x > com.x - ext.x / 2f - margin && local.x < com.x + ext.x / 2f + margin
                && local.z > com.z - ext.z / 2f - margin && local.z < com.z + ext.z / 2f + margin;
    }

    /** Highest roof under (x,y) that your feet at {@code z} are on or above (within stepUp). */
    static BaseVehicle roofUnder(IsoCell cell, float x, float y, double z, float margin, double stepUp, float[] outRoof) {
        BaseVehicle best = null;
        float bestRoof = -1;
        ArrayList<BaseVehicle> cars = nearby(cell, x, y);
        for (int i = 0; i < cars.size(); i++) {
            BaseVehicle v = cars.get(i);
            float roof = roofZ(v);
            if (Float.isNaN(roof) || z < roof - stepUp || roof <= bestRoof) continue;
            if (!over(v, x, y, margin)) continue;
            best = v;
            bestRoof = roof;
        }
        outRoof[0] = bestRoof;
        return best;
    }

    /**
     * For a move to (x,y): -1 if a vehicle there is in your way, 1 if it overlaps vehicles and your feet
     * are above every roof (the vehicle is under you), 0 if it overlaps none.
     */
    static int vehiclesAt(IsoCell cell, float ox, float oy, float x, float y, double feet, float radius, double stepUp) {
        boolean any = false;
        ArrayList<BaseVehicle> cars = nearby(cell, x, y);
        for (int i = 0; i < cars.size(); i++) {
            BaseVehicle v = cars.get(i);
            if (!over(v, x, y, radius)) continue;
            // Moving away from it (running off its roof): never in your way.
            if (Math.hypot(x - v.getX(), y - v.getY()) > Math.hypot(ox - v.getX(), oy - v.getY()) + 1e-6) {
                any = true;
                continue;
            }
            float roof = roofZ(v);
            if (Float.isNaN(roof) || feet < roof - stepUp) return -1;
            any = true;
        }
        return any ? 1 : 0;
    }

    /** World-plane velocity (tiles/s) into {@code out}: physics x -> world x, physics z -> world y. */
    static void velocity(BaseVehicle v, double[] out) {
        Vector3f lv = v.getLinearVelocity(tmp);
        out[0] = lv.x;
        out[1] = lv.z;
    }

    /** Heading in the world plane (radians), for carrying a rider through turns. */
    static double yaw(BaseVehicle v) {
        Vector3f f = v.getForwardVector(tmp);
        return Math.atan2(f.z, f.x);
    }
}

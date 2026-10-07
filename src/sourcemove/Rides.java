package sourcemove;

import java.util.ArrayList;

import org.joml.Vector3f;

import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.scripting.objects.VehicleScript;
import zombie.vehicles.BaseVehicle;

/** Car roofs as surfaces, using the engine's chassis box for character collision. */
final class Rides {
    private Rides() {}

    private static final float SEARCH = 6f;
    private static final Vector3f tmp = new Vector3f();
    private static final ArrayList<BaseVehicle> near = new ArrayList<>();

    /** Cars within SEARCH of (x, y), from nearby chunks; the list is reused. */
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

    /** Roof Z (levels), NaN if the physics transform isn't live. */
    static float roofZ(BaseVehicle v) {
        VehicleScript s = v.getScript();
        if (s == null) return Float.NaN;
        float originLevels = (float) (v.jniTransform.origin.y / Physics.LEVEL_M);
        if (Math.abs(originLevels - v.getZ()) > 1f) return Float.NaN;
        Vector3f ext = s.getExtents(), com = s.getCenterOfMassOffset();
        return (float) ((v.jniTransform.origin.y + com.y + ext.y / 2f) / Physics.LEVEL_M + Cfg.carRoofOffset());
    }

    /** (x, y) over the chassis box grown by margin. */
    static boolean over(BaseVehicle v, float x, float y, float margin) {
        VehicleScript s = v.getScript();
        if (s == null) return false;
        Vector3f ext = s.getExtents(), com = s.getCenterOfMassOffset();
        Vector3f local = v.getLocalPos(x, y, 0f, tmp);
        return local.x > com.x - ext.x / 2f - margin && local.x < com.x + ext.x / 2f + margin
                && local.z > com.z - ext.z / 2f - margin && local.z < com.z + ext.z / 2f + margin;
    }

    /** Highest roof under (x, y) your feet are on or above. */
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

    /** Move to (x, y). -1 a car blocks, 1 you're above every car there, 0 none. */
    static int vehiclesAt(IsoCell cell, float ox, float oy, float x, float y, double feet, float radius, double stepUp) {
        boolean any = false;
        ArrayList<BaseVehicle> cars = nearby(cell, x, y);
        for (int i = 0; i < cars.size(); i++) {
            BaseVehicle v = cars.get(i);
            if (!over(v, x, y, radius)) continue;
            // Running off its roof never blocks.
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

    /** World velocity (tiles/s), physics x -> x, z -> y. */
    static void velocity(BaseVehicle v, double[] out) {
        Vector3f lv = v.getLinearVelocity(tmp);
        out[0] = lv.x;
        out[1] = lv.z;
    }

    static double yaw(BaseVehicle v) {
        Vector3f f = v.getForwardVector(tmp);
        return Math.atan2(f.z, f.x);
    }
}

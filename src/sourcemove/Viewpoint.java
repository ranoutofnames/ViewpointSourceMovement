package sourcemove;

import java.lang.reflect.Field;

/** Reflection link to Viewpoint's camera; inert if it changes. Nothing of Viewpoint is copied. */
public final class Viewpoint {
    private Viewpoint() {}

    private static boolean resolved;
    private static Field viewEnabled;

    private static boolean eyeResolved;
    /** Controls.eyeX/Y/Z, smoothed head offset from the feet (render meters). */
    private static Field offX, offY, offZ;
    /** Frame.eyeX/Y/Z, the eye Viewpoint renders from. */
    private static Field frameX, frameY, frameZ;

    private static void resolve() {
        resolved = true;
        try {
            viewEnabled = Class.forName("viewpoint.core.View").getField("enabled");
            Log.info("Viewpoint found: viewpoint.core.View.enabled");
        } catch (Throwable t) {
            viewEnabled = null;
            Log.warn("Viewpoint camera state not found (" + t + "); first-person-only mode will stay inactive");
        }
    }

    /** Viewpoint's own camera is on. */
    public static boolean active() {
        if (!resolved) resolve();
        if (viewEnabled == null) return false;
        try {
            return viewEnabled.getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean resolveEye() {
        if (eyeResolved) return offX != null;
        eyeResolved = true;
        try {
            Class<?> controls = Class.forName("viewpoint.input.Controls");
            Class<?> frame = Class.forName("viewpoint.core.Frame");
            offX = accessible(controls.getDeclaredField("eyeX"));
            offY = accessible(controls.getDeclaredField("eyeY"));
            offZ = accessible(controls.getDeclaredField("eyeZ"));
            frameX = frame.getField("eyeX");
            frameY = frame.getField("eyeY");
            frameZ = frame.getField("eyeZ");
            Log.info("Viewpoint eye offset found; jump camera stabilization available");
        } catch (Throwable t) {
            offX = null;
            Log.warn("Viewpoint eye offset not found (" + t + "); jump camera stabilization disabled");
        }
        return offX != null;
    }

    private static Field accessible(Field f) {
        f.setAccessible(true);
        return f;
    }

    /** Head offset {x, up, z}, or null. */
    static float[] eyeOffset() {
        if (!resolveEye()) return null;
        try {
            return new float[]{offX.getFloat(null), offY.getFloat(null), offZ.getFloat(null)};
        } catch (Throwable t) {
            return null;
        }
    }

    /** Replace this frame's head offset, in the eye and its smoothing. */
    static void overrideEye(Object frame, float x, float up, float z) {
        if (frame == null || !resolveEye()) return;
        try {
            float dx = x - offX.getFloat(null), dy = up - offY.getFloat(null), dz = z - offZ.getFloat(null);
            offX.setFloat(null, x);
            offY.setFloat(null, up);
            offZ.setFloat(null, z);
            frameX.setFloat(frame, frameX.getFloat(frame) + dx);
            frameY.setFloat(frame, frameY.getFloat(frame) + dy);
            frameZ.setFloat(frame, frameZ.getFloat(frame) + dz);
        } catch (Throwable t) {
            offX = null; // stop trying; logged once below
            Log.warn("jump camera stabilization failed (" + t + "); disabled");
        }
    }
}

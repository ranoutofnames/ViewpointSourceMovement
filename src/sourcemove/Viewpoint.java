package sourcemove;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

/** Reflection link to Viewpoint's camera; inert if it changes. Nothing of Viewpoint is copied. */
public final class Viewpoint {
    private Viewpoint() {}

    private static boolean resolved;
    private static Field viewEnabled;

    /** Movement.model, 0 = Turn to move, 1 = Strafe. */
    private static final int STRAFE = 1;
    private static boolean modelResolved;
    private static Object model;
    private static Method modelGet, modelSet;
    private static boolean forcing;
    /** The player's own choice, while we hold Strafe. */
    private static int modelWas = -1;

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

    private static boolean resolveModel() {
        if (modelResolved) return model != null;
        modelResolved = true;
        try {
            Field f = Class.forName("viewpoint.input.Controls").getDeclaredField("MODEL");
            f.setAccessible(true);
            model = f.get(null);
            modelGet = model.getClass().getMethod("get");
            modelSet = model.getClass().getMethod("set", int.class);
            // Greys the option out in Viewpoint's menu while we hold it.
            BooleanSupplier free = () -> !forcing;
            model.getClass().getMethod("enabledWhen", BooleanSupplier.class).invoke(model, free);
        } catch (Throwable t) {
            if (modelGet == null || modelSet == null) model = null;
            Log.warn("Viewpoint movement model not found (" + t + "); Strafe is not forced");
        }
        return model != null;
    }

    /** Holds Viewpoint on Strafe while we drive, and gives the player's choice back after. */
    static void holdStrafe(boolean on) {
        if (!on && !forcing) return;
        if (!resolveModel()) return;
        try {
            int now = (Integer) modelGet.invoke(model);
            if (on) {
                forcing = true;
                if (now != STRAFE) {
                    if (modelWas < 0) modelWas = now;
                    modelSet.invoke(model, STRAFE);
                }
            } else {
                forcing = false;
                if (modelWas >= 0 && now == STRAFE) modelSet.invoke(model, modelWas);
                modelWas = -1;
            }
        } catch (Throwable t) {
            model = null;
            forcing = false;
            Log.warn("Viewpoint movement model failed (" + t + "); Strafe is not forced");
        }
    }
}

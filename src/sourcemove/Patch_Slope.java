package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.iso.IsoMovingObject;

/** Slopes pin you to their surface; not mid-jump. */
@Patch(className = "zombie.iso.IsoMovingObject", methodName = "handleSlopedSurface")
public class Patch_Slope {
    @Patch.OnEnter
    public static void enter(@Patch.This IsoMovingObject self) {
        Ramps.onSurfaceSnapEnter(self);
    }

    @Patch.OnExit(onThrowable = Throwable.class)
    public static void exit(@Patch.This IsoMovingObject self) {
        Ramps.onSurfaceSnapExit(self);
    }
}

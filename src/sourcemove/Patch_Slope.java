package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.iso.IsoMovingObject;

/** Sloped surfaces pin you to their surface every frame; not while you're in a jump. */
@Patch(className = "zombie.iso.IsoMovingObject", methodName = "handleSlopedSurface")
public class Patch_Slope {
    @Patch.OnEnter
    public static void enter(@Patch.This IsoMovingObject self) {
        Mover.onSurfaceSnapEnter(self);
    }

    @Patch.OnExit(onThrowable = Throwable.class)
    public static void exit(@Patch.This IsoMovingObject self) {
        Mover.onSurfaceSnapExit(self);
    }
}

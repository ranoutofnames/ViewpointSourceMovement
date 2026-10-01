package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.iso.IsoMovingObject;

/** Stairs pin you to their surface every frame (within 0.95 level); not while you're in a jump. */
@Patch(className = "zombie.iso.IsoMovingObject", methodName = "doStairs")
public class Patch_Stairs {
    @Patch.OnEnter
    public static void enter(@Patch.This IsoMovingObject self) {
        Mover.onSurfaceSnapEnter(self);
    }

    @Patch.OnExit(onThrowable = Throwable.class)
    public static void exit(@Patch.This IsoMovingObject self) {
        Mover.onSurfaceSnapExit(self);
    }
}

package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.iso.IsoMovingObject;

/** Clip velocity against what the engine blocked. */
@Patch(className = "zombie.iso.IsoMovingObject", methodName = "postupdate")
public class Patch_PostUpdate {
    @Patch.OnEnter
    public static void enter(@Patch.This IsoMovingObject self) {
        Collide.onPostUpdateEnter(self);
    }

    @Patch.OnExit
    public static void exit(@Patch.This IsoMovingObject self) {
        Collide.onPostUpdateExit(self);
    }
}

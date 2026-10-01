package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.iso.IsoMovingObject;

/** No sprint-into-wall stumble while Source movement drives the player. */
@Patch(className = "zombie.iso.IsoMovingObject", methodName = "checkHitWall")
public class Patch_CheckHitWall {
    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This IsoMovingObject self) {
        return Mover.onCheckHitWall(self);
    }
}

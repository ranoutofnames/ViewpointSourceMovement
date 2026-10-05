package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;

/** Collisions the game has no flag for (misplaced escalator panels). */
@Patch(className = "zombie.iso.IsoGridSquare", methodName = "testCollideAdjacent")
public class Patch_CollideAdjacent {
    @Patch.OnExit
    public static void exit(@Patch.This IsoGridSquare self, @Patch.Argument(0) IsoMovingObject obj, @Patch.Argument(1) int x,
            @Patch.Argument(2) int y, @Patch.Argument(3) int z, @Patch.Return(readOnly = false) boolean ret) {
        ret = Collide.onCollideAdjacent(self, obj, x, y, z, ret);
    }
}

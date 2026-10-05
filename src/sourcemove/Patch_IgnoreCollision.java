package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;
import zombie.iso.IsoGridSquare;

/** Let the player over what their feet are above. */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "shouldIgnoreCollisionWithSquare")
public class Patch_IgnoreCollision {
    @Patch.OnExit
    public static void exit(@Patch.This IsoGameCharacter self, @Patch.Argument(0) IsoGridSquare square,
            @Patch.Return(readOnly = false) boolean ret) {
        ret = Collide.onIgnoreCollision(self, square, ret);
    }
}

package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;

/** Frame start, Source velocity instead of root motion. */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "doDeferredMovement")
public class Patch_DeferredMovement {
    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This IsoGameCharacter self) {
        return Mover.onDeferredMovement(self);
    }
}

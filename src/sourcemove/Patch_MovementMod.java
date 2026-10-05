package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoPlayer;

/** No 0.75 stair slowdown. */
@Patch(className = "zombie.characters.IsoPlayer", methodName = "getGlobalMovementMod")
public class Patch_MovementMod {
    @Patch.OnExit
    public static void exit(@Patch.This IsoPlayer self, @Patch.Return(readOnly = false) float ret) {
        ret = Mover.onMovementMod(self, ret);
    }
}

package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoPlayer;

/** Capture the wish direction (after Viewpoint rotates it). */
@Patch(className = "zombie.characters.IsoPlayer", methodName = "updateMovementFromInput")
public class Patch_Input {
    @Patch.OnExit
    public static void exit(@Patch.This IsoPlayer self) {
        Mover.onInput(self);
    }
}

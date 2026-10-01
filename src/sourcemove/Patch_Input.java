package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoPlayer;

/** After input is read (and rotated by Viewpoint), capture the world-space wish direction. */
@Patch(className = "zombie.characters.IsoPlayer", methodName = "updateMovementFromInput")
public class Patch_Input {
    @Patch.OnExit
    public static void exit(@Patch.This IsoPlayer self) {
        Mover.onInput(self);
    }
}

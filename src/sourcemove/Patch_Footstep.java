package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;

/** Silent walk-cycle footsteps in the air; the argument keeps our takeoff step's overload unpatched. */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "DoFootstepSound")
public class Patch_Footstep {
    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This IsoGameCharacter self, @Patch.Argument(0) String type) {
        return Landing.onAnimFootstep(self);
    }
}

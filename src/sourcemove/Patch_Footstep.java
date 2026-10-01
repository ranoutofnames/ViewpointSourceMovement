package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;

/**
 * Animation footsteps (DoFootstepSound(String), fired by the walk cycle's "Footstep" events): silent while
 * airborne. The @Argument keeps ZombieBuddy from also matching the DoFootstepSound(float) overload,
 * which we call ourselves for the takeoff step.
 */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "DoFootstepSound")
public class Patch_Footstep {
    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This IsoGameCharacter self, @Patch.Argument(0) String type) {
        return Mover.onAnimFootstep(self);
    }
}

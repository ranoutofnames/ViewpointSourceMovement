package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;

/**
 * DoLand(impactSpeed) -> handleLandingImpact is where all landing damage, injuries, pain and knockdowns
 * happen. Fall mode NONE skips it; REASONABLE shrinks the impact speed by the configured safe drop height.
 */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "DoLand")
public class Patch_DoLand {
    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This IsoGameCharacter self, @Patch.Argument(value = 0, readOnly = false) float speed) {
        if (Mover.skipLanding(self)) return true;
        speed = Mover.landingSpeed(self, speed);
        return false;
    }

    @Patch.OnExit
    public static void exit(@Patch.This IsoGameCharacter self) {
        Mover.onLandingDone(self);
    }
}

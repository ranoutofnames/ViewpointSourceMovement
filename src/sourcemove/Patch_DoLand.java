package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;

/** Fall damage, skipped in NONE and reduced in REASONABLE. */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "DoLand")
public class Patch_DoLand {
    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This IsoGameCharacter self, @Patch.Argument(value = 0, readOnly = false) float speed) {
        if (Landing.skipLanding(self)) return true;
        speed = Landing.landingSpeed(self, speed);
        return false;
    }

    @Patch.OnExit(onThrowable = Throwable.class)
    public static void exit(@Patch.This IsoGameCharacter self) {
        Landing.onLandingDone(self);
    }
}

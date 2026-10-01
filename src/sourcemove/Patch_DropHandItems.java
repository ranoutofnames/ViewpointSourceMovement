package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;

/** Hard landings drop held items; skipped together with the knockdown when that's turned off. */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "dropHandItems")
public class Patch_DropHandItems {
    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This IsoGameCharacter self) {
        return Mover.skipKnockdown(self);
    }
}

package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;

/** Hard-landing knockdown (both overloads), skipped during our landings when it's turned off. */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "fallenOnKnees")
public class Patch_Knockdown {
    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This IsoGameCharacter self) {
        return Mover.skipKnockdown(self);
    }
}

package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;

/** Hard-landing knockdown, skipped when off. */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "fallenOnKnees")
public class Patch_Knockdown {
    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This IsoGameCharacter self) {
        return Landing.skipKnockdown(self);
    }
}

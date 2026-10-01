package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;

/** isFalling() drives the animation graph's "bfalling"; hide it so the falling state never plays. */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "isFalling")
public class Patch_IsFalling {
    @Patch.OnExit
    public static void exit(@Patch.This IsoGameCharacter self, @Patch.Return(readOnly = false) boolean ret) {
        ret = Mover.onIsFalling(self, ret);
    }
}

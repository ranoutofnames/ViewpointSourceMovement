package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;

/** No falling animation. */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "isFalling")
public class Patch_IsFalling {
    @Patch.OnExit
    public static void exit(@Patch.This IsoGameCharacter self, @Patch.Return(readOnly = false) boolean ret) {
        ret = Mover.onIsFalling(self, ret);
    }
}

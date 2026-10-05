package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;

/** Gravity, for ceilings, ground state and no fall states. */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "updateFalling")
public class Patch_UpdateFalling {
    @Patch.OnEnter
    public static void enter(@Patch.This IsoGameCharacter self) {
        Mover.onUpdateFallingEnter(self);
    }

    @Patch.OnExit
    public static void exit(@Patch.This IsoGameCharacter self) {
        Mover.onUpdateFallingExit(self);
    }
}

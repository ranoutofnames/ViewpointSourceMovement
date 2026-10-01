package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;

/** The engine's "where is the floor" for gravity: fence tops under your feet count. */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "getHeightAboveFloor")
public class Patch_HeightAboveFloor {
    @Patch.OnExit
    public static void exit(@Patch.This IsoGameCharacter self, @Patch.Return(readOnly = false) float ret) {
        ret = Mover.onHeightAboveFloor(self, ret);
    }
}

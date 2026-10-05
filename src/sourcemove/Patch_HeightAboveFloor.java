package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;

/** Fence tops, props and car roofs count as floor. */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "getHeightAboveFloor")
public class Patch_HeightAboveFloor {
    @Patch.OnExit
    public static void exit(@Patch.This IsoGameCharacter self, @Patch.Return(readOnly = false) float ret) {
        ret = Floors.onHeightAboveFloor(self, ret);
    }
}

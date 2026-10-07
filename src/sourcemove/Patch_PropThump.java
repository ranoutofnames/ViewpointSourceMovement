package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;

/** In MP the server breaks the prop you stand on. */
@Patch(className = "zombie.iso.IsoObject", methodName = "Thump")
public class Patch_PropThump {
    @Patch.OnEnter
    public static void enter(@Patch.This IsoObject self, @Patch.Argument(0) IsoMovingObject thumper) {
        Zombies.onThump(self, thumper);
    }
}

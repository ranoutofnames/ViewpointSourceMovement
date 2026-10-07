package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;
import zombie.iso.IsoObject;
import zombie.iso.objects.interfaces.Thumpable;

/** Zombies after you can thump the prop you stand on, like moved furniture. */
@Patch(className = "zombie.iso.IsoObject", methodName = "getThumpableFor")
public class Patch_PropThumpable {
    @Patch.OnExit
    public static void exit(@Patch.This IsoObject self, @Patch.Argument(0) IsoGameCharacter chr,
            @Patch.Return(readOnly = false) Thumpable ret) {
        ret = Zombies.onThumpableFor(self, chr, ret);
    }
}

package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoPlayer;

/** Client shows other mod users at their real height and speed. */
@Patch(className = "zombie.characters.IsoPlayer", methodName = "update")
public class Patch_PlayerUpdate {
    @Patch.OnEnter
    public static void enter(@Patch.This IsoPlayer self) {
        Remote.onUpdateEnter(self);
    }

    @Patch.OnExit(onThrowable = Throwable.class)
    public static void exit(@Patch.This IsoPlayer self) {
        Remote.onUpdateExit(self);
    }
}

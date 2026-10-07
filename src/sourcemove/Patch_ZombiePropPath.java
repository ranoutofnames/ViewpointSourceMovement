package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;
import zombie.pathfind.PathFindBehavior2;

/** Zombies path beside the prop you stand on; its square has no way in. WalkTowardState calls this every tick. */
@Patch(className = "zombie.pathfind.PathFindBehavior2", methodName = "pathToCharacter")
public class Patch_ZombiePropPath {
    @Patch.OnExit
    public static void exit(@Patch.This PathFindBehavior2 self, @Patch.Argument(0) IsoGameCharacter target) {
        Zombies.onPathToCharacter(self, target);
    }
}

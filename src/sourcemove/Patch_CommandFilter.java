package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;

/** The server rebuilds its cmd.txt filter on /reloadoptions and /changeoption, so ours goes back in. */
@Patch(className = "zombie.network.GameServer", methodName = "initClientCommandFilter")
public class Patch_CommandFilter {
    @Patch.OnExit
    public static void exit() {
        Server.quietCommandLog();
    }
}

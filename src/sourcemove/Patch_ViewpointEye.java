package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;

/** Hold the head still through the leap animation; Object keeps us free of a Viewpoint compile dependency. */
@Patch(className = "viewpoint.input.Controls", methodName = "eye")
public class Patch_ViewpointEye {
    @Patch.OnExit
    public static void exit(@Patch.Argument(0) IsoGameCharacter chr, @Patch.Argument(1) Object frame) {
        JumpCam.onViewpointEye(chr, frame);
    }
}

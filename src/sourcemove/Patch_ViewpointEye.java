package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;

/**
 * Viewpoint puts the first-person eye at the head bone, so the leap animation swings the camera. After it
 * computes the eye each frame, hold the head offset from takeoff for the duration of the jump. The frame is
 * typed Object so there is no compile-time dependency on Viewpoint; if it changes, this patch just won't match.
 */
@Patch(className = "viewpoint.input.Controls", methodName = "eye")
public class Patch_ViewpointEye {
    @Patch.OnExit
    public static void exit(@Patch.Argument(0) IsoGameCharacter chr, @Patch.Argument(1) Object frame) {
        Mover.onViewpointEye(chr, frame);
    }
}

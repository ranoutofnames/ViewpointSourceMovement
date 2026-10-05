package sourcemove;

import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;

/** Holds the head still through the jump leap animation. */
public final class JumpCam {
    private JumpCam() {}

    static boolean camLocked;
    private static float camX, camUp, camZ;
    private static double camAngle;
    static long camReleaseAt;

    private static double facing(IsoPlayer p) {
        return Math.atan2(p.getForwardDirectionY(), p.getForwardDirectionX());
    }

    static void lockCamera(IsoPlayer p) {
        float[] off = Viewpoint.eyeOffset();
        if (off == null) return;
        camX = off[0];
        camUp = off[1];
        camZ = off[2];
        camAngle = facing(p);
        camReleaseAt = Long.MAX_VALUE;
        camLocked = true;
    }

    /** Hold the takeoff head offset, turned with the body. */
    public static void onViewpointEye(IsoGameCharacter c, Object frame) {
        if (!camLocked || c != Mover.self) return;
        if (!Cfg.stableJumpCam || System.nanoTime() > camReleaseAt) {
            camLocked = false;
            return;
        }
        double a = facing(Mover.self) - camAngle, cos = Math.cos(a), sin = Math.sin(a);
        Viewpoint.overrideEye(frame, (float) (camX * cos - camZ * sin), camUp, (float) (camX * sin + camZ * cos));
    }
}

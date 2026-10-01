package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;
import zombie.iso.Vector2;
import zombie.vehicles.BaseVehicle;

/** Moving vehicles hit characters inside their box; not the player standing on (or above) the roof. */
@Patch(className = "zombie.vehicles.BaseVehicle", methodName = "testCollisionWithCharacter")
public class Patch_VehicleHit {
    @Patch.OnExit
    public static void exit(@Patch.This BaseVehicle self, @Patch.Argument(0) IsoGameCharacter chr,
            @Patch.Return(readOnly = false) Vector2 ret) {
        ret = Mover.onVehicleHitTest(self, chr, ret);
    }
}

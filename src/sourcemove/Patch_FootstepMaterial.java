package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.audio.parameters.ParameterFootstepMaterial;

/** Footstep material on cars, fences and props. */
@Patch(className = "zombie.audio.parameters.ParameterFootstepMaterial", methodName = "calculateCurrentValue")
public class Patch_FootstepMaterial {
    @Patch.OnExit
    public static void exit(@Patch.This ParameterFootstepMaterial self, @Patch.Return(readOnly = false) float ret) {
        ret = Floors.onFootstepMaterial(self, ret);
    }
}

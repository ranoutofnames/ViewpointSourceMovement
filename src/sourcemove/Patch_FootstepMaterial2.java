package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.audio.parameters.ParameterFootstepMaterial2;

/** No ground puddles, glass or leaves on cars, fences and props. */
@Patch(className = "zombie.audio.parameters.ParameterFootstepMaterial2", methodName = "calculateCurrentValue")
public class Patch_FootstepMaterial2 {
    @Patch.OnExit
    public static void exit(@Patch.This ParameterFootstepMaterial2 self, @Patch.Return(readOnly = false) float ret) {
        ret = Floors.onFootstepMaterial2(self, ret);
    }
}

package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.audio.parameters.ParameterFootstepMaterial;

/** The FMOD FootstepMaterial parameter only looks at the floor tile: metal on car roofs, wood on fence tops. */
@Patch(className = "zombie.audio.parameters.ParameterFootstepMaterial", methodName = "calculateCurrentValue")
public class Patch_FootstepMaterial {
    @Patch.OnExit
    public static void exit(@Patch.This ParameterFootstepMaterial self, @Patch.Return(readOnly = false) float ret) {
        ret = Mover.onFootstepMaterial(self, ret);
    }
}

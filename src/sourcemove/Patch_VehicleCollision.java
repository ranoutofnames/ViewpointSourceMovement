package sourcemove;

import org.joml.Vector2f;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;

/** PolygonalMap2.resolveCollision pushes characters out of vehicle footprints; not when the car is under your feet. */
@Patch(className = "zombie.pathfind.PolygonalMap2", methodName = "resolveCollision")
public class Patch_VehicleCollision {
    @Patch.OnExit
    public static void exit(@Patch.Argument(0) IsoGameCharacter chr, @Patch.Argument(1) float nx, @Patch.Argument(2) float ny,
            @Patch.Return Vector2f result) {
        Mover.onResolveCollision(chr, nx, ny, result);
    }
}

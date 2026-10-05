package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.network.packets.character.PlayerPacket;

/** Client predicts along our real velocity. */
@Patch(className = "zombie.characters.NetworkPlayerAI", methodName = "set")
public class Patch_Prediction {
    @Patch.OnExit
    public static void exit(@Patch.Argument(0) PlayerPacket packet) {
        Net.onPrediction(packet);
    }
}

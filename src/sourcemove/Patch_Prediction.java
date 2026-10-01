package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.network.packets.character.PlayerPacket;

/** Client: the position update we send predicts along our real velocity, not the discarded root motion. */
@Patch(className = "zombie.characters.NetworkPlayerAI", methodName = "set")
public class Patch_Prediction {
    @Patch.OnExit
    public static void exit(@Patch.Argument(0) PlayerPacket packet) {
        Net.onPrediction(packet);
    }
}

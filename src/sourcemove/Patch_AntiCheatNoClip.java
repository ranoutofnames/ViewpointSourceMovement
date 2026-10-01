package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.core.raknet.UdpConnection;
import zombie.network.packets.INetworkPacket;

/** Server: let through fence, prop, window and roof crossings a recent jump could make. */
@Patch(className = "zombie.network.anticheats.AntiCheatNoClip", methodName = "validate")
public class Patch_AntiCheatNoClip {
    @Patch.OnExit
    public static void exit(@Patch.Argument(0) UdpConnection connection, @Patch.Argument(1) INetworkPacket packet,
                            @Patch.Return(readOnly = false) String ret) {
        ret = Server.noClip(connection, packet, ret);
    }
}

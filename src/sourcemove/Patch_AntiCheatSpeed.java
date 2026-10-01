package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.core.raknet.UdpConnection;
import zombie.network.packets.INetworkPacket;

/** Server: the on-foot speed limit for players using Source movement comes from the sandbox settings. */
@Patch(className = "zombie.network.anticheats.AntiCheatSpeed", methodName = "validate")
public class Patch_AntiCheatSpeed {
    @Patch.OnExit
    public static void exit(@Patch.Argument(0) UdpConnection connection, @Patch.Argument(1) INetworkPacket packet,
                            @Patch.Return(readOnly = false) String ret) {
        ret = Server.speed(connection, packet, ret);
    }
}

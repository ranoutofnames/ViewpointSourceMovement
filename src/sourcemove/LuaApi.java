package sourcemove;

import se.krka.kahlua.integration.annotations.LuaMethod;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoObject;

/** Global Lua functions for the client and server scripts. */
public class LuaApi {
    public LuaApi() {}

    @LuaMethod(name = "SourceMove_setBool", global = true)
    public static void setBool(String key, boolean v) {
        switch (key) {
            case "enabled" -> {
                if (Cfg.enabled != v) Mover.softReset();
                Cfg.enabled = v;
            }
            case "fpOnly" -> Cfg.fpOnly = v;
            case "jumpAnim" -> Cfg.jumpAnim = v;
            case "wheelJump" -> Cfg.wheelJump = v;
            case "wireHud" -> Cfg.wireHud = v;
            default -> Log.warn("unknown bool setting " + key);
        }
    }

    @LuaMethod(name = "SourceMove_setNumber", global = true)
    public static void setNumber(String key, double v) {
        switch (key) {
            case "jumpKey" -> Cfg.jumpKey = (int) v;
            default -> Log.warn("unknown number setting " + key);
        }
    }

    /** Client gets a server command, id = sender's online ID. */
    @LuaMethod(name = "SourceMove_clientCommand", global = true)
    public static void clientCommand(String command, double id, String d) {
        Net.onServerCommand(command, id, d);
    }

    /** Client forgets the last game and server (game start, main menu). */
    @LuaMethod(name = "SourceMove_reset", global = true)
    public static void reset() {
        Net.reset();
        Mover.resetSession();
    }

    /** Server started. */
    @LuaMethod(name = "SourceMove_serverStart", global = true)
    public static void serverStart() {
        Server.quietCommandLog();
    }

    /** Server break check, only plain props and never built or powered things. */
    @LuaMethod(name = "SourceMove_isProp", global = true)
    public static boolean isProp(IsoObject obj) {
        return obj != null && obj.getClass() == IsoObject.class;
    }

    /** Server hears a mod client's hello. */
    @LuaMethod(name = "SourceMove_serverHello", global = true)
    public static void serverHello(IsoPlayer player) {
        Server.hello(player);
    }

    /** Server gets a client's state report, for the anti-cheat. */
    @LuaMethod(name = "SourceMove_serverState", global = true)
    public static void serverState(IsoPlayer player, String d) {
        Server.report(player, d);
    }

    @LuaMethod(name = "SourceMove_speed", global = true)
    public static double speed() {
        return Status.hudSpeed();
    }

    /** Collision overlay at (x, y). */
    @LuaMethod(name = "SourceMove_drawWire", global = true)
    public static void drawWire(double x, double y) {
        Wire.draw(x, y);
    }

    @LuaMethod(name = "SourceMove_status", global = true)
    public static String status() {
        return Status.status();
    }
}

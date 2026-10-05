package sourcemove;

import se.krka.kahlua.integration.annotations.LuaMethod;
import zombie.characters.IsoPlayer;

/** Global Lua functions for the client and server scripts. */
public class LuaApi {
    public LuaApi() {}

    @LuaMethod(name = "SourceMove_setBool", global = true)
    public static void setBool(String key, boolean v) {
        switch (key) {
            case "enabled" -> Cfg.enabled = v;
            case "fpOnly" -> Cfg.fpOnly = v;
            case "fallKnockdown" -> Cfg.fallKnockdown = v;
            case "jumpAnim" -> Cfg.jumpAnim = v;
            case "stableJumpCam" -> Cfg.stableJumpCam = v;
            case "exertionWeight" -> Cfg.exertionWeight = v;
            case "tiredJumps" -> Cfg.tiredJumps = v;
            case "heavyJumps" -> Cfg.heavyJumps = v;
            case "exhaustedNoJump" -> Cfg.exhaustedNoJump = v;
            case "autohop" -> Cfg.autohop = v;
            case "wheelJump" -> Cfg.wheelJump = v;
            case "debugHud" -> Cfg.debugHud = v;
            case "wireHud" -> Cfg.wireHud = v;
            case "sounds" -> Cfg.sounds = v;
            case "pulldown" -> Cfg.pulldown = v;
            case "windowJump" -> Cfg.windowJump = v;
            case "windowCrash" -> Cfg.windowCrash = v;
            case "windowDamage" -> Cfg.windowDamage = v;
            case "barbedWire" -> Cfg.barbedWire = v;
            case "trimp" -> Cfg.trimp = v;
            default -> Log.warn("unknown bool setting " + key);
        }
    }

    @LuaMethod(name = "SourceMove_setNumber", global = true)
    public static void setNumber(String key, double v) {
        switch (key) {
            case "jumpKey" -> Cfg.jumpKey = (int) v;
            case "jumpBufferMs" -> Cfg.jumpBufferMs = v;
            case "autohopGroundTicks" -> Cfg.autohopGroundTicks = (int) Math.round(v);
            case "tickrate" -> Cfg.tickrate = v;
            case "accelerate" -> Cfg.accelerate = v;
            case "airAccelerate" -> Cfg.airAccelerate = v;
            case "friction" -> Cfg.friction = v;
            case "jumpHeight" -> Cfg.jumpHeight = v;
            case "maxSpeed" -> Cfg.maxSpeed = v;
            case "fallMode" -> Cfg.fallMode = (int) Math.round(v);
            case "safeDrop" -> Cfg.safeDrop = v;
            case "fenceFooting" -> Cfg.fenceFooting = v;
            case "carRoofOffset" -> Cfg.carRoofOffset = v;
            case "windowCrashSpeed" -> Cfg.windowCrashSpeed = v;
            case "propMode" -> Cfg.propMode = (int) Math.round(v);
            case "zombieReach" -> Cfg.zombieReach = v;
            case "pulldownRate" -> Cfg.pulldownRate = v;
            case "pulldownGroup" -> Cfg.pulldownGroup = (int) Math.round(v);
            case "pulldownRange" -> Cfg.pulldownRange = v;
            case "pulldownKeep" -> Cfg.pulldownKeep = v;
            case "pulldownLockout" -> Cfg.pulldownLockout = v;
            case "jumpExertion" -> Cfg.jumpExertion = v;
            case "mpSpeedLimit" -> Cfg.mpSpeedLimit = v;
            default -> Log.warn("unknown number setting " + key);
        }
    }

    /** Client gets a server command, id = sender's online ID. */
    @LuaMethod(name = "SourceMove_clientCommand", global = true)
    public static void clientCommand(String command, double id, String d) {
        Net.onServerCommand(command, id, d);
    }

    /** Client forgets the last server (game start). */
    @LuaMethod(name = "SourceMove_netReset", global = true)
    public static void netReset() {
        Net.reset();
    }

    /** Server hears a mod client's hello, returns our version. */
    @LuaMethod(name = "SourceMove_serverHello", global = true)
    public static String serverHello(IsoPlayer player) {
        Server.hello(player);
        return Server.VERSION;
    }

    /** Server gets a client's state report, for the anti-cheat. */
    @LuaMethod(name = "SourceMove_serverState", global = true)
    public static void serverState(IsoPlayer player, String d) {
        Server.report(player, d);
    }

    @LuaMethod(name = "SourceMove_isEnabled", global = true)
    public static boolean isEnabled() {
        return Cfg.enabled;
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

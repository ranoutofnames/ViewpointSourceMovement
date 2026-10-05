-- Pushes Sandbox gameplay settings to Java (client and server).

-- Same names in SandboxVars.ViewpointSourceMovement.
local SANDBOX_BOOLS = {
    "autohop", "trimp", "sounds", "windowJump", "windowCrash", "windowDamage", "barbedWire", "fallKnockdown", "pulldown", "exertionWeight", "tiredJumps", "heavyJumps", "exhaustedNoJump",
}
local SANDBOX_NUMBERS = {
    "jumpBufferMs", "jumpHeight", "fenceFooting", "carRoofOffset", "windowCrashSpeed", "safeDrop", "zombieReach",
    "pulldownGroup", "pulldownKeep", "pulldownLockout", "jumpExertion", "airAccelerate", "accelerate", "friction", "tickrate", "maxSpeed",
    "mpSpeedLimit",
}

--- @return boolean false without the Java side
function SourceMovement_pushSandbox()
    if not SourceMove_setBool or not SourceMove_setNumber then return false end
    local sv = SandboxVars and SandboxVars.ViewpointSourceMovement
    if not sv then return true end
    for _, key in ipairs(SANDBOX_BOOLS) do
        if sv[key] ~= nil then SourceMove_setBool(key, sv[key] == true) end
    end
    for _, key in ipairs(SANDBOX_NUMBERS) do
        local v = tonumber(sv[key])
        if v then SourceMove_setNumber(key, v) end
    end
    -- 1-based None, Reasonable, Vanilla
    SourceMove_setNumber("fallMode", (tonumber(sv.fallMode) or 2) - 1)
    return true
end

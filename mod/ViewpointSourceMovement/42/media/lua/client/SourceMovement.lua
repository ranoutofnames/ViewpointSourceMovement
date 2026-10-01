-- Source Movement for Viewpoint. Gameplay settings are Sandbox options (pushed by SourceMovement_Shared.lua);
-- Mod Options only hold per-player preferences: keys, camera, HUD.
-- The physics lives in the Java side (sourcemove.*); this file pushes settings to it and, in multiplayer,
-- does the handshake with the server and hands relayed jump reports of other players to it.

local MOD_ID = "ViewpointSourceMovement"
local NET = "SourceMovement"

local opts = PZAPI.ModOptions:create(MOD_ID, "Source Movement")
local o = {}

opts:addTitle("General")
o.enabled      = opts:addTickBox("enabled", "Enabled", true, "Turn the entire mod on or off")
o.toggleKey    = opts:addKeyBind("toggleKey", "Toggle on/off", Keyboard.KEY_F8, "Hotkey to turn it on or off in game")
o.fpOnly       = opts:addTickBox("fpOnly", "First-person only", true)
o.speedHud     = opts:addTickBox("speedHud", "Speed overlay", false)
o.debugHud     = opts:addTickBox("debugHud", "Debug overlay", false)

opts:addTitle("Jumping")
o.jumpKey      = opts:addKeyBind("jumpKey", "Jump", Keyboard.KEY_SPACE)
o.wheelJump    = opts:addTickBox("wheelJump", "Jump on mouse wheel", false)
o.jumpAnim     = opts:addTickBox("jumpAnim", "Jumping animation", true)

-- Java setting name -> Mod Options entry in `o` (per player).
local BOOLS = { enabled = "enabled", fpOnly = "fpOnly", debugHud = "debugHud", wheelJump = "wheelJump", jumpAnim = "jumpAnim" }
local NUMBERS = { jumpKey = "jumpKey" }

local warnedMissingJava = false

local function push()
    if not SourceMove_setBool or not SourceMove_setNumber then
        if not warnedMissingJava then
            warnedMissingJava = true
            print("[SourceMovement] Java side not loaded (ZombieBuddy approval denied or jar failed to load)")
        end
        return false
    end
    for key, opt in pairs(BOOLS) do SourceMove_setBool(key, o[opt]:getValue() == true) end
    for key, opt in pairs(NUMBERS) do SourceMove_setNumber(key, tonumber(o[opt]:getValue()) or 0) end
    return SourceMovement_pushSandbox()
end

function opts:apply()
    push()
end

local function note(text)
    local p = getPlayer()
    if p then p:setHaloNote(text) end
end

local function meleeKey()
    local ok, key = pcall(function() return getCore():getKey(KeybindId.MELEE:getId()) end)
    return ok and key or nil
end

-- Multiplayer: movement stays off until the server answers our hello.
local HELLO_EVERY_MS, HELLO_TRIES = 3000, 10
local mp = { started = false, ready = false, tries = 0, at = 0 }

local function onGameStart()
    -- Mod options are normally only loaded when the Options screen is built; load them ourselves.
    PZAPI.ModOptions:load()
    if SourceMove_netReset then SourceMove_netReset() end
    mp.started, mp.ready, mp.tries, mp.at = true, false, 0, 0
    if push() then
        print("[SourceMovement] settings applied, enabled=" .. tostring(o.enabled:getValue()))
    end
    local jump = o.jumpKey:getValue()
    if jump ~= 0 and jump == meleeKey() then
        note("Source movement: Jump and Melee are both on " .. Keyboard.getKeyName(jump) .. " - rebind one in Options")
    end
end

local function onTick()
    if not mp.started or mp.ready or mp.tries >= HELLO_TRIES or not isClient() or not SourceMove_clientCommand then return end
    local now = getTimestampMs()
    if now - mp.at < HELLO_EVERY_MS then return end
    local p = getPlayer()
    if not p then return end
    mp.at = now
    mp.tries = mp.tries + 1
    sendClientCommand(p, NET, "hello", {})
    if mp.tries == HELLO_TRIES then
        print("[SourceMovement] no answer from the server; staying off")
        note("Source movement: no answer from the server, off for this game")
    end
end

local function onServerCommand(module, command, args)
    if module ~= NET or not SourceMove_clientCommand then return end
    args = args or {}
    if command == "welcome" then
        mp.ready = true
        if args.d ~= "java" and not isCoopHost() then
            note("Source movement: server has no ZombieBuddy, its anticheat may pull you back after jumps")
        end
    end
    SourceMove_clientCommand(command, tonumber(args.id) or -1, tostring(args.d or ""))
end

local function onKeyPressed(key)
    local toggle = o.toggleKey:getValue()
    if toggle == nil or toggle == 0 or key ~= toggle then return end
    local v = not o.enabled:getValue()
    o.enabled:setValue(v)
    PZAPI.ModOptions:save()
    push()
    note(v and "Source movement ON" or "Source movement OFF")
end

-- Speed, centred under where the digital watch sits (top right; its spot is kept even without a watch).
local function drawSpeed()
    local clock = UIManager.getClock()
    local x, y
    if clock then
        x = clock:getX() + clock:getWidth() / 2
        y = clock:getY() + clock:getHeight() + 4
    else
        x = getCore():getScreenWidth() - 120
        y = 60
    end
    getTextManager():DrawStringCentre(UIFont.Medium, x, y, string.format("%.1f tiles/s", SourceMove_speed()), 1, 1, 1, 1)
end

local function onPostUIDraw()
    if not mp.started or not getPlayer() then return end -- in game only, never on the main menu
    if o.speedHud:getValue() and SourceMove_speed then drawSpeed() end
    if not o.debugHud:getValue() or not SourceMove_status then return end
    local y = 60
    for line in string.gmatch(SourceMove_status(), "[^\n]+") do
        getTextManager():DrawString(UIFont.Small, 20, y, line, 1, 1, 1, 1)
        y = y + 16
    end
end

Events.OnGameStart.Add(onGameStart)
Events.OnTick.Add(onTick)
Events.OnServerCommand.Add(onServerCommand)
Events.OnKeyPressed.Add(onKeyPressed)
Events.OnPostUIDraw.Add(onPostUIDraw)
if Events.OnMainMenuEnter then
    Events.OnMainMenuEnter.Add(function() mp.started = false end)
end

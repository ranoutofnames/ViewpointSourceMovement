-- Client side. Mod Options to Java, the MP handshake, and other players' relayed jump reports.

local MOD_ID = "ViewpointSourceMovement"
local NET = "SourceMovement"

local opts = PZAPI.ModOptions:create(MOD_ID, "Source Movement")
local o = {}

opts:addTitle("General")
o.enabled      = opts:addTickBox("enabled", "Enabled", true, "Turn the entire mod on or off")
o.toggleKey    = opts:addKeyBind("toggleKey", "Source movement on/off", Keyboard.KEY_F8, "Hotkey to turn it on or off in game")
o.fpOnly       = opts:addTickBox("fpOnly", "First-person only", true)
o.speedHud     = opts:addTickBox("speedHud", "Speed overlay", false)
o.debugHud     = opts:addTickBox("debugHud", "Debug overlay", false)
o.wireHud      = opts:addTickBox("wireHud", "Collision overlay", false)

opts:addTitle("Jumping")
o.jumpKey      = opts:addKeyBind("jumpKey", "Source jump", Keyboard.KEY_SPACE)
o.wheelJump    = opts:addTickBox("wheelJump", "Jump on mouse wheel", false)
o.jumpAnim     = opts:addTickBox("jumpAnim", "Jumping animation", true)

-- Mod Options Java reads, same names there.
local BOOLS = { "enabled", "fpOnly", "wireHud", "wheelJump", "jumpAnim" }
local NUMBERS = { "jumpKey" }

local warnedMissingJava = false

local function push()
    if not SourceMove_setBool or not SourceMove_setNumber then
        if not warnedMissingJava then
            warnedMissingJava = true
            print("[SourceMovement] Java side not loaded (ZombieBuddy approval denied or jar failed to load)")
        end
        return false
    end
    for _, key in ipairs(BOOLS) do SourceMove_setBool(key, o[key]:getValue() == true) end
    for _, key in ipairs(NUMBERS) do SourceMove_setNumber(key, tonumber(o[key]:getValue()) or 0) end
    return true
end

local function note(text)
    local p = getPlayer()
    if p then p:setHaloNote(text) end
end

local function meleeKey()
    local ok, key = pcall(function() return getCore():getKey(KeybindId.MELEE:getId()) end)
    return ok and key or nil
end

local function checkConflict()
    local jump = o.jumpKey:getValue()
    if jump ~= 0 and jump == meleeKey() then
        note("Source movement: Jump and Melee are both on " .. Keyboard.getKeyName(jump) .. " - rebind one in Options")
    end
end

function opts:apply()
    -- Vanilla saves a rebound key into the option only after apply runs.
    for _, opt in pairs(o) do
        if opt.type == "keybind" and opt.element and opt.element.keyCode then opt.key = opt.element.keyCode end
    end
    push()
    checkConflict()
end

-- MP movement stays off until the server answers. Hellos keep going until it does, slower after a while.
local HELLO_FAST_MS, HELLO_SLOW_MS, QUIET_MS = 3000, 15000, 30000
local mp = { started = false, ready = false, ticking = false, since = 0, at = 0, noted = false }

local function onTick()
    if mp.ready or not SourceMove_clientCommand then return end
    local now = getTimestampMs()
    local quiet = now - mp.since >= QUIET_MS
    if now - mp.at < (quiet and HELLO_SLOW_MS or HELLO_FAST_MS) then return end
    local p = getPlayer()
    if not p then return end
    mp.at = now
    sendClientCommand(p, NET, "hello", {})
    if quiet and not mp.noted then
        mp.noted = true
        print("[SourceMovement] no answer from the server yet; off until it answers")
        note("Source movement: no answer from the server yet, off until it answers")
    end
end

local function setTicking(on)
    if on == mp.ticking then return end
    mp.ticking = on
    if on then Events.OnTick.Add(onTick) else Events.OnTick.Remove(onTick) end
end

local function onGameStart()
    -- Mod Options only load with the Options screen otherwise.
    PZAPI.ModOptions:load()
    if SourceMove_reset then SourceMove_reset() end
    mp.started, mp.ready, mp.since, mp.at, mp.noted = true, false, getTimestampMs(), 0, false
    setTicking(isClient())
    if push() then
        print("[SourceMovement] settings applied, enabled=" .. tostring(o.enabled:getValue()))
    end
    checkConflict()
end

local function onMainMenuEnter()
    mp.started, mp.ready = false, false
    setTicking(false)
    if SourceMove_reset then SourceMove_reset() end
end

-- A respawn is a new player on the server.
local function onCreatePlayer(_, player)
    if mp.started and isClient() and SourceMove_clientCommand and player and player:isLocalPlayer() then
        sendClientCommand(player, NET, "hello", {})
    end
end

local function onServerCommand(module, command, args)
    if module ~= NET or not SourceMove_clientCommand then return end
    args = args or {}
    if command == "welcome" and not mp.ready then
        mp.ready = true
        setTicking(false)
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
    -- Save copies key widgets into the options, so drop rebinds cancelled in the Options screen.
    for _, opt in pairs(o) do
        if opt.type == "keybind" and opt.element then opt.element.keyCode = opt.key end
    end
    PZAPI.ModOptions:save()
    push()
    note(v and "Source movement ON" or "Source movement OFF")
end

-- Speed, under the digital watch's spot.
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
    if not mp.started or not getPlayer() then return end -- not on the main menu
    if o.speedHud:getValue() and SourceMove_speed then drawSpeed() end
    local y = 60
    if o.debugHud:getValue() and SourceMove_status then
        for line in string.gmatch(SourceMove_status(), "[^\n]+") do
            getTextManager():DrawString(UIFont.Small, 20, y, line, 1, 1, 1, 1)
            y = y + 16
        end
        y = y + 8
    end
    if o.wireHud:getValue() and SourceMove_drawWire then SourceMove_drawWire(20, y) end
end

Events.OnGameStart.Add(onGameStart)
Events.OnCreatePlayer.Add(onCreatePlayer)
Events.OnServerCommand.Add(onServerCommand)
Events.OnKeyPressed.Add(onKeyPressed)
Events.OnPostUIDraw.Add(onPostUIDraw)
if Events.OnMainMenuEnter then
    Events.OnMainMenuEnter.Add(onMainMenuEnter)
end

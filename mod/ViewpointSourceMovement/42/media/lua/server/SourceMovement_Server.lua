-- MP server. Answers hellos, relays jump reports to nearby players, feeds the anti-cheat when Java is loaded,
-- breaks the prop a player stands on once their zombies have.
if not isServer() then return end

local MOD = "SourceMovement"
local RELAY_RANGE = 70

local function relay(player, d)
    local args = { id = player:getOnlineID(), d = d }
    local x, y = player:getX(), player:getY()
    local players = getOnlinePlayers()
    for i = 0, players:size() - 1 do
        local other = players:get(i)
        if other ~= player and math.abs(other:getX() - x) < RELAY_RANGE and math.abs(other:getY() - y) < RELAY_RANGE then
            sendServerCommand(other, MOD, "s", args)
        end
    end
end

-- Prop breaks. The player must be on or beside it with a zombie at it.
local BREAK_PLAYER_RANGE = 1.5
local BREAK_ZOMBIE_RANGE = 2
local BREAK_EVERY_MS = 500
local lastBreak = {}
-- Built, openable or powered things are never ours to break. The Java side checks for a plain IsoObject instead.
local NOT_PROPS = { "IsoThumpable", "IsoDoor", "IsoWindow", "IsoWindowFrame", "IsoBarricade", "IsoGenerator", "IsoWorldInventoryObject",
    "IsoStove", "IsoWaveSignal", "IsoLightSwitch", "IsoFireplace", "IsoBarbecue", "IsoClothingDryer", "IsoClothingWasher",
    "IsoCombinationWasherDryer", "IsoStackedWasherDryer", "IsoJukebox", "IsoCarBatteryCharger", "IsoCurtain", "IsoMannequin",
    "IsoTree", "IsoCompost", "IsoTrap", "IsoFeedingTrough", "IsoHutch", "IsoWheelieBin", "IsoDeadBody", "IsoFire" }

local function propBreakOn()
    local ok, v = pcall(function() return getSandboxOptions():getOptionByName("ViewpointSourceMovement.propBreak"):getValue() end)
    if ok and v ~= nil then return v == true end
    local sv = SandboxVars and SandboxVars.ViewpointSourceMovement
    return not sv or sv.propBreak ~= false
end

local function isProp(obj)
    if SourceMove_isProp then return SourceMove_isProp(obj) end
    for _, cls in ipairs(NOT_PROPS) do
        if instanceof(obj, cls) then return false end
    end
    return true
end

local function zombieNear(cell, x, y, z)
    local r = BREAK_ZOMBIE_RANGE
    for gx = x - r, x + r do
        for gy = y - r, y + r do
            local sq = cell:getGridSquare(gx, gy, z)
            local movers = sq and sq:getMovingObjects()
            if movers then
                for i = 0, movers:size() - 1 do
                    local o = movers:get(i)
                    if instanceof(o, "IsoZombie") and not o:isDead()
                            and math.abs(o:getX() - (x + 0.5)) <= r + 0.5 and math.abs(o:getY() - (y + 0.5)) <= r + 0.5 then
                        return true
                    end
                end
            end
        end
    end
    return false
end

-- d = x;y;z;object index;sprite name
local function breakProp(player, d)
    if type(d) ~= "string" or #d > 160 or not propBreakOn() then return end
    local x, y, z, index, sprite = d:match("^(-?%d+);(-?%d+);(-?%d+);(-?%d+);(.+)$")
    x, y, z, index = tonumber(x), tonumber(y), tonumber(z), tonumber(index)
    if not x then return end
    local id = player:getOnlineID()
    local now = getTimestampMs()
    if lastBreak[id] and now - lastBreak[id] < BREAK_EVERY_MS then return end
    lastBreak[id] = now
    if math.floor(player:getZ()) ~= z or math.abs(player:getX() - (x + 0.5)) > BREAK_PLAYER_RANGE
            or math.abs(player:getY() - (y + 0.5)) > BREAK_PLAYER_RANGE then return end
    local cell = getCell()
    local sq = cell and cell:getGridSquare(x, y, z)
    if not sq or not (sq:isSolid() or sq:isSolidTrans()) then return end
    local safe = SafeHouse.getSafeHouse(sq)
    if safe and not safe:playerAllowed(player) then return end
    -- The index can shift, the sprite can't.
    local objects = sq:getObjects()
    local obj = index >= 0 and index < objects:size() and objects:get(index) or nil
    if not obj or obj:getSpriteName() ~= sprite then
        obj = nil
        for i = 0, objects:size() - 1 do
            if objects:get(i):getSpriteName() == sprite then
                obj = objects:get(i)
                break
            end
        end
    end
    if not obj or not isProp(obj) or not zombieNear(cell, x, y, z) then return end
    -- As vanilla breaks a thumped object, contents onto the floor.
    for i = 0, obj:getContainerCount() - 1 do
        local container = obj:getContainerByIndex(i)
        local items = container:getItems()
        local spill = {}
        for j = 0, items:size() - 1 do
            spill[#spill + 1] = items:get(j)
        end
        container:removeItemsFromProcessItems()
        container:removeAllItems()
        for _, item in ipairs(spill) do
            sq:AddWorldInventoryItem(item, 0.0, 0.0, 0.0)
        end
    end
    -- A multi-tile prop missing a part only loses this tile.
    if sq:transmitRemoveItemFromSquare(obj) < 0 then sq:transmitRemoveItemFromSquare(obj, false) end
    addSound(nil, x, y, z, 10, 20)
end

local function onClientCommand(module, command, player, args)
    if module ~= MOD or not player then return end
    if command == "s" then
        local d = args and args.d
        if type(d) ~= "string" or #d > 64 then return end
        if SourceMove_serverState then SourceMove_serverState(player, d) end
        relay(player, d)
    elseif command == "brk" then
        breakProp(player, args and args.d)
    elseif command == "hello" then
        local java = SourceMove_serverHello ~= nil
        if java then SourceMove_serverHello(player) end
        print("[SourceMovement] hello from " .. tostring(player:getUsername()) .. (java and "" or " (no Java side on this server)"))
        sendServerCommand(player, MOD, "welcome", { d = java and "java" or "" })
    end
end

Events.OnClientCommand.Add(onClientCommand)
Events.OnServerStarted.Add(function()
    if SourceMove_serverStart then SourceMove_serverStart() end
end)

-- Source Movement for Viewpoint, multiplayer server side: answer clients' hello, relay their jump reports
-- (height, vertical speed, jump and landing events) to nearby players, and, when this server runs
-- ZombieBuddy with the mod's Java side, feed them to its anti-cheat patches. The relay works without Java,
-- e.g. on a hosted (co-op) game, whose server has the anti-cheat off anyway.
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

local function onClientCommand(module, command, player, args)
    if module ~= MOD or not player then return end
    if command == "s" then
        local d = args and args.d
        if type(d) ~= "string" or #d > 64 then return end
        if SourceMove_serverState then SourceMove_serverState(player, d) end
        relay(player, d)
    elseif command == "hello" then
        local java = SourceMove_serverHello ~= nil
        if java then
            SourceMovement_pushSandbox()
            SourceMove_serverHello(player)
        end
        print("[SourceMovement] hello from " .. tostring(player:getUsername()) .. (java and "" or " (no Java side on this server)"))
        sendServerCommand(player, MOD, "welcome", { d = java and "java" or "" })
    end
end

Events.OnClientCommand.Add(onClientCommand)

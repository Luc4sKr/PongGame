local maxSlots = tonumber(ARGV[1])
local active = tonumber(redis.call('GET', KEYS[2]) or '0')
if active >= maxSlots then
    return {}
end

local function popValidTicket()
    while true do
        local tid = redis.call('LPOP', KEYS[1])
        if not tid then
            return nil
        end
        local st = redis.call('HGET', 'mm:ticket:' .. tid, 'status')
        if st == 'QUEUED' then
            return tid
        end
    end
end

local t1 = popValidTicket()
if not t1 then
    return {}
end
local t2 = popValidTicket()
if not t2 then
    redis.call('LPUSH', KEYS[1], t1)
    return {}
end

local p1Id = redis.call('HGET', 'mm:ticket:' .. t1, 'playerId')
local p1User = redis.call('HGET', 'mm:ticket:' .. t1, 'username')
local p2Id = redis.call('HGET', 'mm:ticket:' .. t2, 'playerId')
local p2User = redis.call('HGET', 'mm:ticket:' .. t2, 'username')

if p1Id == p2Id then
    redis.call('LPUSH', KEYS[1], t1)
    return {}
end

redis.call('INCR', KEYS[2])

local matchId = ARGV[2]
local corrId = ARGV[3]
local nowIso = ARGV[4]
local deadlineMs = ARGV[5]

redis.call('HSET', 'mm:ticket:' .. t1, 'status', 'PROVISIONING', 'matchId', matchId, 'correlationId', corrId, 'updatedAt', nowIso)
redis.call('HSET', 'mm:ticket:' .. t2, 'status', 'PROVISIONING', 'matchId', matchId, 'correlationId', corrId, 'updatedAt', nowIso)
redis.call('HSET', 'mm:match:' .. matchId,
    'matchId', matchId,
    'status', 'PROVISIONING',
    'correlationId', corrId,
    'player1Id', p1Id,
    'player1Username', p1User,
    'ticket1Id', t1,
    'player2Id', p2Id,
    'player2Username', p2User,
    'ticket2Id', t2,
    'deadlineMs', deadlineMs,
    'slotReleased', '0',
    'updatedAt', nowIso)
redis.call('EXPIRE', 'mm:match:' .. matchId, 7200)
redis.call('ZADD', KEYS[3], tonumber(deadlineMs), matchId)

local function traceHeader(ticket, name)
    return redis.call('HGET', 'mm:ticket:' .. ticket, 'otel.' .. name) or ''
end
return {t1, p1Id, p1User, t2, p2Id, p2User,
    traceHeader(t1, 'traceparent'), traceHeader(t1, 'tracestate'),
    traceHeader(t2, 'traceparent'), traceHeader(t2, 'tracestate')}

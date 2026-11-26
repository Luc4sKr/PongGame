local released = redis.call('HGET', KEYS[1], 'slotReleased')
if released == '1' then
    return 0
end
redis.call('HSET', KEYS[1], 'slotReleased', '1')
local current = tonumber(redis.call('GET', KEYS[2]) or '0')
if current > 0 then
    redis.call('DECR', KEYS[2])
end
return 1

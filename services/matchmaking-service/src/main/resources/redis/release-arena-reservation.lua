local exists = redis.call('DEL', KEYS[2])
if exists == 1 then
    local current = tonumber(redis.call('GET', KEYS[1]) or '0')
    if current > 0 then
        redis.call('DECR', KEYS[1])
    end
    return 1
end
return 0

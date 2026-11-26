local limit = tonumber(ARGV[1])
local current = tonumber(redis.call('GET', KEYS[1]) or '0')
if current >= limit then
    return 0
end
local nextVal = redis.call('INCR', KEYS[1])
redis.call('SET', KEYS[2], ARGV[2], 'EX', 3600)
return nextVal

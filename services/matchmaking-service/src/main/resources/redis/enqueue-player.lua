local existing = redis.call('GET', KEYS[1])
if existing then
    return existing
end
redis.call('SET', KEYS[1], ARGV[1], 'EX', 3600)
redis.call('HSET', KEYS[2],
    'ticketId', ARGV[1],
    'playerId', ARGV[2],
    'username', ARGV[3],
    'status', 'QUEUED',
    'correlationId', ARGV[4],
    'matchId', '',
    'wsPath', '',
    'reason', '',
    'updatedAt', ARGV[5],
    'otel.traceparent', ARGV[6],
    'otel.tracestate', ARGV[7])
redis.call('EXPIRE', KEYS[2], 7200)
redis.call('RPUSH', KEYS[3], ARGV[1])
return ARGV[1]

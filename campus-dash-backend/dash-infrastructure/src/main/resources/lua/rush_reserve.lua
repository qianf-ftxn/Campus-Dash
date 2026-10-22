-- KEYS: stock, users hash, request hash
-- ARGV: userId, requestId, nowMillis, resultTtlSeconds
local owner = redis.call('HGET', KEYS[3], 'userId')
if owner then
  local status = redis.call('HGET', KEYS[3], 'status')
  -- requestId是全局幂等号。即使两个用户并发提交同一个仍处于QUEUED的requestId，
  -- 也不能让后处理者覆盖先提交者的所有权。
  if owner ~= ARGV[1] then return -2 end
  if owner == ARGV[1] and status == 'RESERVED' then return 2 end
  if status ~= 'QUEUED' then return -2 end
end

local existing = redis.call('HGET', KEYS[2], ARGV[1])
if existing then
  if existing == ARGV[2] then
    redis.call('HSET', KEYS[3], 'userId', ARGV[1], 'campaignId', string.match(KEYS[1], 'rush:{(.-)}:stock'),
      'status', 'RESERVED', 'reservedAt', ARGV[3])
    redis.call('EXPIRE', KEYS[3], ARGV[4])
    return 2
  end
  return -1
end

local stock = redis.call('GET', KEYS[1])
if not stock then return -3 end
if tonumber(stock) <= 0 then return 0 end

redis.call('DECR', KEYS[1])
redis.call('HSET', KEYS[2], ARGV[1], ARGV[2])
redis.call('HSET', KEYS[3], 'userId', ARGV[1], 'campaignId', string.match(KEYS[1], 'rush:{(.-)}:stock'),
  'status', 'RESERVED', 'reservedAt', ARGV[3])
redis.call('EXPIRE', KEYS[3], ARGV[4])
return 1

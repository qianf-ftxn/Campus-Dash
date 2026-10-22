-- KEYS: stock, users hash, request hash
-- ARGV: userId, requestId, failedStatus, resultTtlSeconds
local mapped = redis.call('HGET', KEYS[2], ARGV[1])
local owner = redis.call('HGET', KEYS[3], 'userId')
local status = redis.call('HGET', KEYS[3], 'status')

-- users映射是“确实扣过库存”的凭证。请求Hash可能因为TTL或一次失败的缓存写而缺字段，
-- 只要映射仍由本请求持有，就可以安全、且只执行一次地归还库存。
if mapped == ARGV[2] then
  if owner and owner ~= ARGV[1] then return 0 end
  if status == 'SUCCESS' then return 0 end
  if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end

  redis.call('INCR', KEYS[1])
  redis.call('HDEL', KEYS[2], ARGV[1])
  redis.call('HSET', KEYS[3], 'userId', ARGV[1],
    'campaignId', string.match(KEYS[1], 'rush:{(.-)}:stock'), 'status', ARGV[3])
  redis.call('EXPIRE', KEYS[3], ARGV[4])
  return 1
end

-- 映射已经移除且请求已经是失败终态，说明其他消费者/恢复任务完成过补偿。
-- 返回2表示“已经补偿”，调用方可以继续推进DB终态而不会重复加库存。
if (not mapped) and owner == ARGV[1] and status and status ~= 'RESERVED' and status ~= 'SUCCESS' then
  return 2
end
return 0

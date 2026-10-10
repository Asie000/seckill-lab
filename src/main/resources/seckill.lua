-- ============================================================
-- v1-redis-lua：秒杀预扣减 Lua 脚本（原子执行）
-- KEYS[1] = seckill:stock:{activityId}
-- KEYS[2] = seckill:order:{activityId}   (Set，存已下单 userId)
-- ARGV[1] = userId
-- 返回值：0=成功  1=库存不足  2=重复下单
-- ============================================================

local stockKey = KEYS[1]
local orderKey = KEYS[2]
local userId   = ARGV[1]

-- 1. 库存判断（key 不存在按 0 处理，避免 tonumber(nil) 报错）
local stock = tonumber(redis.call('get', stockKey)) or 0
if stock <= 0 then
    return 1
end

-- 2. 一人一单判断
if redis.call('sismember', orderKey, userId) == 1 then
    return 2
end

-- 3. 原子扣减 + 记录用户
redis.call('decr', stockKey)
redis.call('sadd', orderKey, userId)
return 0

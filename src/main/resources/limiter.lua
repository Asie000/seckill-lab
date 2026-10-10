-- ============================================================
-- v4-ratelimit：滑动窗口限流 Lua 脚本（基于 Redis ZSet）
-- KEYS[1] = 限流 key
-- ARGV[1] = 窗口大小（秒）
-- ARGV[2] = 窗口内最大请求数
-- ARGV[3] = 当前时间戳（毫秒）
-- ARGV[4] = 本次请求的唯一标识（member，由调用方传入）
-- 返回值：>0 表示放行（当前窗口计数）；0 表示被限流
-- ============================================================

local key    = KEYS[1]
local window = tonumber(ARGV[1]) * 1000   -- 秒 -> 毫秒
local limit  = tonumber(ARGV[2])
local now    = tonumber(ARGV[3])
local member = ARGV[4]

-- 1. 删除窗口外的请求记录
redis.call('ZREMRANGEBYSCORE', key, 0, now - window)

-- 2. 统计窗口内请求数
local current = redis.call('ZCARD', key)

-- 3. 未超限则记录本次请求并放行
--    注意：member 必须每次唯一。早期版本用 math.randomseed(now)+random，
--    同一毫秒内会生成相同 member 被 ZSet 去重，导致 ZCARD 少算、限流被绕过。
if current < limit then
    redis.call('ZADD', key, now, member)
    redis.call('PEXPIRE', key, window)
    return current + 1
end
return 0

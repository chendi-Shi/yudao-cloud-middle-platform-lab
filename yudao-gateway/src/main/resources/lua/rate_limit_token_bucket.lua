-- 令牌桶限流（Redis + Lua，保证「读-再填充-扣减-写回」的原子性）
--
-- KEYS[1] 桶的 key（按 租户 + 用户/IP + 规则 维度隔离）
-- ARGV[1] rate        每秒补充的令牌数（= QPS）
-- ARGV[2] capacity    桶容量（= 允许的突发量）
-- ARGV[3] now_ms      当前毫秒时间戳（由网关传入，避免多实例时钟漂移导致桶行为不一致）
-- ARGV[4] requested   本次请求消耗的令牌数（固定为 1）
-- ARGV[5] ttl_ms      桶的空闲过期时间，避免冷 key 常驻内存
--
-- 返回：{是否放行(1/0), 剩余令牌数, 桶容量}

local rate = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])
local ttl = tonumber(ARGV[5])

local key = KEYS[1]
local bucket = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(bucket[1])
local ts = tonumber(bucket[2])

-- 首次访问：桶是满的
if tokens == nil then
    tokens = capacity
    ts = now
end

-- 按流逝时间补充令牌，上限为桶容量
local delta = math.max(0, now - ts) / 1000
tokens = math.min(capacity, tokens + delta * rate)

local allowed = 0
if tokens >= requested then
    tokens = tokens - requested
    allowed = 1
end

redis.call('HSET', key, 'tokens', tostring(tokens), 'ts', tostring(now))
if ttl > 0 then
    redis.call('PEXPIRE', key, ttl)
end

return {allowed, math.floor(tokens), capacity}

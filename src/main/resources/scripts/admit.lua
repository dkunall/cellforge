-- Monthly quota first, then a token bucket, in one round trip so concurrent requests cannot both take the
-- last token. Time comes from Redis so edge instances with skewed clocks share one view of the bucket.
-- KEYS[1] quota counter, KEYS[2] bucket hash
-- ARGV[1] monthly quota, ARGV[2] tokens per second, ARGV[3] burst, ARGV[4] quota key ttl seconds
local quota = tonumber(ARGV[1])
local used = tonumber(redis.call('GET', KEYS[1]) or '0')
if used >= quota then
  return {0, 'quota', 0}
end

local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local per_ms = tonumber(ARGV[2]) / 1000
local burst = tonumber(ARGV[3])
local state = redis.call('HMGET', KEYS[2], 'tokens', 'ts')
local tokens = tonumber(state[1]) or burst
local last = tonumber(state[2]) or now
tokens = math.min(burst, tokens + math.max(0, now - last) * per_ms)
if tokens < 1 then
  return {0, 'rate', math.ceil((1 - tokens) / per_ms)}
end

redis.call('HSET', KEYS[2], 'tokens', tostring(tokens - 1), 'ts', now)
redis.call('PEXPIRE', KEYS[2], math.ceil(burst / per_ms) + 1000)
if redis.call('INCR', KEYS[1]) == 1 then
  redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
end
return {1, 'ok', 0}

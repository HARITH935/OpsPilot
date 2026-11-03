-- Sliding-window log rate limiter (architecture doc B2.4), run inside Redis by SlidingWindowRateLimiter.
-- Redis runs a script as one step: no other command can run in between, so two requests at the same moment can't
-- both see "one place left" and both take it.
--
-- KEYS[1] = this caller's sorted set, e.g. ratelimit:orders:ip:203.0.113.7
--           (a sorted set = members kept in order of a number, their "score"; here the score is the request time)
-- ARGV[1] = how many requests the window allows
-- ARGV[2] = the window, in milliseconds
-- ARGV[3] = a unique id for this request (two requests in the same millisecond must both be counted)
-- Returns {allowed (1 or 0), places left, milliseconds until the oldest counted request leaves the window}

local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])

-- Redis's own clock, not the gateway's: every copy of the gateway then counts with the same time
local time = redis.call('TIME')   -- {seconds, microseconds}
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)   -- forget requests that left the window
local count = redis.call('ZCARD', key)

local allowed = 0
if count < limit then
  redis.call('ZADD', key, now, ARGV[3])   -- only allowed requests are counted: being refused costs nothing
  count = count + 1
  allowed = 1
end
redis.call('PEXPIRE', key, window)   -- a caller who stops calling leaves nothing behind after one window

local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')   -- {member, score} of the oldest counted request
local reset = 0
if oldest[2] then
  reset = tonumber(oldest[2]) + window - now
end
return {allowed, limit - count, reset}

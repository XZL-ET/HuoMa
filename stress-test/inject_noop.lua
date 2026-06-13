-- ============================================================
-- 活码系统安全压测脚本（noop 事件，不调企微 API）
--
-- 用法:
--   redis-cli --eval inject_noop.lua , <消息数量> [批次间隔_ms]
--
-- 示例:
--   redis-cli --eval inject_noop.lua , 100       # 注入100条，0间隔
--   redis-cli --eval inject_noop.lua , 500 10    # 注入500条，每批间隔10ms
--   redis-cli --eval inject_noop.lua , 2000 1    # 注入2000条，每批间隔1ms
--
-- 原理: 注入 event_type=__stress_test__ 的消息到 wecom:callback:stream
--       CallbackWorker 的 processEvent() 走到 default 分支,
--       只打 debug 日志就 ACK, 不调企微 API, 不写数据库, 不触发打标
-- ============================================================

local count = tonumber(ARGV[1])
local batch_delay = tonumber(ARGV[2]) or 0
local batch_size = tonumber(ARGV[3]) or 50
local stream_key = "wecom:callback:stream"

if not count or count <= 0 then
    return "用法: redis-cli --eval inject_noop.lua , <消息数量> [批次间隔_ms] [批次大小]"
end

local start_time = redis.call('TIME')
local start_len = redis.call('XLEN', stream_key)
-- 用 Redis TIME 的时间戳替代 os.date() (Redis Lua 沙箱禁用了 os 模块)
local base_ts = start_time[1]

for i = 1, count do
    local event_json = string.format(
        '{"event_type":"__stress_test__","payload":"msg_%d","ts":%s}',
        i, base_ts
    )

    redis.call('XADD', stream_key, '*', 'event', event_json)

    -- 分批提交，避免单次 Lua 脚本阻塞 Redis 太久
    if batch_delay > 0 and i % batch_size == 0 and i < count then
        redis.call('PING')
    end
end

local end_time = redis.call('TIME')
local end_len = redis.call('XLEN', stream_key)

local elapsed = (end_time[1] - start_time[1]) * 1000 + (end_time[2] - start_time[2]) / 1000
local growth = end_len - start_len

return string.format(
    "注入完成: %d 条 | 耗时: %.1fms | Stream: %d → %d (+%d) | 速度: %.0f msg/s",
    count, elapsed, start_len, end_len, growth,
    elapsed > 0 and (count / elapsed * 1000) or count
)

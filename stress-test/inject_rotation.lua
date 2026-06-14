-- ============================================================
-- 活码人员轮转压测 —— Redis Stream 注入脚本
--
-- 注入模拟 add_external_contact 事件到 wecom:callback:stream
-- CallbackWorker 消费后走完整链路:
--   incrementDailyCount → checkAndRotate → expandQrCodeUsers
--   → takeStandby → afterCommit 同步企微
--
-- 用法:
--   redis-cli --eval inject_rotation.lua <agent_userid> <state> <count> [batch_delay_ms]
--
-- 参数:
--   agent_userid   : 被分配客户的企微员工 userid
--   state          : 活码 school_id，用于定位 QR code
--   count          : 注入该员工的模拟客户数
--   batch_delay_ms : 每批间隔(ms)，默认 0（不延迟）
--
-- 示例:
--   redis-cli --eval inject_rotation.lua zhang-san STRESS_TEST_000 10
--   redis-cli --eval inject_rotation.lua zhang-san STRESS_TEST_000 100 10
-- ============================================================

local agent_userid = ARGV[1]
local state        = ARGV[2]
local count        = tonumber(ARGV[3])
local batch_delay  = tonumber(ARGV[4]) or 0
local batch_size   = 20
local stream_key   = "wecom:callback:stream"
local prefix       = "stress_"

if not agent_userid or not state or not count or count <= 0 then
    return "用法: redis-cli --eval inject_rotation.lua <agent_userid> <state> <count> [batch_delay_ms]"
end

local start_time = redis.call('TIME')
local start_len  = redis.call('XLEN', stream_key)
local base_ts    = start_time[1]

for i = 1, count do
    local ext_uid = prefix .. "ext_" .. agent_userid .. "_" .. i

    -- 构造 add_external_contact 事件 JSON
    -- CallbackWorker 的 handleAddSuccess 需要: external_userid, userid, state
    local event_json = string.format(
        '{"event_type":"add_external_contact","external_userid":"%s","userid":"%s","state":"%s"}',
        ext_uid, agent_userid, state
    )

    redis.call('XADD', stream_key, '*', 'event', event_json)

    -- 分批暂停，避免 Lua 脚本长时间阻塞 Redis
    if batch_delay > 0 and i % batch_size == 0 and i < count then
        redis.call('PING')
        -- 注意: Redis Lua 不支持 sleep，延迟由调用方(Shell)控制
    end
end

local end_time = redis.call('TIME')
local end_len  = redis.call('XLEN', stream_key)

local elapsed = (end_time[1] - start_time[1]) * 1000 + (end_time[2] - start_time[2]) / 1000
local growth  = end_len - start_len

return string.format(
    "[轮转压测] agent=%s state=%s 注入:%d条 | 耗时:%.1fms | Stream: %d→%d (+%d) | 速率:%.0f msg/s",
    agent_userid, state, count, elapsed, start_len, end_len, growth,
    elapsed > 0 and (count / elapsed * 1000) or count
)

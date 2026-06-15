#!/usr/bin/env python3
"""
活码轮转压力模拟引擎 v2（匹配真实系统上下码逻辑）
====================================================
严格模拟:
  CallbackWorker.handleAddSuccess -> incrementDailyCount -> checkAndRotate
  -> expandQrCodeUsers(满员下码+上码) / preActivateBackup(紧急预激活)
  -> takeStandby(公平轮转,排序+排除+懒清理) -> syncWechat(afterCommit)

真实对照:
  事件路由:  state(schoolId) -> QR code (非轮询分配)
  员工分配:  userid 在事件中指定 (模拟企微随机分配)
  日计数:    Redis-style key 带 TTL到次日0点
  阈值:      warn(仅日志) / urgent(预激活) / dailyMax(满员轮转)
  池取人:    takeStandby(excludeUserids) — 排除已在活码上的员工
  公平轮转:  取走后 sortOrder -> max+1 (队尾)
  懒清理:    遍历时删除 wechat_status!=1, active=false, blocked/melted
  分布式锁:  活码级 rotate:lock 防并发
  afterCommit: 异步同步企微 (模拟延迟)

用法:
  python rotation_sim_engine.py [选项]
    --rate N      每事件间隔 ms (默认 15)
    --total N     总事件数 (默认 200000)
    --qrcodes N   活码数 (默认 20)
    --pool N      全局池大小 (默认 1800)
    --daily-max N 每接待员日限 (默认 100)
    --agents-per N 每活码初始接待员数 (默认 20)
    --warn-ratio N   预警阈值% (默认 70)
    --urgent-ratio N 紧急阈值% (默认 85)
    --fast        最快速度 (0ms间隔)
    --log FILE    日志文件路径
"""

import sys
import os
import time
import random
import string
import threading
import json
import uuid
import re
from datetime import datetime, timedelta
from collections import defaultdict, deque
from dataclasses import dataclass, field
from typing import List, Dict, Optional, Tuple, Set

# Windows 控制台 UTF-8 支持
if sys.platform == 'win32':
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')

# ============================================================
# 配置
# ============================================================
@dataclass
class Config:
    event_rate_ms: int = 15
    total_events: int = 200_000
    num_qrcodes: int = 20
    pool_size: int = 1800
    daily_max: int = 100
    agents_per_qr: int = 20
    warn_ratio: int = 70        # 预警阈值 %
    urgent_ratio: int = 100     # 紧急阈值 % (100=关闭预激活)
    refresh_interval: float = 0.5
    fast_mode: bool = False
    log_file: str = ''
    # 生产环境参数
    worker_count: int = 4            # 并行 Worker 数
    batch_size: int = 50             # XREADGROUP 每批数量
    block_ms: int = 5000             # XREADGROUP 阻塞等待
    after_commit_ms: int = 50        # afterCommit 企微 API 延迟 (ms)
    traffic_skew: float = 0.3        # 流量倾斜度 (0=均匀, 1=极度不均)
    burst_ratio: float = 0.0         # 突发比例 (0=均匀, 0.5=50%时间在突发)

# ============================================================
# 模拟 Redis 计数器 (带 TTL 到次日0点)
# ============================================================
class SimulatedRedis:
    """模拟 Redis 计数器，带次日0点过期"""
    def __init__(self):
        self.counters: Dict[str, int] = {}
        self._midnight = self._calc_midnight()

    @staticmethod
    def _calc_midnight() -> float:
        now = datetime.now()
        tomorrow = now.replace(hour=0, minute=0, second=0, microsecond=0) + timedelta(days=1)
        return tomorrow.timestamp()

    def incr(self, key: str) -> int:
        now = time.time()
        if now >= self._midnight:
            # 已过期，重置
            self.counters.clear()
            self._midnight = self._calc_midnight()
        val = self.counters.get(key, 0) + 1
        self.counters[key] = val
        return val

    def get(self, key: str) -> int:
        return self.counters.get(key, 0)

    def reset(self):
        self.counters.clear()
        self._midnight = self._calc_midnight()

# ============================================================
# 模拟 Redis Stream (匹配 wecom:callback:stream)
# ============================================================
@dataclass
class StreamMessage:
    msg_id: str
    event_data: Dict  # {userid, state, external_userid}
    pending: bool = True

class SimulatedRedisStream:
    """模拟 Redis Stream 的 XADD/XREADGROUP/XACK，支持消费者组"""

    def __init__(self, consumer_group: str = 'CALLBACK_CONSUMER_GROUP'):
        self.messages: deque = deque()
        self.pending: Dict[str, StreamMessage] = {}  # msg_id -> message (pending)
        self.consumer_group = consumer_group
        self._msg_counter = 0
        self._lock = threading.Lock()
        self.total_added = 0
        self.total_acked = 0

    def xadd(self, event_data: Dict) -> str:
        """XADD stream * event <json>"""
        with self._lock:
            self._msg_counter += 1
            msg_id = f"{int(time.time() * 1000)}-{self._msg_counter}"
            msg = StreamMessage(msg_id=msg_id, event_data=event_data, pending=True)
            self.messages.append(msg)
            self.total_added += 1
            return msg_id

    def xreadgroup(self, consumer: str, count: int, block_ms: int) -> List[StreamMessage]:
        """
        XREADGROUP GROUP {group} {consumer} COUNT {count} BLOCK {block_ms} STREAMS {key} >
        从 stream 读取新消息（未被其他 consumer 认领的）
        """
        with self._lock:
            batch = []
            for _ in range(min(count, len(self.messages))):
                msg = self.messages.popleft()
                self.pending[msg.msg_id] = msg
                batch.append(msg)
            return batch

    def xack(self, msg_id: str):
        """XACK — 确认消息已处理"""
        with self._lock:
            if msg_id in self.pending:
                del self.pending[msg_id]
                self.total_acked += 1

    def xpending_count(self) -> int:
        """XPENDING — 待处理消息数"""
        with self._lock:
            return len(self.pending)

    def xlen(self) -> int:
        """XLEN — 队列长度"""
        with self._lock:
            return len(self.messages)

# ============================================================
# 数据模型
# ============================================================
@dataclass
class Agent:
    """全局池中的员工 (对应 GlobalAgentPool 实体)"""
    userid: str
    name: str
    sort_order: int
    status: str = 'standby'       # standby | full | blocked
    wechat_status: int = 1        # 1=已激活 2=禁用 4=未激活 5=离职
    daily_max: int = 100
    daily_current: int = 0        # 全局日计数 (agent:daily:total)
    rotation_count: int = 0
    total_served: int = 0

@dataclass
class QrAgent:
    """活码上的员工 (对应 qr_agent 表)"""
    agent: Agent
    role: str = 'receptionist'    # service | receptionist
    daily_max: int = 100
    daily_current: int = 0        # 本活码维度的日计数
    sort_order: int = 0
    status: str = 'active'        # active | full | removed
    replaced_by: str = ''         # 替换者 userid (满员下码时)

@dataclass
class QrCode:
    """活码 (对应 qr_code 表)"""
    qr_id: int
    name: str
    school_id: str
    agents: List[QrAgent] = field(default_factory=list)
    rotate_mode: str = 'auto'     # auto | manual
    warn_ratio: int = 70
    urgent_ratio: int = 85
    total_events: int = 0
    rotation_events: int = 0      # expandQrCodeUsers 次数
    pre_activate_events: int = 0  # preActivateBackup 次数

@dataclass
class RotationEvent:
    """轮转事件 (对应 qr_rotate_log 表)"""
    timestamp: float
    event_type: str               # expand | pre_activate
    qr_name: str
    off_agent: str                # 下码员工 (pre_activate 时为空)
    on_agent: str                 # 上码员工
    off_daily: int
    reason: str

# ============================================================
# 全局池 (对应 GlobalAgentPoolService)
# ============================================================
class GlobalPool:
    """全局接待员池 — 严格模拟 GlobalAgentPoolService"""

    def __init__(self, config: Config):
        self.config = config
        self.pool: List[Agent] = []
        self.sort_counter = 100
        self.full_pool: List[Agent] = []
        self._lock = threading.Lock()
        self.take_count = 0
        self.skip_count = 0
        self.lazy_delete_count = 0    # 懒清理删除数
        self._init_pool()

    def _init_pool(self):
        surnames = list("赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张"
                       "孔曹严华金魏陶姜戚谢邹喻柏水窦章云苏潘葛奚范彭郎"
                       "鲁韦昌马苗凤花方俞任袁柳酆鲍史唐费廉岑薛雷贺倪汤")
        names = list("伟强军勇杰涛明超华丽芳婷敏静洋艳慧斌龙峰霞鑫玉萍")
        used_names = set()

        for i in range(self.config.pool_size):
            while True:
                name = random.choice(surnames) + random.choice(names) + random.choice(names)
                if name not in used_names:
                    used_names.add(name)
                    break
            if random.random() < 0.3:
                userid = name.replace(' ', '')
            else:
                userid = f"13{random.randint(100000000, 999999999):0d}"

            # ~5% 企微不可用
            wx_status = 1
            if random.random() < 0.05:
                wx_status = random.choice([2, 4, 5])

            agent = Agent(
                userid=userid, name=name,
                sort_order=self.sort_counter,
                wechat_status=wx_status,
                daily_max=self.config.daily_max,
            )
            self.sort_counter += 1
            self.pool.append(agent)

        self.pool.sort(key=lambda a: a.sort_order)

    def take_standby(self, exclude_userids: Set[str] = None) -> Optional[Agent]:
        """
        从池中取一个可用 standby (匹配 GlobalAgentPoolService.takeStandby)
        公平轮转: sortOrder ASC -> 取走 -> sortOrder = max+1 (队尾)
        懒清理: 遍历时物理删除 wechat_status!=1, active=false, blocked/melted
        """
        if exclude_userids is None:
            exclude_userids = set()

        with self._lock:
            max_order = max((a.sort_order for a in self.pool), default=0)

            for agent in self.pool:
                if agent.status != 'standby':
                    continue

                # 1. 排除已在活码上的员工
                if agent.userid in exclude_userids:
                    continue

                # 2. 懒清理: 企微状态不可用
                if agent.wechat_status is not None and agent.wechat_status != 1:
                    self.lazy_delete_count += 1
                    self.skip_count += 1
                    self.pool.remove(agent)
                    continue

                # 3. 懒清理: 封号/熔断 (本模拟中只有 blocked)
                if agent.status == 'blocked':
                    self.lazy_delete_count += 1
                    self.skip_count += 1
                    self.pool.remove(agent)
                    continue

                # 通过检查 — 取走
                agent.status = 'full'  # 临时标记
                self.take_count += 1
                agent.rotation_count += 1

                # 公平轮转: 移至队尾
                max_order += 1
                agent.sort_order = max_order
                self.sort_counter = max_order
                self.pool.sort(key=lambda a: a.sort_order)

                return agent

            return None  # 池枯竭

    def mark_full(self, userid: str):
        """标记员工全局日限满 (对应 poolService.markFull)"""
        with self._lock:
            for agent in self.pool:
                if agent.userid == userid:
                    agent.status = 'full'
                    self.pool.remove(agent)
                    self.full_pool.append(agent)
                    return

    def update_daily_current(self, userid: str, count: int):
        """更新全局日计数 (对应 poolService.updateDailyCurrent)"""
        for agent in self.pool:
            if agent.userid == userid:
                agent.daily_current = count
                return
        # 可能在 full_pool 中
        for agent in self.full_pool:
            if agent.userid == userid:
                agent.daily_current = count
                return

    def get_stats(self) -> Dict:
        with self._lock:
            standby = sum(1 for a in self.pool if a.status == 'standby')
            full_in = sum(1 for a in self.pool if a.status == 'full')
            return {
                'total': len(self.pool),
                'standby': standby,
                'full_in_pool': full_in,
                'full_pool_size': len(self.full_pool),
                'take_count': self.take_count,
                'skip_count': self.skip_count,
                'lazy_deleted': self.lazy_delete_count,
                'available': standby,
            }

# ============================================================
# XML 事件生成器 (匹配企微回调格式)
# ============================================================
class EventGenerator:
    """生成企微 add_external_contact 回调事件"""

    CORP_ID = "ww36b412d53f0fe0c6"

    @staticmethod
    def generate(state: str, agent_userid: str, seq: int) -> str:
        """
        生成 add_external_contact 事件 XML
        真实字段: external_userid, userid, state
        state = schoolId (路由关键), userid = 被分配的企微员工
        """
        ext_uid = f"wmZUH8SgAA{random.randint(1000000000000, 9999999999999):0d}"
        create_time = int(time.time())

        return f"""<xml>
<ToUserName><![CDATA[{EventGenerator.CORP_ID}]]></ToUserName>
<FromUserName><![CDATA[sys]]></FromUserName>
<CreateTime>{create_time}</CreateTime>
<MsgType><![CDATA[event]]></MsgType>
<Event><![CDATA[change_external_contact]]></Event>
<ChangeType><![CDATA[add_external_contact]]></ChangeType>
<UserID><![CDATA[{agent_userid}]]></UserID>
<ExternalUserID><![CDATA[{ext_uid}]]></ExternalUserID>
<State><![CDATA[{state}]]></State>
</xml>"""

# ============================================================
# 活码管理器 (对应 AgentBindService + QrCodeService)
# ============================================================
class QrCodeManager:
    """管理活码，严格模拟真实上下码流程"""

    def __init__(self, config: Config, pool: GlobalPool, redis: 'SimulatedRedis'):
        self.config = config
        self.pool = pool
        self.redis = redis
        self.qrcodes: List[QrCode] = []
        self.rotation_events: deque = deque(maxlen=500)
        self._lock = threading.Lock()
        self.warn_logs: deque = deque(maxlen=100)
        self._create_qrcodes()

    def _create_qrcodes(self):
        """创建活码 (对应 QrCodeService.bindAgents)"""
        school_names = [
            "兰州一中", "兰州五中", "兰州二中", "兰州三中", "兰州四中",
            "兰州六中", "兰州七中", "兰州八中", "兰州九中", "兰州十中",
            "兰州十一中", "兰州十二中", "兰州十三中", "兰州十四中", "兰州十五中",
            "兰州十六中", "兰州十七中", "兰州十八中", "兰州十九中", "兰州二十中",
        ]

        for i in range(self.config.num_qrcodes):
            qr = QrCode(
                qr_id=100 + i,
                name=school_names[i],
                school_id=f"SCHOOL_SIM_{i:03d}",
                warn_ratio=self.config.warn_ratio,
                urgent_ratio=self.config.urgent_ratio,
            )

            # bindAgents: 初始分配接待员 (从全局池取)
            for j in range(self.config.agents_per_qr):
                agent = self.pool.take_standby()
                if agent is None:
                    break
                qa = QrAgent(
                    agent=agent,
                    role='service' if j == 0 else 'receptionist',
                    daily_max=agent.daily_max,
                    daily_current=0,
                    sort_order=j,
                    status='active',
                )
                qr.agents.append(qa)

            self.qrcodes.append(qr)

    def find_qr_by_state(self, state: str) -> Optional[QrCode]:
        """通过 state (schoolId) 查找活码 (对应 qrCodeRepo.findBySchoolId)"""
        for qr in self.qrcodes:
            if qr.school_id == state:
                return qr
        return None

    def get_exclude_userids(self, qr: QrCode) -> Set[str]:
        """构建排除列表: 已在活码上的所有员工 (对应 expandQrCodeUsers 中的 excludeUserids)"""
        return {qa.agent.userid for qa in qr.agents if qa.status in ('active', 'full')}

    # ============================================================
    # incrementDailyCount (对应 AgentBindService.incrementDailyCount)
    # ============================================================
    def increment_daily_count(self, qr: QrCode, userid: str) -> int:
        """
        日计数 +1 (模拟 Redis INCR + DB 持久化)
        对应: agentBindService.incrementDailyCount(userId, state)
        """
        # Redis INCR agent:daily:{userId}:{qrId}
        per_qr_key = f"agent:daily:{userid}:{qr.qr_id}"
        per_qr_new = self.redis.incr(per_qr_key)

        # Redis INCR agent:daily:total:{userId}
        total_key = f"agent:daily:total:{userid}"
        global_new = self.redis.incr(total_key)

        # 持久化到 qr_agent.daily_current
        for qa in qr.agents:
            if qa.agent.userid == userid and qa.status == 'active':
                qa.daily_current = per_qr_new
                break

        # 持久化到 global_agent_pool.daily_current
        self.pool.update_daily_current(userid, global_new)

        return global_new

    # ============================================================
    # checkAndRotate (对应 AgentBindService.checkAndRotate)
    # ============================================================
    def check_and_rotate(self, qr: QrCode, userid: str, global_count: int):
        """
        三级阈值检查
        对应: agentBindService.checkAndRotate(qrCodeId, userId, globalCount)
        """
        # 获取该员工的 dailyMax
        agent = None
        for qa in qr.agents:
            if qa.agent.userid == userid:
                agent = qa.agent
                daily_max = qa.daily_max
                break
        if agent is None:
            return

        warn_threshold = daily_max * qr.warn_ratio // 100
        urgent_threshold = daily_max * qr.urgent_ratio // 100

        if global_count >= daily_max:
            # 满员 -> 下码 + 上码
            self.expand_qr_code_users(qr, userid)
        elif global_count >= urgent_threshold:
            # 紧急 -> 预激活后备 (不替换)
            self.pre_activate_backup(qr)
        elif global_count >= warn_threshold:
            # 预警 -> 仅日志
            self.warn_logs.append(
                f"{datetime.now().strftime('%H:%M:%S')} WARN {qr.name} "
                f"{agent.name}({userid}) {global_count}/{daily_max} ({warn_threshold}%+)"
            )

    # ============================================================
    # expandQrCodeUsers (对应 AgentBindService.expandQrCodeUsers)
    # ============================================================
    def expand_qr_code_users(self, qr: QrCode, full_userid: str):
        """
        满员轮换: 下码 + 上码
        对应: agentBindService.expandQrCodeUsers(qrCodeId, fullUserId, qr, fullPool)
        """
        with self._lock:
            # 1. 分布式锁 (模拟: 已有锁保护)
            # 2. rotateMode 检查
            if qr.rotate_mode == 'manual':
                return

            # 3. 构建排除列表
            exclude_userids = self.get_exclude_userids(qr)

            # 找到满员员工
            full_qa = None
            for qa in qr.agents:
                if qa.agent.userid == full_userid and qa.status == 'active':
                    full_qa = qa
                    break
            if full_qa is None:
                return  # 已经被其他线程处理

            # 4. 从全局池取后备
            backup = self.pool.take_standby(exclude_userids)
            if backup is None:
                self.warn_logs.append(
                    f"{datetime.now().strftime('%H:%M:%S')} ALERT {qr.name} 池枯竭！"
                )
                return

            # 5. 新接待员上码 (创建 qr_agent 记录)
            max_sort = max((qa.sort_order for qa in qr.agents), default=0)
            new_qa = QrAgent(
                agent=backup,
                role='receptionist',
                daily_max=backup.daily_max,
                daily_current=0,
                sort_order=max_sort + 1,
                status='active',
            )
            qr.agents.append(new_qa)

            # 6. 满员员工下码 (标记 full)
            full_qa.status = 'full'
            full_qa.replaced_by = backup.userid
            qr.rotation_events += 1

            # 7. 全局池标记满员 (poolService.markFull)
            self.pool.mark_full(full_userid)

            # 8. 记录轮转事件 (afterCommit 同步企微 — 模拟)
            event = RotationEvent(
                timestamp=time.time(),
                event_type='expand',
                qr_name=qr.name,
                off_agent=full_qa.agent.name,
                on_agent=backup.name,
                off_daily=full_qa.daily_current,
                reason=f"全局日限到达({full_qa.daily_current}/{full_qa.daily_max}) — 自动扩容"
            )
            self.rotation_events.append(event)

    # ============================================================
    # preActivateBackup (对应 AgentBindService.preActivateBackup)
    # ============================================================
    def pre_activate_backup(self, qr: QrCode):
        """
        紧急阈值预激活: 额外激活一名后备 (不下码任何人)
        对应: agentBindService.preActivateBackup(qrCodeId, qr)
        """
        with self._lock:
            if qr.rotate_mode == 'manual':
                return

            exclude_userids = self.get_exclude_userids(qr)

            backup = self.pool.take_standby(exclude_userids)
            if backup is None:
                return

            max_sort = max((qa.sort_order for qa in qr.agents), default=0)
            new_qa = QrAgent(
                agent=backup,
                role='receptionist',
                daily_max=backup.daily_max,
                daily_current=0,
                sort_order=max_sort + 1,
                status='active',
            )
            qr.agents.append(new_qa)
            qr.pre_activate_events += 1

            event = RotationEvent(
                timestamp=time.time(),
                event_type='pre_activate',
                qr_name=qr.name,
                off_agent='',
                on_agent=backup.name,
                off_daily=0,
                reason=f"全局紧急阈值触发 — 提前激活"
            )
            self.rotation_events.append(event)

    # ============================================================
    # process_event (对应 CallbackWorker.handleAddSuccess)
    # ============================================================
    def process_event(self, state: str, agent_userid: str) -> Optional[RotationEvent]:
        """
        处理一个 add_external_contact 事件
        对应: CallbackWorker.handleAddSuccess -> incrementDailyCount
        """
        # 1. 通过 state 找活码
        qr = self.find_qr_by_state(state)
        if qr is None:
            return None  # 静默失败 (state 不匹配任何活码)

        qr.total_events += 1

        # 2. incrementDailyCount
        global_count = self.increment_daily_count(qr, agent_userid)

        # 3. checkAndRotate
        self.check_and_rotate(qr, agent_userid, global_count)

        return None

    # ============================================================
    # 统计
    # ============================================================
    def get_all_stats(self) -> Dict:
        with self._lock:
            qr_stats = []
            for qr in self.qrcodes:
                active = sum(1 for a in qr.agents if a.status == 'active')
                full = sum(1 for a in qr.agents if a.status == 'full')
                qr_stats.append({
                    'id': qr.qr_id, 'name': qr.name, 'school_id': qr.school_id,
                    'active': active, 'full': full,
                    'total_agents': len(qr.agents),
                    'total_events': qr.total_events,
                    'total_daily': sum(a.daily_current for a in qr.agents),
                    'rotations': qr.rotation_events,
                    'pre_activates': qr.pre_activate_events,
                })

            return {
                'qr_stats': qr_stats,
                'recent_rotations': list(self.rotation_events),
                'recent_warns': list(self.warn_logs)[-5:],
                'pool': self.pool.get_stats(),
            }

# ============================================================
# 实时控制台仪表盘
# ============================================================
class Dashboard:
    """实时控制台仪表盘"""

    def __init__(self, config: Config, qr_manager: QrCodeManager, pool: GlobalPool,
                 stream: 'SimulatedRedisStream' = None, workers: List['CallbackWorkerThread'] = None,
                 injector: 'EventInjector' = None):
        self.config = config
        self.qr_manager = qr_manager
        self.pool = pool
        self.stream = stream
        self.workers = workers or []
        self.injector = injector
        self.start_time = time.time()
        self.event_count = 0
        self.last_event_count = 0
        self.last_refresh = time.time()
        self.events_per_sec = 0.0
        self._lock = threading.Lock()
        self.running = True
        self.log_fh = None

        if config.log_file:
            self.log_fh = open(config.log_file, 'w', encoding='utf-8')
            self.log_fh.write(f"# 活码轮转压力模拟引擎 v2 日志\n")
            self.log_fh.write(f"# 开始时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
            self.log_fh.write(f"# 匹配真实系统逻辑: incrementDailyCount -> checkAndRotate "
                            f"(warn/urgent/dailyMax) -> expandQrCodeUsers / preActivateBackup\n")
            self.log_fh.write(f"# 活码={config.num_qrcodes} 池={config.pool_size} "
                            f"日限={config.daily_max} 每码={config.agents_per_qr}人 "
                            f"阈值w={config.warn_ratio}% u={config.urgent_ratio}% "
                            f"总事件={config.total_events:,} 间隔={config.event_rate_ms}ms\n\n")
            self.log_fh.flush()

        self.use_colors = sys.stdout.isatty()

    def close_log(self):
        if self.log_fh:
            self.log_fh.close()
            self.log_fh = None

    def add_events(self, count: int):
        with self._lock:
            self.event_count += count

    def _color(self, code: str, text: str) -> str:
        if not self.use_colors:
            return text
        colors = {
            'red': '\033[91m', 'green': '\033[92m', 'yellow': '\033[93m',
            'blue': '\033[94m', 'cyan': '\033[96m', 'bold': '\033[1m',
            'reset': '\033[0m', 'magenta': '\033[95m', 'dim': '\033[2m',
        }
        return f"{colors.get(code, '')}{text}{colors['reset']}"

    def _progress_bar(self, value: int, max_val: int, width: int = 20) -> str:
        if max_val == 0:
            return '─' * width
        ratio = min(value / max_val, 1.0)
        filled = int(ratio * width)
        if ratio < 0.3: color = 'green'
        elif ratio < 0.7: color = 'yellow'
        elif ratio < 0.95: color = 'magenta'
        else: color = 'red'
        return self._color(color, '█' * filled + '─' * (width - filled))

    def render(self):
        now = time.time()
        elapsed = now - self.start_time
        with self._lock:
            count = self.event_count
        delta_count = count - self.last_event_count
        delta_time = now - self.last_refresh
        if delta_time > 0:
            self.events_per_sec = delta_count / delta_time
        self.last_event_count = count
        self.last_refresh = now

        remaining = self.config.total_events - count
        if self.events_per_sec > 0:
            eta_str = str(timedelta(seconds=int(remaining / self.events_per_sec)))
        else:
            eta_str = "inf"

        elapsed_str = str(timedelta(seconds=int(elapsed)))
        stats = self.qr_manager.get_all_stats()
        pool_stats = stats['pool']

        os.system('cls' if os.name == 'nt' else 'clear')

        # 标题
        print(self._color('bold', '╔══════════════════════════════════════════════════════════════════════╗'))
        print(self._color('bold', '║    活码轮转压力模拟 v2 (匹配真实系统逻辑)                              ║'))
        print(self._color('bold', '╚══════════════════════════════════════════════════════════════════════╝'))

        # 流量
        print()
        print(self._color('cyan', '━━━ 流量概览 ━━━'))
        pct = count * 100 / self.config.total_events
        print(f"  事件: {self._color('bold', f'{count:,}')} / {self.config.total_events:,} "
              f"({pct:.1f}%)  {self._progress_bar(count, self.config.total_events, 30)}")
        print(f"  速率: {self._color('green', f'{self.events_per_sec:,.0f}')} evt/s  "
              f"已运行 {elapsed_str}  剩余 {eta_str}")

        total_expand = sum(q['rotations'] for q in stats['qr_stats'])
        total_pre = sum(q['pre_activates'] for q in stats['qr_stats'])
        print(f"  轮转: {self._color('magenta', str(total_expand))} 次(满员下码)  "
              f"预激活: {self._color('yellow', str(total_pre))} 次(紧急激活)")

        # Stream 和 Worker 状态
        if self.stream:
            stream_len = self.stream.xlen()
            pending = self.stream.xpending_count()
            injected = self.injector.injected if self.injector else 0
            print(f"  Stream: 队列={stream_len}  Pending={pending}  已注入={injected:,}")
        if self.workers:
            worker_stats = '  '.join(
                f"{w.name}:{self._color('green', str(w.processed))}" for w in self.workers
            )
            print(f"  Worker: {worker_stats}")

        # 全局池
        print()
        print(self._color('cyan', '━━━ 全局池 (GlobalAgentPool) ━━━'))
        avail_pct = pool_stats['available'] * 100 // max(pool_stats['total'], 1)
        color = 'green' if avail_pct > 50 else 'yellow' if avail_pct > 20 else 'red'
        print(f"  standby: {self._color(color, str(pool_stats['available']))} / {pool_stats['total']}  "
              f"{self._progress_bar(pool_stats['available'], pool_stats['total'], 20)}")
        print(f"  满员池: {self._color('red', str(pool_stats['full_pool_size']))} 人  "
              f"已取: {pool_stats['take_count']}  懒清理: {pool_stats['lazy_deleted']}")

        # 活码状态
        print()
        print(self._color('cyan', '━━━ 活码状态 ━━━'))
        header = f"  {'活码':<12} {'活跃':>4} {'已满':>4} {'总人':>5} {'日均':>6} {'事件':>8} {'扩容':>4} {'预激':>4} 热力"
        print(self._color('dim', header))
        qr_stats = stats['qr_stats']
        max_evt = max((q['total_events'] for q in qr_stats), default=1)
        for q in qr_stats:
            heat = self._progress_bar(q['total_events'], max_evt, 12)
            print(f"  {q['name']:<12} "
                  f"{self._color('green', str(q['active']).rjust(4))} "
                  f"{self._color('red' if q['full'] > 0 else 'reset', str(q['full']).rjust(4))} "
                  f"{str(q['total_agents']).rjust(5)} "
                  f"{str(q['total_daily']).rjust(6)} "
                  f"{self._color('yellow', str(q['total_events']).rjust(8))} "
                  f"{self._color('magenta' if q['rotations'] > 0 else 'reset', str(q['rotations']).rjust(4))} "
                  f"{self._color('yellow' if q['pre_activates'] > 0 else 'reset', str(q['pre_activates']).rjust(4))} "
                  f" {heat}")

        # 最近轮转
        recent = stats['recent_rotations']
        if recent:
            print()
            print(self._color('cyan', '━━━ 最近轮转 (最新 10 条) ━━━'))
            print(self._color('dim', f"  {'时间':<10} {'活码':<12} {'类型':<12} {'下码(满员)':<12} -> {'上码(新人)':<12} {'原因':<20}"))
            for evt in recent[-10:]:
                ts = datetime.fromtimestamp(evt.timestamp).strftime('%H:%M:%S')
                etype = 'EXPAND' if evt.event_type == 'expand' else 'PRE_ACT'
                off = evt.off_agent if evt.off_agent else '(无)'
                print(f"  {ts:<10} {evt.qr_name:<12} "
                      f"{self._color('magenta' if etype == 'EXPAND' else 'yellow', etype):<12} "
                      f"{self._color('red', off):<12} -> "
                      f"{self._color('green', evt.on_agent):<12} "
                      f"{evt.reason[:20]}")

        # 预警日志
        warns = stats['recent_warns']
        if warns:
            print()
            print(self._color('yellow', '━━━ 最近预警 ━━━'))
            for w in warns[-5:]:
                print(f"  {w}")

        # 底部
        print()
        print(self._color('dim', f"  按 Ctrl+C 停止  |  刷新 {self.config.refresh_interval}s  |  "
                               f"{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}"))
        print(self._color('dim', '━' * 74))

        # 写日志
        if self.log_fh:
            total_rot = sum(q['rotations'] for q in stats['qr_stats'])
            total_pre_act = sum(q['pre_activates'] for q in stats['qr_stats'])
            active_total = sum(q['active'] for q in stats['qr_stats'])

            # 写入概览行
            self.log_fh.write(
                f"\n{'='*80}\n"
                f"[{datetime.now().strftime('%H:%M:%S')}] "
                f"events={count}/{self.config.total_events} ({100*count//max(self.config.total_events,1)}%) "
                f"rate={self.events_per_sec:.0f}/s\n"
                f"  轮转: expand={total_rot} pre_act={total_pre_act} | "
                f"池: standby={pool_stats['available']}/{pool_stats['total']} "
                f"满员池={pool_stats['full_pool_size']} "
                f"已取={pool_stats['take_count']} 懒清理={pool_stats['lazy_deleted']}\n"
                f"  Stream: 注入={self.injector.injected if self.injector else '?'} "
                f"已消费={sum(w.processed for w in self.workers)} | "
                f"Worker: {' | '.join(f'{w.name}={w.processed}' for w in self.workers)}\n"
            )

            # 写入最近轮转明细 (上下码路径)
            recent_rotations = stats.get('recent_rotations', [])
            if recent_rotations:
                # 只写本轮新增的轮转 (相对于上次日志)
                self.log_fh.write(f"  --- 最近轮转 (上下码路径) ---\n")
                for evt in recent_rotations[-20:]:
                    ts = datetime.fromtimestamp(evt.timestamp).strftime('%H:%M:%S')
                    if evt.event_type == 'expand':
                        path = f"  [下码] {evt.off_agent} (日限{evt.off_daily}) ──> [上码] {evt.on_agent}"
                    else:
                        path = f"  [预激活] 池取 {evt.on_agent} 加入活码 (无下码)"
                    self.log_fh.write(f"  {ts} | {evt.qr_name:<10} | {path}\n")

            # 写入活码员工状态 (每 10% 进度写一次)
            if count % (max(self.config.total_events // 10, 1)) < self.events_per_sec * 2 + 1:
                self.log_fh.write(f"\n  {'='*70}\n")
                self.log_fh.write(f"  活码员工状态快照 (进度 {100*count//max(self.config.total_events,1)}%)\n")
                self.log_fh.write(f"  {'='*70}\n")
                for q in stats['qr_stats']:
                    self.log_fh.write(
                        f"  [{q['name']}] 总人={q['total_agents']} | "
                        f"活跃={q['active']} | 已满={q['full']} | "
                        f"事件={q['total_events']} | "
                        f"扩容={q['rotations']}次 | 预激活={q['pre_activates']}次\n"
                    )
                    # 从 qr_manager 获取该活码的员工详情
                    for qr in self.qr_manager.qrcodes:
                        if qr.name == q['name']:
                            # 显示活跃员工 (最多 10 人)
                            active_list = [qa for qa in qr.agents if qa.status == 'active']
                            if active_list:
                                agents_str = ', '.join(
                                    f"{qa.agent.name}({qa.daily_current}/{qa.daily_max})"
                                    for qa in active_list[:10]
                                )
                                if len(active_list) > 10:
                                    agents_str += f" ... +{len(active_list)-10}人"
                                self.log_fh.write(f"    活跃: {agents_str}\n")
                            # 显示已满员工 (最多 5 人)
                            full_list = [qa for qa in qr.agents if qa.status == 'full']
                            if full_list:
                                agents_str = ', '.join(
                                    f"{qa.agent.name}(满,被{qa.replaced_by}替换)"
                                    for qa in full_list[:5]
                                )
                                if len(full_list) > 5:
                                    agents_str += f" ... +{len(full_list)-5}人"
                                self.log_fh.write(f"    已满: {agents_str}\n")
                            break

            self.log_fh.flush()

    def stop(self):
        self.running = False
        self.close_log()

# ============================================================
# CallbackWorker 线程 (模拟真实 CallbackWorker)
# ============================================================
class CallbackWorkerThread:
    """
    模拟 CallbackWorker 线程:
    - XREADGROUP 从 wecom:callback:stream 批量拉取 (COUNT 50, BLOCK 5000ms)
    - 每条消息: handleAddSuccess -> incrementDailyCount -> checkAndRotate
    - XACK 确认
    - afterCommit 模拟企微 API 延迟
    """

    def __init__(self, name: str, config: Config, stream: SimulatedRedisStream,
                 qr_manager: 'QrCodeManager', dashboard: 'Dashboard'):
        self.name = name
        self.config = config
        self.stream = stream
        self.qr_manager = qr_manager
        self.dashboard = dashboard
        self.running = True
        self.thread = None
        self.processed = 0
        self.skipped = 0

    def run(self):
        while self.running:
            # XREADGROUP GROUP {group} {consumer} COUNT {batch} BLOCK {block}
            batch = self.stream.xreadgroup(
                consumer=self.name,
                count=self.config.batch_size,
                block_ms=100 if self.config.fast_mode else self.config.block_ms
            )

            if not batch:
                # 阻塞超时 — 检查是否还有消息要处理
                if self.stream.xlen() == 0 and self.stream.xpending_count() == 0:
                    time.sleep(0.01)  # 短暂休眠避免忙等
                continue

            for msg in batch:
                if not self.running:
                    break

                event = msg.event_data
                state = event.get('state', '')
                userid = event.get('userid', '')

                if not userid:
                    self.stream.xack(msg.msg_id)
                    self.skipped += 1
                    continue

                # handleAddSuccess 完整链路
                self.qr_manager.process_event(state, userid)
                self.processed += 1

                # XACK
                self.stream.xack(msg.msg_id)

                # 通知仪表盘 (每处理一条就报告)
                self.dashboard.add_events(1)

                # afterCommit 模拟企微 API 延迟
                if self.config.after_commit_ms > 0:
                    time.sleep(self.config.after_commit_ms / 1000.0)

    def start(self):
        self.thread = threading.Thread(target=self.run, daemon=True, name=self.name)
        self.thread.start()

    def stop(self):
        self.running = False

# ============================================================
# 事件注入器 (模拟企微回调推送)
# ============================================================
class EventInjector:
    """
    向 SimulatedRedisStream 注入 add_external_contact 事件
    模拟企微回调推送: 客户扫码 -> 企微分配员工 -> 回调通知
    """

    def __init__(self, config: Config, stream: SimulatedRedisStream,
                 qr_manager: QrCodeManager, dashboard: Dashboard):
        self.config = config
        self.stream = stream
        self.qr_manager = qr_manager
        self.dashboard = dashboard
        self.running = True
        self.thread = None
        self.injected = 0

    def _pick_qr_weighted(self, qrcodes: List[QrCode]) -> QrCode:
        """加权选择活码 (模拟真实流量不均: 部分学校流量更大)"""
        n = len(qrcodes)
        if self.config.traffic_skew <= 0:
            return random.choice(qrcodes)

        # 给每个活码随机权重，反映真实世界中不同学校流量差异
        weights = [1.0 + random.uniform(0, self.config.traffic_skew * 3) for _ in range(n)]
        total = sum(weights)
        r = random.random() * total
        cumulative = 0
        for qr, w in zip(qrcodes, weights):
            cumulative += w
            if r <= cumulative:
                return qr
        return qrcodes[-1]

    def run(self):
        qrcodes = self.qr_manager.qrcodes
        burst_active = False
        burst_count = 0
        burst_total = int(self.config.total_events * self.config.burst_ratio) if self.config.burst_ratio > 0 else 0

        for i in range(self.config.total_events):
            if not self.running:
                break

            # 突发模式: 集中大量事件 (模拟开学季等高峰)
            if burst_total > 0 and i < burst_total:
                if not burst_active:
                    burst_active = True
                burst_count += 1
                # 突发期间: 1ms 间隔 (1000 evt/s)
                delay = 0.001 if not self.config.fast_mode else 0
            else:
                burst_active = False
                delay = self.config.event_rate_ms / 1000.0 if not self.config.fast_mode else 0

            # 加权选择活码
            qr = self._pick_qr_weighted(qrcodes)

            # 从该活码上选一个 active 接待员 (模拟企微随机分配)
            with self.qr_manager._lock:
                active_qas = [qa for qa in qr.agents if qa.status == 'active']

            if active_qas:
                chosen_qa = random.choice(active_qas)
                agent_userid = chosen_qa.agent.userid
            else:
                continue  # 无可用接待员，事件丢失 (企微不会分配)

            # XADD 到 stream
            ext_uid = f"wmZUH8SgAA{random.randint(10**12, 10**13-1):0d}"
            self.stream.xadd({
                'userid': agent_userid,
                'state': qr.school_id,
                'external_userid': ext_uid,
            })
            self.injected += 1

            if delay > 0:
                time.sleep(delay)

        # 标记注入完成
        self.dashboard.add_events(0)  # 触发最终刷新

    def start(self):
        self.thread = threading.Thread(target=self.run, daemon=True)
        self.thread.start()

    def stop(self):
        self.running = False

# ============================================================
# 主函数
# ============================================================
def parse_args():
    config = Config()
    args = sys.argv[1:]
    i = 0
    while i < len(args):
        arg = args[i]
        if arg == '--rate' and i + 1 < len(args):
            config.event_rate_ms = int(args[i + 1]); i += 2
        elif arg == '--total' and i + 1 < len(args):
            config.total_events = int(args[i + 1]); i += 2
        elif arg == '--qrcodes' and i + 1 < len(args):
            config.num_qrcodes = int(args[i + 1]); i += 2
        elif arg == '--pool' and i + 1 < len(args):
            config.pool_size = int(args[i + 1]); i += 2
        elif arg == '--daily-max' and i + 1 < len(args):
            config.daily_max = int(args[i + 1]); i += 2
        elif arg == '--agents-per' and i + 1 < len(args):
            config.agents_per_qr = int(args[i + 1]); i += 2
        elif arg == '--warn-ratio' and i + 1 < len(args):
            config.warn_ratio = int(args[i + 1]); i += 2
        elif arg == '--urgent-ratio' and i + 1 < len(args):
            config.urgent_ratio = int(args[i + 1]); i += 2
        elif arg == '--fast':
            config.fast_mode = True; config.event_rate_ms = 0; i += 1
        elif arg == '--log' and i + 1 < len(args):
            config.log_file = args[i + 1]; i += 2
        elif arg == '--workers' and i + 1 < len(args):
            config.worker_count = int(args[i + 1]); i += 2
        elif arg == '--batch' and i + 1 < len(args):
            config.batch_size = int(args[i + 1]); i += 2
        elif arg == '--after-commit' and i + 1 < len(args):
            config.after_commit_ms = int(args[i + 1]); i += 2
        elif arg == '--traffic-skew' and i + 1 < len(args):
            config.traffic_skew = float(args[i + 1]); i += 2
        elif arg == '--burst' and i + 1 < len(args):
            config.burst_ratio = float(args[i + 1]); i += 2
        elif arg == '--help' or arg == '-h':
            print(__doc__)
            sys.exit(0)
        else:
            i += 1
    return config

def main():
    config = parse_args()

    print()
    print('╔══════════════════════════════════════════════════════════════════════╗')
    print('║    活码轮转压力模拟引擎 v2 (匹配真实系统逻辑)                         ║')
    print('╚══════════════════════════════════════════════════════════════════════╝')
    print()
    print(f'  真实对照:')
    print(f'    Redis Stream XADD -> {config.worker_count}x CallbackWorker XREADGROUP')
    print(f'    -> handleAddSuccess -> incrementDailyCount')
    print(f'    -> checkAndRotate (warn={config.warn_ratio}%/urgent={config.urgent_ratio}%/dailyMax)')
    print(f'    -> expandQrCodeUsers / preActivateBackup')
    print(f'    -> takeStandby 公平轮转 + 懒清理 -> afterCommit({config.after_commit_ms}ms)')
    print()
    print(f'  配置:')
    print(f'    活码: {config.num_qrcodes} | 池: {config.pool_size} | 日限: {config.daily_max}')
    print(f'    每码: {config.agents_per_qr}人 | 事件: {config.total_events:,} | 间隔: {config.event_rate_ms}ms')
    print(f'    Worker: {config.worker_count}线程 | 批量: {config.batch_size}/批 | '
          f'流量倾斜: {config.traffic_skew:.0%} | 突发: {config.burst_ratio:.0%}')
    if config.log_file:
        print(f'    日志: {config.log_file}')
    print()

    if config.event_rate_ms > 0 and config.total_events * config.event_rate_ms / 1000 > 300:
        print(f'  [!] 预计运行 {config.total_events * config.event_rate_ms / 60000:.0f} 分钟，Ctrl+C 可随时停止')
    print()
    print('  初始化...')

    redis = SimulatedRedis()
    pool = GlobalPool(config)
    qr_manager = QrCodeManager(config, pool, redis)
    stream = SimulatedRedisStream()

    # 创建 Worker 线程池 (模拟 4 个 CallbackWorker)
    workers = []
    for i in range(config.worker_count):
        w = CallbackWorkerThread(f'W{i+1}', config, stream, qr_manager, None)  # dashboard set later
        workers.append(w)

    # 注入器: 向 Stream 推送事件
    injector = EventInjector(config, stream, qr_manager, None)  # dashboard set later

    # 仪表盘 (需要 workers 和 injector 引用)
    dashboard = Dashboard(config, qr_manager, pool, stream, workers, injector)

    # 回填 dashboard 引用
    for w in workers:
        w.dashboard = dashboard
    injector.dashboard = dashboard

    stats = qr_manager.get_all_stats()
    total_assigned = sum(q['total_agents'] for q in stats['qr_stats'])
    print(f'  [OK] {config.num_qrcodes} 活码, {total_assigned} 接待员, {stats["pool"]["available"]} standby')
    print(f'  [OK] {config.worker_count} Worker 待命, Redis Stream 就绪')
    print(f'  [OK] afterCommit 延迟 {config.after_commit_ms}ms, 流量倾斜 {config.traffic_skew:.0%}')
    print()
    print('  3 秒后开始...')
    time.sleep(3)

    # 1. 先启动 Worker (等待消费)
    for w in workers:
        w.start()

    # 2. 再启动注入器 (推送事件)
    injector.start()

    try:
        # 等注入器完成 + Stream 清空 + Pending 归零
        while True:
            dashboard.render()
            time.sleep(config.refresh_interval)

            injector_done = not injector.thread or not injector.thread.is_alive()
            stream_empty = stream.xlen() == 0
            pending_empty = stream.xpending_count() == 0

            if injector_done and stream_empty and pending_empty:
                # 最后再等 1 秒确保 Worker 完成最后一批
                time.sleep(1.0)
                if stream.xpending_count() == 0:
                    break

        dashboard.render()
    except KeyboardInterrupt:
        injector.stop()
        for w in workers:
            w.stop()
        dashboard.stop()
        print('\n\n  停止中...')
        time.sleep(0.5)

    # 停止 Worker
    for w in workers:
        w.stop()

    # 最终报告
    print()
    print('╔══════════════════════════════════════════════════════════════════════╗')
    print('║    最终报告                                                          ║')
    print('╚══════════════════════════════════════════════════════════════════════╝')
    print()

    final_stats = qr_manager.get_all_stats()
    ps = final_stats['pool']
    total_expand = sum(q['rotations'] for q in final_stats['qr_stats'])
    total_pre = sum(q['pre_activates'] for q in final_stats['qr_stats'])

    print(f'  总事件:     {dashboard.event_count:,}')
    print(f'  扩容(满员): {total_expand} 次')
    print(f'  预激活:     {total_pre} 次')
    print(f'  池可用:     {ps["available"]}/{ps["total"]}')
    print(f'  满员池:     {ps["full_pool_size"]} 人')
    print(f'  懒清理:     {ps["lazy_deleted"]} 人')
    if stream:
        print(f'  Stream:     注入={injector.injected:,} | '
              f'已消费={sum(w.processed for w in workers):,}')
    print()

    print(f"  {'活码':<14} {'总人':>4} {'活跃':>4} {'已满':>4} {'事件':>8} {'扩容':>4} {'预激':>4}  {'占比':>6}")
    for q in final_stats['qr_stats']:
        share = q['total_events'] * 100 / max(dashboard.event_count, 1)
        print(f"  {q['name']:<14} {q['total_agents']:>4} {q['active']:>4} {q['full']:>4} "
              f"{q['total_events']:>8,} {q['rotations']:>4} {q['pre_activates']:>4}  {share:>5.1f}%")

    print()
    print(f'  完成时间: {datetime.now().strftime("%Y-%m-%d %H:%M:%S")}')
    if config.log_file:
        print(f'  日志已保存: {config.log_file}')

if __name__ == '__main__':
    main()

# 测试缺口分析

> 最后更新: 2026-06-21 · 当前测试总数: 147 (0 失败, 0 错误, 15 跳过)

## 现有测试概览

```
        ┌─────────────────┐
        │    E2E: 0       │  ← 浏览器/HTTP 端到端
        ├─────────────────┤
        │  集成测试: 6 类   │  ← @SpringBootTest + H2 + Embedded Redis
        │  (27 用例)       │
        ├─────────────────┤
        │  单元测试: 22 类  │  ← Mockito / @DataJpaTest
        │  (120 用例)      │
        └─────────────────┘
```

### 已有的集成测试 (6 类, 27 用例)

| 测试类 | 用例数 | 覆盖场景 |
|--------|--------|---------|
| QrCodeCreationFlowTest | 7 | 活码创建 → 员工绑定 → 后备池填充 → 重复拒绝 → 空员工拒绝 |
| CustomerScanFlowTest | 5 | 客户 upsert → 稀疏写入 → Redis 锁 → 已删除激活 → 日计数 |
| AgentRotationFlowTest | 7 | 扩容 → 手动模式 → 三级阈值 → 公平轮转 → 排除 → blocked 过滤 |
| GlobalAgentPoolFlowTest | 6 | ensureInPool 幂等 → markFull/dailyReset → 离职清理 → 企微状态清理 |
| AlertSystemTest | 2 | 通用告警创建 → 3 次熔断升级 blocked |

### 4 个 Disabled 测试及原因

| 测试 | 原因 | 解药 |
|------|------|------|
| CustomerScanFlowTest.shouldPublishDataFillEvent | Embedded Redis 不支持 Stream (XLEN) | Testcontainers Redis 7 |
| QrCodeCreationFlowTest.shouldAutoComputeAgentCount | 池耗尽 + H2 FOR UPDATE 差异 | Testcontainers Redis 7 |
| AlertSystemTest.shouldMeltAgentOnAddFail84061 | findByIdForUpdate + H2 悲观锁差异 | Testcontainers Redis 7 |
| AlertSystemTest.shouldCreateAlertOnEmptyBackup | 独立事务 + H2 行为不一致 | Testcontainers Redis 7 |

---

## 未覆盖的源文件 (49 个)

### 🔴 P0 — 核心数据入口链路（下周必做）

| 文件 | 职责 | 为什么重要 |
|------|------|-----------|
| `wecom/WecomCallbackController.java` | 企微回调 HTTP 端点 — 解密/验签/分发 | **所有业务流量的入口** |
| `worker/CallbackWorker.java` | 从 Redis Stream 消费回调事件 → 路由到具体 handler | 异步消费主循环 |
| `worker/DataFillWorker.java` | 消费 datafill 事件 → 调企微 API 补全客户信息 | 客户数据从"未知"变完整 |
| `service/AgentDailyCountService.java` | Redis 日计数读写 + 阈值判断 | 轮换决策的数据源 |

**建议测试类:** `CallbackFlowIntegrationTest` — 从 HTTP 回调到客户补全的完整链路

### 🟠 P1 — 合规与数据同步（两周内）

| 文件 | 职责 | 为什么重要 |
|------|------|-----------|
| `service/TransferService.java` | 在职继承 — 客户转移发起 + 结果查询 | 企微合规要求，员工离职必用 |
| `worker/TransferWorker.java` | 异步执行客户转移 | |
| `worker/TransferMonitorWorker.java` | 轮询转移结果，超时告警 | |
| `service/EmployeeSyncService.java` | 从企微同步通讯录到本地 Employee 表 | 后备池数据来源 |
| `worker/DailyResetWorker.java` | 每日 00:00 重置计数 + 恢复熔断 agent | 系统时钟依赖 |
| `worker/PatrolWorker.java` | 定时巡检 — 池空告警、异常检测 | |

**建议测试类:** `TransferFlowIntegrationTest`, `DailyResetIntegrationTest`

### 🟡 P2 — 标签与消息

| 文件 | 职责 |
|------|------|
| `service/TagService.java` | 企微企业标签 CRUD + 同步 |
| `worker/TagWorker.java` | 异步为客户打标签 |
| `worker/OutboundMsgWorker.java` | 主动推送消息给客户 |
| `service/MessageGuardService.java` | 消息防骚扰守护 |

### 🟢 P3 — 管理后台与辅助功能

| 文件 | 职责 |
|------|------|
| `controller/AdminSchoolController.java` | 学校管理 CRUD |
| `controller/AdminSchoolEntryController.java` | 学校入口管理 |
| `controller/AdminSystemConfigController.java` | 系统配置管理 |
| `controller/AlertController.java` | 告警列表/处理 |
| `controller/DistrictManagerController.java` | 区域经理管理 |
| `controller/DownloadCenterController.java` | 下载中心 |
| `controller/DownloadStatsController.java` | 下载统计 |
| `controller/FormController.java` | 表单填写 |
| `controller/FormTemplateController.java` | 表单模板 |
| `controller/HomeController.java` | 首页 |
| `controller/LoginController.java` | 登录 |
| `controller/QrCodeGroupController.java` | 活码分组 |
| `controller/UserController.java` | 用户管理 |
| `service/QrImageService.java` | ZXing 二维码图片生成 |
| `service/FormTemplateService.java` | 表单模板组装 |
| `service/DistrictManagerService.java` | 区域经理业务 |
| `service/OperationLogService.java` | 操作日志 |
| `service/DownloadLogService.java` | 下载记录 |
| `service/QrCodeGroupService.java` | 活码分组合并 |
| `service/RateLimiterService.java` | 学校查询限流 |
| `service/WecomOAuthService.java` | 企微 OAuth 登录 |
| `service/WechatSyncHealingService.java` | 企微同步自愈 |

### 🔵 P4 — 基础设施与配置

| 文件 | 职责 |
|------|------|
| `config/SecurityConfig.java` | Spring Security 认证授权链 |
| `config/CustomUserDetailsService.java` | 用户加载 |
| `config/DownloadAuthenticationFilter.java` | 下载链接一次性 token |
| `config/GlobalControllerAdvice.java` | 全局异常处理 |
| `config/AsyncConfig.java` | 异步线程池配置 |
| `config/RedisConfig.java` | Redis 序列化配置 |
| `config/DataInitializer.java` | 系统初始种子数据 |

---

## 如何补充

### 集成测试 (@SpringBootTest) 模板

```java
@Import(WecomApiMockConfig.class)
@DisplayName("xxx 集成测试")
class XxxFlowTest extends BaseIntegrationTest {

    @Autowired private XxxService xxxService;
    @Autowired private XxxRepository xxxRepo;
    @Autowired private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        // 严格 FK 顺序清理
        childRepo.deleteAll();
        parentRepo.deleteAll();
    }

    @Test
    @DisplayName("场景描述")
    void shouldXxxYyy() {
        // Given: 准备数据
        // When: 调 Service
        // Then: 断言 DB + Redis 状态
    }
}
```

### 需要 Testcontainers Redis 7 的场景

以下场景依赖 Redis Stream / 更完整的 Redis 命令集，需要切换到 Testcontainers：

1. **CallbackWorker 消费循环** — Stream XREADGROUP/XACK
2. **DataFillWorker** — Stream XADD/XLEN
3. **TransferWorker** — Stream 消息
4. **TagWorker** — Stream 消息

切换方式：将 `BaseIntegrationTest` 中的 Embedded Redis 替换为：

```java
@Testcontainers
abstract class BaseIntegrationTest {
    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
}
```

前提：Docker Desktop 必须在 Windows 上运行。

### Controller 层测试

用 `@WebMvcTest` + MockMvc，不启动完整 Spring 上下文：

```java
@WebMvcTest(AdminSchoolController.class)
@Import(SecurityConfig.class)
class AdminSchoolControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private SchoolService schoolService;

    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldReturnSchoolList() throws Exception {
        mockMvc.perform(get("/admin/schools"))
               .andExpect(status().isOk());
    }
}
```

---

## 性能基准 (当前)

```
集成测试 (6 类): ~45s (含 Embedded Redis 启动 2s + Spring 上下文 5s)
单元测试 (22 类): ~30s
总计:             ~75s
```

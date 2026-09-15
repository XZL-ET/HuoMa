package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.entity.AgentAlert;
import com.bookstore.qrcode.entity.CustomerDeletionEvent;
import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.entity.SystemConfig;
import com.bookstore.qrcode.repository.CustomerDeletionEventRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.SystemConfigRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;

/**
 * 客户删除员工每日汇总日报服务。
 * <p>
 * 查询指定日期（通常为昨日）的「客户删除员工」事件，按员工聚合删除客户数，
 * 通过企微应用消息推送给配置的管理员，让管理员知晓哪些员工被多少客户删除。
 * 具体客户明细由管理员登录火马平台「删除记录」页查看。</p>
 *
 * <p>员工显示名查不到时回退为 {@code userid}。</p>
 *
 * @author Bookstore Dev
 * @since 2.x
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeletionReportService {

    /** 系统配置表中存储日报接收人的键 */
    public static final String RECIPIENTS_CONFIG_KEY = "deletion_report_admin_userids";

    /** 系统配置表中存储日报推送时间的键（值格式 HH:mm） */
    public static final String TIME_CONFIG_KEY = "deletion_report_time";

    /** 系统配置表中存储「今日推送」接收人的键（删除记录页独立配置） */
    public static final String TODAY_RECIPIENTS_CONFIG_KEY = "deletion_report_today_recipients";

    /** 日报默认推送时间（早 9 点），未配置或非法时回退此值 */
    public static final LocalTime DEFAULT_PUSH_TIME = LocalTime.of(9, 0);

    /** 日报统计与推送所用的业务时区 */
    public static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Shanghai");

    /** 锁被其他执行占用时的哨兵返回值 */
    public static final int LOCK_BUSY = -1;

    /** 被删超过该人数的员工才逐行列出，其余合并为员工总数 */
    private static final long HIGHLIGHT_MIN_DELETIONS = 5;

    /** 重点员工逐行列出的最大行数，防企微 2048 字节超限 */
    private static final int MAX_DETAIL_LINES = 50;

    /** 分布式锁键与 TTL，防止定时任务与手动推送撞车重复推送 */
    private static final String LOCK_KEY = "lock:deletion-report:daily";
    private static final Duration LOCK_TTL = Duration.ofMinutes(30);

    private final CustomerDeletionEventRepository deletionRepo;
    private final EmployeeRepository employeeRepo;
    private final SystemConfigRepository configRepository;
    private final WecomApiClient wecomApi;
    private final AlertService alertService;
    private final StringRedisTemplate redisTemplate;

    /** 推送对象兜底值（企微 userid，逗号分隔），系统配置未设置时使用 */
    @Value("${app.deletion-report.admin-userids:}")
    private String adminUserids;

    /**
     * 加分布式锁执行日报推送，供定时任务与手动推送共用。
     * <p>锁被占用时返回 {@link #LOCK_BUSY}，避免与另一实例或手动推送撞车重复推送。</p>
     *
     * @param date 统计日期
     * @return 成功推送的管理员数量，或 {@link #LOCK_BUSY} 表示正在执行中
     */
    public int reportWithLock(LocalDate date) {
        return withLock(() -> report(date));
    }

    public int reportTodayWithLock(String recipientsRaw) {
        return withLock(() -> reportTodayUntilNow(recipientsRaw));
    }

    private int withLock(IntSupplier action) {
        String lockValue = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY, lockValue, LOCK_TTL);
        if (!Boolean.TRUE.equals(locked)) {
            log.info("客户删除员工日报推送已在进行中，跳过本次");
            return LOCK_BUSY;
        }
        try {
            return action.getAsInt();
        } finally {
            try {
                redisTemplate.execute(RedisConfig.SAFE_UNLOCK_SCRIPT, List.of(LOCK_KEY), lockValue);
            } catch (Exception e) {
                log.warn("释放日报推送锁失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 执行某日期的客户删除员工日报推送。
     *
     * @param date 统计日期（统计 [date 00:00, date+1 00:00) 内的事件）
     * @return 成功推送的管理员数量
     */
    public int report(LocalDate date) {
        List<String> admins = resolveRecipients();
        if (admins.isEmpty()) {
            log.warn("客户删除员工日报：未配置推送对象，跳过推送 date={}", date);
            return 0;
        }
        return reportRange(admins, date.atStartOfDay(), date.plusDays(1).atStartOfDay(), "昨日", date);
    }

    public int reportTodayUntilNow(String recipientsRaw) {
        List<String> admins = parseAdmins(recipientsRaw);
        if (admins.isEmpty()) {
            log.warn("客户删除员工今日推送：接收人为空，跳过推送");
            return 0;
        }
        LocalDateTime now = LocalDateTime.now(REPORT_ZONE);
        String period = "今日截至 " + now.format(DateTimeFormatter.ofPattern("HH:mm"));
        return reportRange(admins, now.toLocalDate().atStartOfDay(), now, period, now.toLocalDate());
    }

    private int reportRange(List<String> admins, LocalDateTime start, LocalDateTime end,
                            String period, LocalDate date) {
        List<CustomerDeletionEvent> events = deletionRepo.findByDirectionAndDeletedAtBetween(
                CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT, start, end);
        if (events.isEmpty()) {
            log.info("客户删除员工日报：{} 无删除事件，跳过推送", date);
            return 0;
        }
        String message = buildMessage(date, period, events);
        return sendToAdmins(admins, message, date);
    }

    private int sendToAdmins(List<String> admins, String message, LocalDate date) {
        int sent = 0;
        for (String admin : admins) {
            try {
                wecomApi.sendReportMessage(admin, message);
                sent++;
            } catch (Exception e) {
                log.error("客户删除员工日报推送失败: date={}, admin={}", date, admin, e);
                alertService.createAlert(admin, "deletion_report_fail",
                        AgentAlert.AlertSeverity.high,
                        Map.of("date", date.toString(), "admin", admin,
                                "error", String.valueOf(e.getMessage())),
                        AgentAlert.AutoAction.none, null);
            }
        }
        log.info("客户删除员工日报完成: date={}, admins={}, sent={}", date, admins.size(), sent);
        return sent;
    }

    /** 接收人解析：优先系统配置，回退环境变量。 */
    private List<String> resolveRecipients() {
        return parseAdmins(getEffectiveRecipients());
    }

    /**
     * 实际生效的接收人原始串（逗号分隔）。
     * <p>系统配置键存在时始终以配置值为准（包括清空后的空串），
     * 仅当键不存在时才回退到环境变量。这样管理员在界面清空接收人
     * 即可停用推送，而不会被环境变量的旧值覆盖。</p>
     */
    public String getEffectiveRecipients() {
        return configRepository.findByConfigKey(RECIPIENTS_CONFIG_KEY)
                .map(SystemConfig::getConfigValue)
                .orElse(adminUserids);
    }

    public String getTodayRecipients() {
        return configRepository.findByConfigKey(TODAY_RECIPIENTS_CONFIG_KEY)
                .map(SystemConfig::getConfigValue)
                .orElse("");
    }

    public void saveTodayRecipients(String raw) {
        String normalized = normalizeAdmins(raw);
        SystemConfig config = configRepository.findByConfigKey(TODAY_RECIPIENTS_CONFIG_KEY)
                .orElseGet(() -> {
                    SystemConfig c = new SystemConfig();
                    c.setConfigKey(TODAY_RECIPIENTS_CONFIG_KEY);
                    c.setConfigName("删除记录页今日推送接收人（企微 userid，逗号分隔）");
                    return c;
                });
        config.setConfigValue(normalized);
        configRepository.save(config);
    }

    /**
     * 实际生效的日报推送时间。
     * <p>优先读取系统配置键 {@link #TIME_CONFIG_KEY}（HH:mm），未配置或非法时回退
     * {@link #DEFAULT_PUSH_TIME}。</p>
     */
    public LocalTime resolvePushTime() {
        return configRepository.findByConfigKey(TIME_CONFIG_KEY)
                .map(SystemConfig::getConfigValue)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(this::parsePushTime)
                .orElse(DEFAULT_PUSH_TIME);
    }

    private LocalTime parsePushTime(String value) {
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException e) {
            log.warn("客户删除员工日报推送时间配置非法，回退默认 09:00: {}", value);
            return DEFAULT_PUSH_TIME;
        }
    }

    private List<String> parseAdmins(String raw) {
        String normalized = normalizeAdmins(raw);
        if (normalized.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(normalized.split(","));
    }

    private String normalizeAdmins(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(","));
    }

    private String buildMessage(LocalDate date, String period, List<CustomerDeletionEvent> events) {
        Map<String, Long> countByUserid = new LinkedHashMap<>();
        for (CustomerDeletionEvent e : events) {
            countByUserid.merge(e.getUserid(), 1L, Long::sum);
        }
        List<Map.Entry<String, Long>> sorted = countByUserid.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .toList();

        List<Map.Entry<String, Long>> highlighted = sorted.stream()
                .filter(e -> e.getValue() > HIGHLIGHT_MIN_DELETIONS)
                .toList();
        List<Map.Entry<String, Long>> shown = highlighted.size() > MAX_DETAIL_LINES
                ? highlighted.subList(0, MAX_DETAIL_LINES)
                : highlighted;
        int hiddenHighlighted = highlighted.size() - shown.size();
        long restCount = sorted.size() - highlighted.size();

        StringBuilder sb = new StringBuilder();
        sb.append("【客户删除员工日报】").append(date).append("\n");
        sb.append(period).append(" 共 ").append(events.size()).append(" 位客户删除员工，涉及 ")
                .append(sorted.size()).append(" 名员工：\n");
        int i = 1;
        for (Map.Entry<String, Long> entry : shown) {
            sb.append(i++).append(". ")
                    .append(resolveEmployeeName(entry.getKey()))
                    .append("：").append(entry.getValue()).append(" 人\n");
        }
        if (hiddenHighlighted > 0) {
            sb.append("另有 ").append(hiddenHighlighted).append(" 名员工被删超 ")
                    .append(HIGHLIGHT_MIN_DELETIONS).append(" 人未列出。\n");
        }
        if (restCount > 0) {
            sb.append("其余 ").append(restCount).append(" 名员工各被删除不超过 ")
                    .append(HIGHLIGHT_MIN_DELETIONS).append(" 人。\n");
        }
        sb.append("明细请登录火马平台「数据分析 → 删除记录」查看。");
        return sb.toString();
    }

    private String resolveEmployeeName(String userid) {
        return employeeRepo.findByUserid(userid)
                .map(Employee::getName)
                .filter(name -> name != null && !name.isBlank())
                .orElse(userid);
    }
}

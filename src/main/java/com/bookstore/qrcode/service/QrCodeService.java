package com.bookstore.qrcode.service;

import com.bookstore.qrcode.dto.QrCodeCreateRequest;
import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class QrCodeService {

    private final QrCodeRepository qrCodeRepo;
    private final QrAgentRepository qrAgentRepo;
    private final QrBackupPoolRepository backupRepo;
    private final QrRotateLogRepository rotateLogRepo;
    private final AgentRepository agentRepo;
    private final WecomApiClient wecomApi;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    // ==================== 查询 ====================

    public Page<QrCode> search(String keyword, String city, String district,
                                QrCode.QrCodeStatus status, Pageable pageable) {
        return qrCodeRepo.search(keyword, city, district, status, pageable);
    }

    public QrCode getById(Long id) {
        return qrCodeRepo.findById(id)
            .orElseThrow(() -> new RuntimeException("活码不存在: " + id));
    }

    public List<QrAgent> getAgents(Long qrCodeId) {
        return qrAgentRepo.findByQrCodeIdOrderBySortOrder(qrCodeId);
    }

    public List<QrBackupPool> getBackups(Long qrCodeId) {
        return backupRepo.findByQrCodeIdAndStatusOrderBySortOrder(
            qrCodeId, QrBackupPool.PoolStatus.standby);
    }

    // ==================== 手动创建 ====================

    @Transactional
    public QrCode create(QrCodeCreateRequest req) {
        if (qrCodeRepo.existsBySchoolId(req.getSchoolId())) {
            throw new RuntimeException("学校ID已存在: " + req.getSchoolId());
        }

        // 1. 调用企微 API 创建「联系我」二维码（在 DB 写入之前，失败不影响事务）
        String qrRequestJson = buildContactWayJson(req);
        JsonNode result = wecomApi.createContactWay(qrRequestJson);
        int errcode = result.has("errcode") ? result.get("errcode").asInt() : 0;
        if (errcode != 0) {
            String errmsg = result.has("errmsg") ? result.get("errmsg").asText() : "未知错误";
            throw new RuntimeException("创建企微活码失败 [" + errcode + "]: " + errmsg);
        }
        String configId = result.get("config_id").asText();
        String qrUrl = result.get("qr_code").asText();

        // 2. 保存活码
        QrCode qr = QrCode.builder()
            .schoolName(req.getSchoolName())
            .schoolId(req.getSchoolId())
            .regionCity(req.getRegionCity())
            .regionDistrict(req.getRegionDistrict())
            .qrConfigId(configId)
            .qrUrl(qrUrl)
            .welcomeConfig(buildWelcomeConfig(req))
            .status(QrCode.QrCodeStatus.active)
            .rotateMode(QrCode.RotateMode.auto)
            .createMode(QrCode.CreateMode.manual)
            .remark(req.getRemark())
            .build();
        qr = qrCodeRepo.save(qr);

        // 3. 绑定员工
        bindAgents(qr.getId(), req);

        return qr;
    }

    // ==================== 批量导入 ====================

    /**
     * 异步批量导入，进度通过 Redis Hash 跟踪。
     * @return taskId
     */
    public String asyncBatchImport(MultipartFile file) {
        List<Map<String, String>> rawItems = parseExcel(file);
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        String progressKey = "batch:import:" + taskId;

        // 初始化进度
        Map<String, String> init = new LinkedHashMap<>();
        init.put("total", String.valueOf(rawItems.size()));
        init.put("success", "0");
        init.put("fail", "0");
        init.put("status", "processing");
        redisTemplate.opsForHash().putAll(progressKey, init);
        redisTemplate.expire(progressKey, 30, TimeUnit.MINUTES);

        // 异步执行
        executeBatchImport(taskId, rawItems);

        return taskId;
    }

    @Async("taskExecutor")
    public void executeBatchImport(String taskId, List<Map<String, String>> rawItems) {
        String progressKey = "batch:import:" + taskId;
        int success = 0, fail = 0;
        int total = rawItems.size();

        for (int i = 0; i < rawItems.size(); i++) {
            Map<String, String> item = rawItems.get(i);
            try {
                QrCodeCreateRequest req = new QrCodeCreateRequest();
                req.setSchoolName(item.get("schoolName"));
                req.setSchoolId(item.get("schoolId"));
                req.setRegionCity(item.get("regionCity"));
                req.setRegionDistrict(item.get("regionDistrict"));
                req.setRemark(item.getOrDefault("remark", ""));
                create(req);
                success++;
            } catch (Exception e) {
                fail++;
                // 记录失败详情
                String detailKey = progressKey + ":fail:" + fail;
                redisTemplate.opsForValue().set(detailKey,
                    item.get("row") + "|" + item.get("schoolName") + "|" + e.getMessage(),
                    30, TimeUnit.MINUTES);
            }

            // 更新进度
            Map<String, String> progress = new LinkedHashMap<>();
            progress.put("total", String.valueOf(total));
            progress.put("success", String.valueOf(success));
            progress.put("fail", String.valueOf(fail));
            progress.put("processed", String.valueOf(i + 1));
            progress.put("status", "processing");
            redisTemplate.opsForHash().putAll(progressKey, progress);
        }

        // 完成
        redisTemplate.opsForHash().put(progressKey, "status", "done");
        log.info("批量导入完成: taskId={}, total={}, success={}, fail={}", taskId, total, success, fail);
    }

    /**
     * 获取批量导入进度。
     */
    public Map<Object, Object> getBatchImportProgress(String taskId) {
        return redisTemplate.opsForHash().entries("batch:import:" + taskId);
    }

    // ==================== 删除 ====================

    @Transactional
    public void delete(Long qrCodeId) {
        QrCode qr = getById(qrCodeId);
        if (qr.getQrConfigId() != null) {
            wecomApi.deleteContactWay(qr.getQrConfigId());
        }
        qrAgentRepo.findByQrCodeId(qrCodeId).forEach(qa -> qrAgentRepo.delete(qa));
        backupRepo.findByQrCodeId(qrCodeId).forEach(bp -> backupRepo.delete(bp));
        rotateLogRepo.findByQrCodeIdOrderByCreatedAtDesc(qrCodeId, Pageable.unpaged())
            .forEach(rl -> rotateLogRepo.delete(rl));
        qrCodeRepo.delete(qr);
    }

    // ==================== 同步企微活码 ====================

    /**
     * 手动同步活码用户列表到企微 — 服务老师始终保留，接待员只上 active。
     */
    public void syncQrUsersToWechat(Long qrCodeId) {
        QrCode qr = getById(qrCodeId);
        if (qr.getQrConfigId() == null) {
            throw new RuntimeException("活码未关联企微 config_id");
        }

        List<QrAgent> allAgents = qrAgentRepo.findByQrCodeId(qrCodeId);
        Set<String> userIds = new LinkedHashSet<>();

        // 服务老师只在 active 状态时上活码（满了就暂时下码）
        for (QrAgent a : allAgents) {
            if (a.getRole() == QrAgent.AgentRole.service
                && a.getStatus() == QrAgent.AgentStatus.active) {
                userIds.add(a.getAgentUserid());
            }
        }
        // 接待员只上 active
        for (QrAgent a : allAgents) {
            if (a.getRole() != QrAgent.AgentRole.service
                && a.getStatus() == QrAgent.AgentStatus.active) {
                userIds.add(a.getAgentUserid());
            }
        }

        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("config_id", qr.getQrConfigId());
            body.put("user", new ArrayList<>(userIds));
            String json = objectMapper.writeValueAsString(body);
            wecomApi.updateContactWay(json);
            log.info("手动同步企微活码: config_id={}, users={}", qr.getQrConfigId(), userIds);
        } catch (Exception e) {
            throw new RuntimeException("同步企微活码失败: " + e.getMessage(), e);
        }
    }

    // ==================== 后备池管理 ====================

    @Transactional
    public void addBackup(Long qrCodeId, String agentUserid) {
        QrCode qr = getById(qrCodeId);

        // 检查是否已在后备池中
        List<QrBackupPool> existing = backupRepo.findByQrCodeIdAndStatusOrderBySortOrder(
            qrCodeId, QrBackupPool.PoolStatus.standby);
        boolean alreadyExists = existing.stream()
            .anyMatch(b -> b.getAgentUserid().equals(agentUserid));
        if (alreadyExists) {
            throw new RuntimeException("该员工已在后备池中");
        }

        // 确保 Agent 表中存在
        ensureAgent(agentUserid, "receptionist");

        // 确定排序号
        int maxOrder = existing.stream()
            .mapToInt(QrBackupPool::getSortOrder)
            .max().orElse(-1);

        QrBackupPool backup = QrBackupPool.builder()
            .qrCodeId(qrCodeId)
            .agentUserid(agentUserid)
            .role(QrBackupPool.PoolRole.receptionist)
            .sortOrder(maxOrder + 1)
            .status(QrBackupPool.PoolStatus.standby)
            .build();
        backupRepo.save(backup);

        log.info("后备接待员已添加: qrCodeId={}, agentUserid={}", qrCodeId, agentUserid);
    }

    // ==================== 活码联系人管理 ====================

    @Transactional
    public void addAgent(Long qrCodeId, String agentUserid) {
        getById(qrCodeId);

        // 检查是否已在联系人中（排除已移除的）
        List<QrAgent> existing = qrAgentRepo.findByQrCodeId(qrCodeId);
        boolean duplicate = existing.stream()
            .anyMatch(a -> a.getAgentUserid().equals(agentUserid)
                && a.getStatus() != QrAgent.AgentStatus.removed);
        if (duplicate) {
            throw new RuntimeException("该员工已在活码联系人中");
        }

        ensureAgent(agentUserid, "receptionist");

        int maxOrder = existing.stream()
            .mapToInt(QrAgent::getSortOrder)
            .max().orElse(-1);

        qrAgentRepo.save(QrAgent.builder()
            .qrCodeId(qrCodeId)
            .agentUserid(agentUserid)
            .role(QrAgent.AgentRole.receptionist)
            .dailyMax(200)
            .sortOrder(maxOrder + 1)
            .status(QrAgent.AgentStatus.active)
            .build());

        log.info("联系人已添加: qrCodeId={}, agentUserid={}", qrCodeId, agentUserid);
    }

    @Transactional
    public void removeAgent(Long qrCodeId, Long agentId) {
        QrAgent agent = qrAgentRepo.findById(agentId)
            .orElseThrow(() -> new RuntimeException("联系人不存在"));
        if (!agent.getQrCodeId().equals(qrCodeId)) {
            throw new RuntimeException("联系人不属于该活码");
        }
        if (agent.getRole() == QrAgent.AgentRole.service) {
            throw new RuntimeException("服务老师不能移除，请先更换服务老师");
        }
        agent.setStatus(QrAgent.AgentStatus.removed);
        qrAgentRepo.save(agent);
        log.info("联系人已移除: qrCodeId={}, agentUserid={}", qrCodeId, agent.getAgentUserid());
    }

    @Transactional
    public void updateAgent(Long qrCodeId, Long agentId,
                            Integer dailyMax, String role, Integer sortOrder) {
        QrAgent agent = qrAgentRepo.findById(agentId)
            .orElseThrow(() -> new RuntimeException("联系人不存在"));
        if (!agent.getQrCodeId().equals(qrCodeId)) {
            throw new RuntimeException("联系人不属于该活码");
        }
        if (dailyMax != null && dailyMax > 0) agent.setDailyMax(dailyMax);
        if (role != null) {
            try {
                agent.setRole(QrAgent.AgentRole.valueOf(role));
            } catch (IllegalArgumentException ignored) {}
        }
        if (sortOrder != null) agent.setSortOrder(sortOrder);
        qrAgentRepo.save(agent);
        log.info("联系人已更新: qrCodeId={}, agentId={}", qrCodeId, agentId);
    }

    // ==================== 后备池管理（续） ====================

    @Transactional
    public void removeBackup(Long qrCodeId, Long backupId) {
        QrBackupPool backup = backupRepo.findById(backupId)
            .orElseThrow(() -> new RuntimeException("后备接待员不存在"));
        if (!backup.getQrCodeId().equals(qrCodeId)) {
            throw new RuntimeException("后备接待员不属于该活码");
        }
        backupRepo.delete(backup);
        log.info("后备接待员已移除: qrCodeId={}, agentUserid={}", qrCodeId, backup.getAgentUserid());
    }

    @Transactional
    public void moveBackup(Long qrCodeId, Long backupId, String direction) {
        QrBackupPool backup = backupRepo.findById(backupId)
            .orElseThrow(() -> new RuntimeException("后备接待员不存在"));
        if (!backup.getQrCodeId().equals(qrCodeId)) {
            throw new RuntimeException("后备接待员不属于该活码");
        }

        List<QrBackupPool> all = backupRepo
            .findByQrCodeIdAndStatusOrderBySortOrder(qrCodeId, QrBackupPool.PoolStatus.standby);

        int idx = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getId().equals(backupId)) { idx = i; break; }
        }
        if (idx < 0) return;

        if ("up".equals(direction) && idx > 0) {
            QrBackupPool other = all.get(idx - 1);
            int tmp = backup.getSortOrder();
            backup.setSortOrder(other.getSortOrder());
            other.setSortOrder(tmp);
            backupRepo.save(backup);
            backupRepo.save(other);
        } else if ("down".equals(direction) && idx < all.size() - 1) {
            QrBackupPool other = all.get(idx + 1);
            int tmp = backup.getSortOrder();
            backup.setSortOrder(other.getSortOrder());
            other.setSortOrder(tmp);
            backupRepo.save(backup);
            backupRepo.save(other);
        }
    }

    // ==================== 状态更新 ====================

    @Transactional
    public void updateStatus(Long qrCodeId, QrCode.QrCodeStatus status) {
        QrCode qr = getById(qrCodeId);
        qr.setStatus(status);
        qrCodeRepo.save(qr);
    }

    @Transactional
    public void updateRotateMode(Long qrCodeId, QrCode.RotateMode mode) {
        QrCode qr = getById(qrCodeId);
        qr.setRotateMode(mode);
        qrCodeRepo.save(qr);
    }

    @Transactional
    public void updateThresholds(Long qrCodeId, int warnRatio, int urgentRatio) {
        if (warnRatio < 1 || warnRatio > 100 || urgentRatio < 1 || urgentRatio > 100) {
            throw new RuntimeException("阈值必须在 1-100 之间");
        }
        if (warnRatio >= urgentRatio) {
            throw new RuntimeException("预警阈值必须小于紧急阈值");
        }
        QrCode qr = getById(qrCodeId);
        qr.setWarnRatio(warnRatio);
        qr.setUrgentRatio(urgentRatio);
        qrCodeRepo.save(qr);
    }

    @Transactional
    public int batchUpdateRotateMode(List<Long> ids, QrCode.RotateMode mode) {
        int count = 0;
        for (Long id : ids) {
            try {
                QrCode qr = qrCodeRepo.findById(id).orElse(null);
                if (qr != null) {
                    qr.setRotateMode(mode);
                    qrCodeRepo.save(qr);
                    count++;
                }
            } catch (Exception e) {
                log.warn("批量切换轮换模式失败: id={}", id, e);
            }
        }
        return count;
    }

    @Transactional
    public void updateStyle(Long qrCodeId, String theme, String guideText,
                            Boolean showSchoolName, String logoPath) {
        QrCode qr = getById(qrCodeId);
        try {
            Map<String, Object> style = new LinkedHashMap<>();
            if (logoPath != null) style.put("logo_path", logoPath);
            style.put("theme", theme != null ? theme : "blue");
            if (guideText != null) style.put("guide_text", guideText);
            style.put("show_school_name", showSchoolName != null ? showSchoolName : true);
            qr.setStyleConfig(objectMapper.writeValueAsString(style));
            qrCodeRepo.save(qr);
        } catch (Exception e) {
            throw new RuntimeException("保存样式配置失败", e);
        }
    }

    // ==================== 内部工具 ====================

    private String buildContactWayJson(QrCodeCreateRequest req) {
        try {
            List<String> userIds = new ArrayList<>();

            // ① 服务老师（JSON 数组优先，回退逗号分隔）
            if (req.getServiceTeacherJson() != null && !req.getServiceTeacherJson().isBlank()) {
                JsonNode arr = objectMapper.readTree(req.getServiceTeacherJson());
                for (JsonNode svc : arr) {
                    if (svc.has("userid")) userIds.add(svc.get("userid").asText());
                }
            } else if (req.getServiceTeacherUserid() != null && !req.getServiceTeacherUserid().isBlank()) {
                for (String uid : req.getServiceTeacherUserid().split(",")) {
                    String trimmed = uid.trim();
                    if (!trimmed.isEmpty()) userIds.add(trimmed);
                }
            }

            // ② 如果没有服务老师，退回到接待员
            if (userIds.isEmpty()) {
                if (req.getAgentsJson() != null && !req.getAgentsJson().isBlank()) {
                    JsonNode arr = objectMapper.readTree(req.getAgentsJson());
                    for (JsonNode a : arr) {
                        if (a.has("userid")) userIds.add(a.get("userid").asText());
                    }
                } else if (req.getReceptionistUserid() != null && !req.getReceptionistUserid().isBlank()) {
                    for (String uid : req.getReceptionistUserid().split(",")) {
                        String trimmed = uid.trim();
                        if (!trimmed.isEmpty()) userIds.add(trimmed);
                    }
                }
            }

            if (userIds.isEmpty()) {
                throw new RuntimeException("请填写服务老师或接待员企微账号");
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", 2);    // 多人
            body.put("scene", 2);   // 二维码
            body.put("style", 1);
            body.put("state", req.getSchoolId());
            body.put("user", userIds);
            return objectMapper.writeValueAsString(body);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("构造活码参数失败: " + e.getMessage(), e);
        }
    }

    private String buildWelcomeConfig(QrCodeCreateRequest req) {
        try {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("text", req.getWelcomeText() != null ? req.getWelcomeText()
                : "欢迎来到XX书店家校服务！");
            if (req.getCollectFormJson() != null && !req.getCollectFormJson().isEmpty()) {
                config.put("collect_form", objectMapper.readTree(req.getCollectFormJson()));
            } else {
                // 默认收集表单
                config.put("collect_form", List.of(
                    Map.of("name", "grade", "label", "孩子年级", "type", "select",
                        "options", List.of("一年级","二年级","三年级","四年级","五年级","六年级",
                            "初一","初二","初三","高一","高二","高三")),
                    Map.of("name", "class", "label", "孩子班级", "type", "select",
                        "options", List.of("1班","2班","3班","4班","5班","6班","7班","8班","9班","10班",
                            "11班","12班","13班","14班","15班","16班","17班","18班","19班","20班")),
                    Map.of("name", "child_name", "label", "孩子姓名", "type", "text", "required", false)
                ));
            }
            config.put("form_callback_tag", true);
            config.put("transfer_greeting_enabled", true);
            config.put("transfer_filled_note",
                "{{grade}}{{class}} | 孩子：{{child_name}} | 来源：{{school_name}}");
            config.put("transfer_filled_greeting",
                "{{parent_name}}您好～我是{{school_name}}的专属服务老师{{teacher_name}}，以后孩子的学习资料和购书优惠都由我为您服务 📚");
            config.put("transfer_unfilled_greeting",
                "{{parent_name}}您好～我是{{school_name}}的{{teacher_name}}！为了给您精准推荐适合孩子的学习资料和优惠，请先花30秒填写一下孩子信息哦👇 📚 {{form_link}}");
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            return "{}";
        }
    }

    private void bindAgents(Long qrCodeId, QrCodeCreateRequest req) {
        try {
            // ① 服务老师 — 优先解析 JSON（每人独立日限），回退到逗号分隔字符串
            int defaultSvcDailyMax = req.getServiceDailyMax() != null ? req.getServiceDailyMax() : 1000;
            int sortOrder = 0;

            if (req.getServiceTeacherJson() != null && !req.getServiceTeacherJson().isBlank()) {
                // JSON 格式: [{"userid":"xx","dailyMax":500}, ...]
                JsonNode arr = objectMapper.readTree(req.getServiceTeacherJson());
                for (JsonNode svc : arr) {
                    String uid = svc.get("userid").asText();
                    int dm = svc.has("dailyMax") ? svc.get("dailyMax").asInt() : defaultSvcDailyMax;
                    ensureAgent(uid, "service");
                    qrAgentRepo.save(QrAgent.builder()
                        .qrCodeId(qrCodeId).agentUserid(uid)
                        .role(QrAgent.AgentRole.service)
                        .dailyMax(dm).serviceDailyMax(dm)
                        .sortOrder(sortOrder++).status(QrAgent.AgentStatus.active)
                        .build());
                }
            } else if (req.getServiceTeacherUserid() != null && !req.getServiceTeacherUserid().isBlank()) {
                for (String uid : req.getServiceTeacherUserid().split(",")) {
                    String trimmed = uid.trim();
                    if (trimmed.isEmpty()) continue;
                    ensureAgent(trimmed, "service");
                    qrAgentRepo.save(QrAgent.builder()
                        .qrCodeId(qrCodeId).agentUserid(trimmed)
                        .role(QrAgent.AgentRole.service)
                        .dailyMax(defaultSvcDailyMax).serviceDailyMax(defaultSvcDailyMax)
                        .sortOrder(sortOrder++).status(QrAgent.AgentStatus.active)
                        .build());
                }
            }

            // ② 接待员 — 优先解析 JSON（每人独立日限），回退到逗号分隔字符串
            int order = 0;
            if (req.getAgentsJson() != null && !req.getAgentsJson().isBlank()) {
                // JSON 格式: [{"userid":"xx","dailyMax":150}, ...]
                JsonNode arr = objectMapper.readTree(req.getAgentsJson());
                for (JsonNode a : arr) {
                    String uid = a.get("userid").asText();
                    int dm = a.has("dailyMax") ? a.get("dailyMax").asInt() : 200;
                    ensureAgent(uid, "receptionist");
                    backupRepo.save(QrBackupPool.builder()
                        .qrCodeId(qrCodeId).agentUserid(uid)
                        .role(QrBackupPool.PoolRole.receptionist)
                        .dailyMax(dm).sortOrder(order++).status(QrBackupPool.PoolStatus.standby)
                        .build());
                }
            } else if (req.getReceptionistUserid() != null && !req.getReceptionistUserid().isBlank()) {
                for (String uid : req.getReceptionistUserid().split(",")) {
                    String trimmed = uid.trim();
                    if (trimmed.isEmpty()) continue;
                    ensureAgent(trimmed, "receptionist");
                    backupRepo.save(QrBackupPool.builder()
                        .qrCodeId(qrCodeId).agentUserid(trimmed)
                        .role(QrBackupPool.PoolRole.receptionist)
                        .dailyMax(200).sortOrder(order++).status(QrBackupPool.PoolStatus.standby)
                        .build());
                }
            }

            // ③ 额外后备员工（纯 userid 数组，日限默认 200）
            if (req.getBackupsJson() != null && !req.getBackupsJson().isEmpty()) {
                JsonNode backups = objectMapper.readTree(req.getBackupsJson());
                for (JsonNode b : backups) {
                    String uid = b.isObject() ? b.get("userid").asText() : b.asText();
                    int dm = b.isObject() && b.has("dailyMax") ? b.get("dailyMax").asInt() : 200;
                    ensureAgent(uid, "receptionist");
                    backupRepo.save(QrBackupPool.builder()
                        .qrCodeId(qrCodeId).agentUserid(uid)
                        .role(QrBackupPool.PoolRole.receptionist)
                        .dailyMax(dm).sortOrder(order++).status(QrBackupPool.PoolStatus.standby)
                        .build());
                }
            }
        } catch (Exception e) {
            log.error("绑定员工失败", e);
            throw new RuntimeException("绑定员工失败: " + e.getMessage(), e);
        }
    }

    private void ensureAgent(String userid, String role) {
        if (!agentRepo.existsById(userid)) {
            // 尝试从企微 API 获取真实姓名
            String name = userid; // fallback
            try {
                JsonNode result = wecomApi.getUserSimplelist();
                if (!result.has("errcode") || result.get("errcode").asInt() == 0) {
                    for (JsonNode u : result.get("userlist")) {
                        if (userid.equals(u.get("userid").asText())) {
                            name = u.get("name").asText();
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("获取员工姓名失败, 使用 userid 作为 name: userid={}", userid);
            }

            agentRepo.save(Agent.builder()
                .userid(userid)
                .name(name)
                .role(Agent.AgentRole.valueOf(role))
                .dailyTotalCap(500)
                .build());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseExcel(MultipartFile file) {
        List<Map<String, String>> items = new ArrayList<>();
        try (InputStream is = file.getInputStream();
             Workbook wb = new XSSFWorkbook(is)) {
            Sheet sheet = wb.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                Map<String, String> item = new LinkedHashMap<>();
                item.put("row", String.valueOf(i + 1));
                item.put("schoolName", getCellString(row, 0));
                item.put("schoolId", getCellString(row, 1));
                item.put("regionCity", getCellString(row, 2));
                item.put("regionDistrict", getCellString(row, 3));
                item.put("remark", getCellString(row, 4));
                if (!item.get("schoolName").isEmpty() && !item.get("schoolId").isEmpty()) {
                    items.add(item);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("解析 Excel 失败: " + e.getMessage(), e);
        }
        return items;
    }

    private String getCellString(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return "";
        cell.setCellType(CellType.STRING);
        return cell.getStringCellValue().trim();
    }
}

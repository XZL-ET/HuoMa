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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class QrCodeService {

    private final QrCodeRepository qrCodeRepo;
    private final QrAgentRepository qrAgentRepo;
    private final QrBackupPoolRepository backupRepo;
    private final AgentRepository agentRepo;
    private final WecomApiClient wecomApi;
    private final ObjectMapper objectMapper;

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

    @Transactional
    public Map<String, Object> batchImport(MultipartFile file) {
        List<Map<String, String>> rawItems = parseExcel(file);
        List<Map<String, Object>> results = new ArrayList<>();
        int success = 0, fail = 0;

        for (Map<String, String> item : rawItems) {
            try {
                QrCodeCreateRequest req = new QrCodeCreateRequest();
                req.setSchoolName(item.get("schoolName"));
                req.setSchoolId(item.get("schoolId"));
                req.setRegionCity(item.get("regionCity"));
                req.setRegionDistrict(item.get("regionDistrict"));
                req.setRemark(item.getOrDefault("remark", ""));
                create(req);
                results.add(Map.of("row", item.get("row"), "status", "ok",
                    "school", item.get("schoolName")));
                success++;
            } catch (Exception e) {
                results.add(Map.of("row", item.get("row"), "status", "fail",
                    "school", item.get("schoolName"), "reason", e.getMessage()));
                fail++;
            }
        }
        return Map.of("total", rawItems.size(), "success", success,
            "fail", fail, "details", results);
    }

    // ==================== 删除 ====================

    @Transactional
    public void delete(Long qrCodeId) {
        QrCode qr = getById(qrCodeId);
        if (qr.getQrConfigId() != null) {
            wecomApi.deleteContactWay(qr.getQrConfigId());
        }
        qrAgentRepo.findByQrCodeId(qrCodeId).forEach(qa -> qrAgentRepo.delete(qa));
        backupRepo.findByQrCodeIdAndStatusOrderBySortOrder(qrCodeId,
            QrBackupPool.PoolStatus.standby).forEach(bp -> backupRepo.delete(bp));
        qrCodeRepo.delete(qr);
    }

    // ==================== 同步企微活码 ====================

    /**
     * 手动同步活码用户列表到企微 — 只把 active 员工放上活码。
     */
    public void syncQrUsersToWechat(Long qrCodeId) {
        QrCode qr = getById(qrCodeId);
        if (qr.getQrConfigId() == null) {
            throw new RuntimeException("活码未关联企微 config_id");
        }

        List<QrAgent> activeAgents = qrAgentRepo.findByQrCodeIdAndStatus(
            qrCodeId, QrAgent.AgentStatus.active);
        List<String> userIds = activeAgents.stream()
            .map(QrAgent::getAgentUserid)
            .distinct()
            .toList();

        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("config_id", qr.getQrConfigId());
            body.put("user", userIds);
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

    // ==================== 内部工具 ====================

    private String buildContactWayJson(QrCodeCreateRequest req) {
        try {
            // 提取服务老师 userid（活码主联系人，优先放活码上）
            String svcUserid = null;
            if (req.getServiceTeacherUserid() != null && !req.getServiceTeacherUserid().isBlank()) {
                svcUserid = req.getServiceTeacherUserid().trim();
            }
            if (svcUserid == null && req.getServiceTeacherJson() != null && !req.getServiceTeacherJson().isBlank()) {
                JsonNode svc = objectMapper.readTree(req.getServiceTeacherJson());
                if (svc.has("userid")) svcUserid = svc.get("userid").asText();
            }

            // 如果没填服务老师，退回到接待员
            List<String> userIds = new ArrayList<>();
            if (svcUserid != null) {
                userIds.add(svcUserid);
            } else {
                // 兼容旧逻辑：从 receptionistUserid 或 agentsJson 提取
                if (req.getReceptionistUserid() != null && !req.getReceptionistUserid().isBlank()) {
                    for (String uid : req.getReceptionistUserid().split(",")) {
                        String trimmed = uid.trim();
                        if (!trimmed.isEmpty()) userIds.add(trimmed);
                    }
                }
                if (userIds.isEmpty() && req.getAgentsJson() != null && !req.getAgentsJson().isBlank()) {
                    JsonNode agents = objectMapper.readTree(req.getAgentsJson());
                    for (JsonNode agent : agents) {
                        if (agent.has("userid")) userIds.add(agent.get("userid").asText());
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
            // ① 服务老师 — 活码主联系人（QrAgent, 角色=service）
            String svcUserid = null;
            int svcDailyMax = 1000;
            if (req.getServiceTeacherUserid() != null && !req.getServiceTeacherUserid().isBlank()) {
                svcUserid = req.getServiceTeacherUserid().trim();
                svcDailyMax = req.getServiceDailyMax() != null ? req.getServiceDailyMax() : 1000;
            }
            if (svcUserid == null && req.getServiceTeacherJson() != null && !req.getServiceTeacherJson().isBlank()) {
                JsonNode svc = objectMapper.readTree(req.getServiceTeacherJson());
                svcUserid = svc.get("userid").asText();
                svcDailyMax = svc.has("serviceDailyMax") ? svc.get("serviceDailyMax").asInt() : 1000;
            }
            if (svcUserid != null) {
                ensureAgent(svcUserid, "service");
                qrAgentRepo.save(QrAgent.builder()
                    .qrCodeId(qrCodeId)
                    .agentUserid(svcUserid)
                    .role(QrAgent.AgentRole.service)
                    .dailyMax(svcDailyMax)
                    .serviceDailyMax(svcDailyMax)
                    .sortOrder(0)
                    .status(QrAgent.AgentStatus.active)
                    .build());
            }

            // ② 接待员 — 进后备池（不是直接上活码，等服务老师满了才激活）
            List<String> receptionistUserids = new ArrayList<>();
            if (req.getReceptionistUserid() != null && !req.getReceptionistUserid().isBlank()) {
                for (String uid : req.getReceptionistUserid().split(",")) {
                    String trimmed = uid.trim();
                    if (!trimmed.isEmpty()) receptionistUserids.add(trimmed);
                }
            }
            if (receptionistUserids.isEmpty() && req.getAgentsJson() != null && !req.getAgentsJson().isBlank()) {
                JsonNode agents = objectMapper.readTree(req.getAgentsJson());
                for (JsonNode a : agents) {
                    receptionistUserids.add(a.get("userid").asText());
                }
            }

            int order = 0;
            for (String userid : receptionistUserids) {
                ensureAgent(userid, "receptionist");
                backupRepo.save(QrBackupPool.builder()
                    .qrCodeId(qrCodeId)
                    .agentUserid(userid)
                    .role(QrBackupPool.PoolRole.receptionist)
                    .sortOrder(order++)
                    .status(QrBackupPool.PoolStatus.standby)
                    .build());
            }

            // ③ 额外后备员工
            if (req.getBackupsJson() != null && !req.getBackupsJson().isEmpty()) {
                JsonNode backups = objectMapper.readTree(req.getBackupsJson());
                for (JsonNode b : backups) {
                    String userid = b.asText();
                    ensureAgent(userid, "receptionist");
                    backupRepo.save(QrBackupPool.builder()
                        .qrCodeId(qrCodeId)
                        .agentUserid(userid)
                        .role(QrBackupPool.PoolRole.receptionist)
                        .sortOrder(order++)
                        .status(QrBackupPool.PoolStatus.standby)
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
            agentRepo.save(Agent.builder()
                .userid(userid)
                .name(userid)
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

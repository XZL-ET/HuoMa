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

        // 1. 调用企微 API 创建「联系我」二维码
        String qrRequestJson = buildContactWayJson(req);
        JsonNode result = wecomApi.createContactWay(qrRequestJson);
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
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", 2);    // 多人
            body.put("scene", 2);   // 二维码
            body.put("style", 1);
            body.put("state", req.getSchoolId());
            body.put("user", List.of()); // 先空
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("构造活码参数失败", e);
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
            if (req.getAgentsJson() != null && !req.getAgentsJson().isEmpty()) {
                JsonNode agents = objectMapper.readTree(req.getAgentsJson());
                int order = 0;
                for (JsonNode a : agents) {
                    String userid = a.get("userid").asText();
                    ensureAgent(userid, "receptionist");
                    int dailyMax = a.has("dailyMax") ? a.get("dailyMax").asInt() : 200;
                    qrAgentRepo.save(QrAgent.builder()
                        .qrCodeId(qrCodeId)
                        .agentUserid(userid)
                        .role(QrAgent.AgentRole.receptionist)
                        .dailyMax(dailyMax)
                        .sortOrder(order++)
                        .status(QrAgent.AgentStatus.active)
                        .build());
                }
            }

            if (req.getServiceTeacherJson() != null && !req.getServiceTeacherJson().isEmpty()) {
                JsonNode svc = objectMapper.readTree(req.getServiceTeacherJson());
                String userid = svc.get("userid").asText();
                ensureAgent(userid, "service");
                int svcMax = svc.has("serviceDailyMax") ? svc.get("serviceDailyMax").asInt() : 1000;
                qrAgentRepo.save(QrAgent.builder()
                    .qrCodeId(qrCodeId)
                    .agentUserid(userid)
                    .role(QrAgent.AgentRole.service)
                    .serviceDailyMax(svcMax)
                    .status(QrAgent.AgentStatus.active)
                    .build());
            }

            if (req.getBackupsJson() != null && !req.getBackupsJson().isEmpty()) {
                JsonNode backups = objectMapper.readTree(req.getBackupsJson());
                int order = 0;
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
            log.warn("绑定员工失败: {}", e.getMessage());
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

package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.repository.SchoolCategoryRepository;
import com.bookstore.qrcode.repository.SchoolRepository;
import com.bookstore.qrcode.config.RedisConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@Slf4j
@Controller
@RequiredArgsConstructor
public class FormController {

    private final QrCodeRepository qrCodeRepo;
    private final FormTemplateRepository formTemplateRepo;
    private final FormSubmissionRepository submissionRepo;
    private final CustomerRepository customerRepo;
    private final QrCodeGroupRepository groupRepo;
    private final SchoolCategoryRepository categoryRepo;
    private final SchoolRepository schoolRepo;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @GetMapping("/form/{qrCodeId}")
    public String fillForm(@PathVariable Long qrCodeId,
                           @RequestParam(required = false) Long c,
                           Model model) {
        QrCode qr = qrCodeRepo.findById(qrCodeId).orElse(null);
        if (qr == null) return "form/success";

        // 表单模板：活码 → 分组 → 学校分类（独立继承）
        Long formTemplateId = qr.getFormTemplateId();
        if (formTemplateId == null && qr.getGroupId() != null) {
            QrCodeGroup group = groupRepo.findById(qr.getGroupId()).orElse(null);
            if (group != null) formTemplateId = group.getDefaultFormTemplateId();
        }
        // 新增：学校分类层
        if (formTemplateId == null && qr.getSchoolId() != null) {
            School school = schoolRepo.findBySchoolIdAndDeletedFalse(qr.getSchoolId()).orElse(null);
            if (school != null && school.getCategoryId() != null) {
                SchoolCategory cat = categoryRepo.findById(school.getCategoryId()).orElse(null);
                if (cat != null) formTemplateId = cat.getDefaultFormTemplateId();
            }
        }
        if (formTemplateId == null) return "form/success";

        FormTemplate tpl = formTemplateRepo.findById(formTemplateId).orElse(null);
        if (tpl == null) return "form/success";

        model.addAttribute("qrCodeId", qrCodeId);
        model.addAttribute("customerId", c);
        model.addAttribute("schoolName", qr.getSchoolName());
        model.addAttribute("subtitle", tpl.getSubtitle());  // null 时模板用默认文案
        model.addAttribute("fieldsJson", tpl.getFields());

        // 区域联盟场景：加载分组学校列表，表单页展示学校选择下拉框
        if (qr.getGroupId() != null) {
            QrCodeGroup group = groupRepo.findById(qr.getGroupId()).orElse(null);
            if (group != null && group.getSchoolList() != null && !group.getSchoolList().isBlank()) {
                List<String> schools = new ArrayList<>();
                for (String line : group.getSchoolList().split("\\n")) {
                    String s = line.strip();
                    if (!s.isEmpty()) schools.add(s);
                }
                model.addAttribute("schoolList", schools);
                model.addAttribute("groupName", group.getName());
            }
        }

        return "form/fill";
    }

    @PostMapping("/api/form/submit")
    @ResponseBody
    public Map<String, Object> submit(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Long qrCodeId = body.get("qrCodeId") != null
                ? Long.valueOf(body.get("qrCodeId").toString()) : null;
            Long customerId = body.get("customerId") != null
                ? Long.valueOf(body.get("customerId").toString()) : null;

            if (qrCodeId == null || customerId == null) {
                result.put("success", false); result.put("error", "缺少必要参数"); return result;
            }

            QrCode qr = qrCodeRepo.findById(qrCodeId).orElse(null);
            String schoolName = body.get("schoolName") != null
                && !body.get("schoolName").toString().isBlank()
                ? body.get("schoolName").toString()
                : (qr != null ? qr.getSchoolName() : null);  // 非联盟自动取活码学校名
            String fieldData = objectMapper.writeValueAsString(
                body.getOrDefault("fieldData", Map.of()));
            // 表单模板：活码 → 分组 → 学校分类（独立继承）
            Long formTemplateId = (qr != null) ? qr.getFormTemplateId() : null;
            if (formTemplateId == null && qr != null && qr.getGroupId() != null) {
                QrCodeGroup group = groupRepo.findById(qr.getGroupId()).orElse(null);
                if (group != null) formTemplateId = group.getDefaultFormTemplateId();
            }
            // 新增：学校分类层
            if (formTemplateId == null && qr != null && qr.getSchoolId() != null) {
                School school = schoolRepo.findBySchoolIdAndDeletedFalse(qr.getSchoolId()).orElse(null);
                if (school != null && school.getCategoryId() != null) {
                    SchoolCategory cat = categoryRepo.findById(school.getCategoryId()).orElse(null);
                    if (cat != null) formTemplateId = cat.getDefaultFormTemplateId();
                }
            }
            if (qr == null || formTemplateId == null) {
                result.put("success", false); result.put("error", "活码未配置表单"); return result;
            }

            // 防重复提交：同一客户+活码只保留首次提交
            if (submissionRepo.existsByCustomerIdAndQrCodeId(customerId, qrCodeId)) {
                result.put("success", false); result.put("error", "您已提交过该表单，无需重复提交"); return result;
            }

            FormSubmission sub = FormSubmission.builder()
                .formTemplateId(formTemplateId)
                .customerId(customerId).qrCodeId(qrCodeId)
                .schoolName(schoolName)
                .fieldData(fieldData).build();
            sub = submissionRepo.save(sub);

            Customer customer = customerRepo.findById(customerId).orElse(null);
            if (customer != null && customer.getCurrentAgent() != null) {
                Map<String, Object> tagEvent = new LinkedHashMap<>();
                tagEvent.put("type", "form_submit");
                tagEvent.put("external_userid", customer.getExternalUserid());
                tagEvent.put("userid", customer.getCurrentAgent());
                tagEvent.put("form_template_id", formTemplateId.toString());
                tagEvent.put("submission_id", sub.getId().toString());
                tagEvent.put("field_data", fieldData);
                if (schoolName != null && !schoolName.isBlank()) {
                    tagEvent.put("school_name", schoolName);
                }
                redisTemplate.opsForStream().add(
                    RedisConfig.TAG_STREAM_KEY,
                    Map.of("event", objectMapper.writeValueAsString(tagEvent)));
            }

            result.put("success", true);
        } catch (Exception e) {
            log.error("表单提交失败", e);
            result.put("success", false); result.put("error", e.getMessage());
        }
        return result;
    }

    @GetMapping("/form/success")
    public String success() { return "form/success"; }
}

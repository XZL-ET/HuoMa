package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.service.FormTemplateService;
import com.bookstore.qrcode.service.PaperLinkService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 补发表单控制器（独立于 {@link FormController}，零耦合，不动既有表单业务）。
 * <p>
 * 入口 {@code GET /form/resend}：群发链接不带个人 customerId，正式流程靠公众号
 * 网页授权（snsapi_base）拿 openid 反查身份（明日接入）。当前支持：
 * <ul>
 *   <li>{@code ?c={customerId}} 直接渲染（用于今日测试与调试）</li>
 *   <li>无 {@code c} 时渲染「待接入公众号授权」兜底页</li>
 * </ul>
 * 提交 {@code POST /api/form/resend-submit}：复用现有 form_submit 打标链路，
 * 并按（年级 × 科目）返回试题链接列表。
 * </p>
 *
 * @author Bookstore Dev
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class FormResendController {

    private final CustomerRepository customerRepo;
    private final FormTemplateRepository formTemplateRepo;
    private final FormSubmissionRepository submissionRepo;
    private final QrCodeRepository qrCodeRepo;
    private final SystemConfigRepository systemConfigRepo;
    private final FormTemplateService formTemplateService;
    private final PaperLinkService paperLinkService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @GetMapping("/form/resend")
    public String fillResend(@RequestParam(required = false) Long c, Model model) {
        // 明日：无 c 时走公众号网页授权反查。今日先渲染兜底提示页。
        if (c == null) {
            return "form/resend-fallback";
        }
        Customer customer = customerRepo.findById(c).orElse(null);
        if (customer == null) return "form/resend-fallback";

        Long templateId = resolveResendTemplateId();
        FormTemplate tpl = templateId != null ? formTemplateRepo.findById(templateId).orElse(null) : null;
        if (tpl == null) return "form/resend-fallback";

        String schoolName = null;
        if (customer.getSourceQrId() != null) {
            QrCode qr = qrCodeRepo.findById(customer.getSourceQrId()).orElse(null);
            if (qr != null) schoolName = qr.getSchoolName();
        }

        model.addAttribute("customerId", c);
        model.addAttribute("schoolName", schoolName);
        model.addAttribute("subtitle", tpl.getSubtitle());
        model.addAttribute("fieldsJson", tpl.getFields());
        return "form/fill-resend";
    }

    @PostMapping("/api/form/resend-submit")
    @ResponseBody
    public Map<String, Object> submit(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Long customerId = body.get("customerId") != null
                ? Long.valueOf(body.get("customerId").toString()) : null;
            if (customerId == null) {
                result.put("success", false); result.put("error", "缺少必要参数"); return result;
            }

            Long templateId = resolveResendTemplateId();
            if (templateId == null) {
                result.put("success", false); result.put("error", "补发表单模板未配置"); return result;
            }

            Customer customer = customerRepo.findById(customerId).orElse(null);
            if (customer == null) {
                result.put("success", false); result.put("error", "客户不存在"); return result;
            }

            String fieldData = objectMapper.writeValueAsString(
                body.getOrDefault("fieldData", Map.of()));
            String schoolName = body.get("schoolName") != null
                && !body.get("schoolName").toString().isBlank()
                ? body.get("schoolName").toString() : null;

            if (submissionRepo.existsByCustomerIdAndFormTemplateId(customerId, templateId)) {
                result.put("success", false); result.put("error", "您已提交过，无需重复提交"); return result;
            }

            FormSubmission sub = FormSubmission.builder()
                .formTemplateId(templateId)
                .customerId(customerId)
                .schoolName(schoolName)
                .fieldData(fieldData)
                .build();
            sub = submissionRepo.save(sub);

            // 复用现有 form_submit 打标链路（年级 + 科目都会打标签）
            if (customer.getCurrentAgent() != null) {
                Map<String, Object> tagEvent = new LinkedHashMap<>();
                tagEvent.put("type", "form_submit");
                tagEvent.put("external_userid", customer.getExternalUserid());
                tagEvent.put("userid", customer.getCurrentAgent());
                tagEvent.put("form_template_id", templateId.toString());
                tagEvent.put("submission_id", sub.getId().toString());
                tagEvent.put("field_data", fieldData);
                if (schoolName != null && !schoolName.isBlank()) {
                    tagEvent.put("school_name", schoolName);
                }
                redisTemplate.opsForStream().add(
                    RedisConfig.TAG_STREAM_KEY,
                    Map.of("event", objectMapper.writeValueAsString(tagEvent)));
            }

            // 解析年级 + 科目，查「年级 × 科目」试题链接
            JsonNode fd = objectMapper.readTree(fieldData);
            String grade = fd.has("grade") && !fd.get("grade").isNull()
                ? fd.get("grade").asText() : null;
            List<String> subjects = new ArrayList<>();
            JsonNode subjectNode = fd.has("subject") ? fd.get("subject") : null;
            if (subjectNode != null && subjectNode.isArray()) {
                subjectNode.forEach(s -> subjects.add(s.asText()));
            } else if (subjectNode != null && !subjectNode.isNull()) {
                subjects.add(subjectNode.asText());
            }

            List<Map<String, Object>> linkList = new ArrayList<>();
            for (PaperLink pl : paperLinkService.findLinks(grade, subjects)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("subject", pl.getSubject());
                m.put("title", pl.getTitle());
                m.put("url", pl.getPaperUrl());
                linkList.add(m);
            }

            result.put("success", true);
            result.put("links", linkList);
        } catch (Exception e) {
            log.error("补发表单提交失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    private Long resolveResendTemplateId() {
        SystemConfig cfg = systemConfigRepo.findById("resend_form_template_id").orElse(null);
        String val = cfg != null ? cfg.getConfigValue() : null;
        if (val != null && !val.isBlank()) {
            try { return Long.valueOf(val.trim()); } catch (NumberFormatException ignored) {}
        }
        // 兜底：默认补发表单模板
        return formTemplateService.ensureResendTemplate().getId();
    }
}

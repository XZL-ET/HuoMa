package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
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
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @GetMapping("/form/{qrCodeId}")
    public String fillForm(@PathVariable Long qrCodeId,
                           @RequestParam(required = false) Long c,
                           Model model) {
        QrCode qr = qrCodeRepo.findById(qrCodeId).orElse(null);
        if (qr == null || qr.getFormTemplateId() == null) {
            return "form/success";
        }
        FormTemplate tpl = formTemplateRepo.findById(qr.getFormTemplateId()).orElse(null);
        if (tpl == null) return "form/success";

        model.addAttribute("qrCodeId", qrCodeId);
        model.addAttribute("customerId", c);
        model.addAttribute("schoolName", qr.getSchoolName());
        model.addAttribute("fieldsJson", tpl.getFields());
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
            String fieldData = objectMapper.writeValueAsString(
                body.getOrDefault("fieldData", Map.of()));

            if (qrCodeId == null || customerId == null) {
                result.put("success", false); result.put("error", "缺少必要参数"); return result;
            }

            QrCode qr = qrCodeRepo.findById(qrCodeId).orElse(null);
            if (qr == null || qr.getFormTemplateId() == null) {
                result.put("success", false); result.put("error", "活码未配置表单"); return result;
            }

            FormSubmission sub = FormSubmission.builder()
                .formTemplateId(qr.getFormTemplateId())
                .customerId(customerId).qrCodeId(qrCodeId)
                .fieldData(fieldData).build();
            sub = submissionRepo.save(sub);

            Customer customer = customerRepo.findById(customerId).orElse(null);
            if (customer != null && customer.getCurrentAgent() != null) {
                Map<String, Object> tagEvent = new LinkedHashMap<>();
                tagEvent.put("type", "form_submit");
                tagEvent.put("external_userid", customer.getExternalUserid());
                tagEvent.put("userid", customer.getCurrentAgent());
                tagEvent.put("form_template_id", qr.getFormTemplateId().toString());
                tagEvent.put("submission_id", sub.getId().toString());
                tagEvent.put("field_data", fieldData);
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

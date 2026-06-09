package com.bookstore.qrcode.wecom;

import com.bookstore.qrcode.config.RedisConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 企微回调接收入口。
 * 极速处理：验签 → 解密 → XADD Redis Stream → 秒回 200。
 */
@Slf4j
@RestController
@RequestMapping("/api/wecom/callback")
@RequiredArgsConstructor
public class WecomCallbackController {

    private final WecomCallbackValidator validator;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * GET：企微回调 URL 验证。
     */
    @GetMapping
    public String verify(@RequestParam("msg_signature") String msgSignature,
                         @RequestParam("timestamp") String timestamp,
                         @RequestParam("nonce") String nonce,
                         @RequestParam("echostr") String echostr) {
        log.info("回调URL验证请求: timestamp={}, nonce={}", timestamp, nonce);
        try {
            String decryptedEchoStr = validator.verify(msgSignature, timestamp, nonce, echostr);
            log.info("回调URL验证成功");
            return decryptedEchoStr;
        } catch (Exception e) {
            log.error("回调URL验证失败", e);
            return "verify failed";
        }
    }

    /**
     * POST：接收企微回调事件。
     * 流程：解密 → 检出事件类型 → XADD Redis Stream → 秒回 200。
     * 不做任何查库操作，确保 < 20ms 响应。
     */
    @PostMapping
    public String receive(@RequestParam("msg_signature") String msgSignature,
                          @RequestParam("timestamp") String timestamp,
                          @RequestParam("nonce") String nonce,
                          HttpServletRequest request) {
        try {
            // 1. 读取请求体
            String body = new String(request.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);

            // 2. 解密
            String decryptedXml = validator.decryptMessage(msgSignature, timestamp, nonce, body);

            // 3. 快速提取事件类型和关键字段
            String eventType = extractXmlTag(decryptedXml, "Event");
            String changeType = extractXmlTag(decryptedXml, "ChangeType");
            String externalUserId = extractXmlTag(decryptedXml, "ExternalUserID");
            String userId = extractXmlTag(decryptedXml, "UserID");
            String state = extractXmlTag(decryptedXml, "State");
            String failReason = extractXmlTag(decryptedXml, "FailReason");
            String source = extractXmlTag(decryptedXml, "Source");

            // 4. 构造事件 JSON
            Map<String, Object> event = new HashMap<>();
            event.put("event_type", eventType != null ? eventType : changeType);
            event.put("external_userid", externalUserId);
            event.put("userid", userId);
            event.put("state", state);
            event.put("fail_reason", failReason);
            event.put("source", source);
            event.put("timestamp", Instant.now().toString());
            event.put("raw_xml", decryptedXml);

            String eventJson = objectMapper.writeValueAsString(event);

            // 5. XADD Redis Stream
            redisTemplate.opsForStream().add(
                RedisConfig.CALLBACK_STREAM_KEY,
                Map.of("event", eventJson));

            log.debug("回调已入队: type={}, userid={}, external={}",
                eventType, userId, externalUserId);

            // 6. 秒回 200（不查库、不阻塞）
            return "success";

        } catch (Exception e) {
            log.error("回调处理异常", e);
            // 即使异常也返回 success，避免企微不断重试
            return "success";
        }
    }

    private String extractXmlTag(String xml, String tag) {
        if (xml == null) return null;
        // CDATA 包裹优先（企微回调均使用 CDATA）
        String startTag = "<" + tag + "><![CDATA[";
        String endTag = "]]></" + tag + ">";
        int start = xml.indexOf(startTag);
        int end = xml.indexOf(endTag);
        if (start >= 0 && end > start) {
            return xml.substring(start + startTag.length(), end);
        }
        // 标准格式
        startTag = "<" + tag + ">";
        endTag = "</" + tag + ">";
        start = xml.indexOf(startTag);
        end = xml.indexOf(endTag);
        if (start >= 0 && end > start) {
            String content = xml.substring(start + startTag.length(), end);
            if (content.startsWith("<![CDATA[")) {
                content = content.substring(9, content.length() - 3);
            }
            return content;
        }
        return null;
    }
}

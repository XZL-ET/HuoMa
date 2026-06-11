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
 * 企业微信回调事件接收入口（Webhook）。
 * <p>
 * <b>设计模式：快速确认（Fast Ack）</b><br>
 * 此接口遵循「先响应、后处理」的异步模式，确保 HTTP 响应时间极短（< 20ms），
 * 避免企微服务器因超时而重复推送回调。
 * <p>
 * <b>处理流水线：</b>
 * <pre>
 * ┌─────────────────────────────────────────────────────┐
 * │                     企微服务器                        │
 * │  ┌───────────────────────────────────────────────┐  │
 * │  │ GET  /api/wecom/callback?msg_signature=...    │  │  ← URL 验证
 * │  │ POST /api/wecom/callback  (application/xml)   │  │  ← 事件推送
 * │  └───────────────────────┬───────────────────────┘  │
 * └──────────────────────────┼──────────────────────────┘
 *                            │
 *                            ▼
 * ┌──────────────────────────────────────────────────────┐
 * │               WecomCallbackController                 │
 * │                                                       │
 * │  Step 1: 签名校验 (SHA-1)  ← WecomCallbackValidator  │
 * │  Step 2: AES-256-CBC 解密  ← WecomCallbackValidator  │
 * │  Step 3: 提取事件关键字段 (Event/ChangeType/UserId…)  │
 * │  Step 4: 构造事件 JSON                                │
 * │  Step 5: XADD → Redis Stream (CALLBACK_STREAM_KEY)   │
 * │  Step 6: HTTP 200 "success"  ← 快速确认               │
 * └──────────────────────┬───────────────────────────────┘
 *                        │
 *                        ▼
 * ┌──────────────────────────────────────────────────────┐
 * │         Redis Stream Consumer（后台异步消费）           │
 * │  - 处理客户添加事件（change_external_contact）         │
 * │  - 处理客户删除事件                                  │
 * │  - 处理标签变更、继承结果等                          │
 * │  XREADGROUP → 业务逻辑 → ACK                        │
 * └──────────────────────────────────────────────────────┘
 * </pre>
 * <p>
 * <b>为什么使用 Redis Stream 而不是消息队列：</b><br>
 * ① 无需引入额外的消息中间件，降低运维成本<br>
 * ② Redis Stream 的 Consumer Group 机制天然支持消费确认和重试<br>
 * ③ 蓝绿部署时 Stream 数据不丢失，新实例上线自动继续消费<br>
 * <p>
 * <b>异常兜底：</b><br>
 * 即使处理过程中出现任何异常，也返回 HTTP 200（"success"），
 * 避免企微认为回调推送失败而不断重试（企微重试机制：间隔逐步增大，最多 24 小时）。
 *
 * @author bookstore
 * @since 1.0.0
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
     * 【GET】企业微信回调 URL 验证接口。
     * <p>
     * 当管理员在企业微信后台配置「回调 URL」时，企微服务器会发送一次 GET 请求
     * 以验证 URL 的合法性。本接口需要完成：
     * <ol>
     *   <li>SHA-1 签名校验（确保请求来自企微官方）</li>
     *   <li>AES 解密 echostr，返回明文给企微</li>
     * </ol>
     * 如果返回值与企微期望一致，URL 配置成功。
     * <p>
     * <b>请求参数（全部由企微服务器自动填充）：</b>
     * <ul>
     *   <li>msg_signature — SHA-1 签名，用于校验请求合法性</li>
     *   <li>timestamp — 时间戳</li>
     *   <li>nonce — 随机数</li>
     *   <li>echostr — 加密字符串，需解密后原样返回</li>
     * </ul>
     *
     * @param msgSignature 企微签发的消息体签名（URL 参数: msg_signature）
     * @param timestamp    企微请求的时间戳（URL 参数: timestamp）
     * @param nonce        随机字符串（URL 参数: nonce）
     * @param echostr      加密的 echostr 字符串（URL 参数: echostr），
     *                     需要 AES 解密后原样返回给企微
     * @return 解密后的 echostr 明文；验证失败返回 "verify failed"
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
     * 【POST】企业微信回调事件推送接口。
     * <p>
     * 当企微发生客户变更、标签变更等事件时，企微服务器会 POST XML 消息到此接口。
     * 本接口遵循 <b>快速确认（Fast Ack）</b>模式：
     * <pre>
     * 请求进入
     *   ├─ 1. 读取 HTTP Body（XML）
     *   ├─ 2. 校验 SHA-1 签名 ← 确保来源可信
     *   ├─ 3. AES-256-CBC 解密 ← 解密事件消息
     *   ├─ 4. 提取关键字段（Event/ChangeType/UserId/ExternalUserId/State…）
     *   ├─ 5. XADD → Redis Stream ← 异步消费
     *   └─ 6. 立即返回 "success" / HTTP 200
     * </pre>
     * <p>
     * <b>关键设计决策：</b><br>
     * - 不做任何数据库查询，确保处理时间 < 20ms<br>
     * - 解密后只提取必要字段（事件类型、客户ID、员工ID、State），全部业务逻辑移至异步消费者<br>
     * - 即使解析失败也返回 "success"，防止企微重复推送<br>
     * - 完整原始 XML 存储在事件的 "raw_xml" 字段中，供消费者按需提取更多信息<br>
     * <p>
     * <b>支持的企微回调事件类型（示意）：</b>
     * <ul>
     *   <li>change_external_contact — 客户变更（添加/删除/编辑）</li>
     *   <li>change_external_chat — 客户群变更</li>
     *   <li>change_external_tag — 企业标签变更</li>
     *   <li>change_contact_way — 活码配置变更</li>
     *   <li>transfer_customer — 客户继承结果通知</li>
     * </ul>
     *
     * @param msgSignature 企微请求头传入的消息体签名（参数: msg_signature），
     *                     用于校验消息未被篡改
     * @param timestamp    企微请求的时间戳（参数: timestamp）
     * @param nonce        随机字符串（参数: nonce）
     * @param request      HTTP 原始请求对象，用于读取 XML 格式的请求体
     * @return 始终返回 "success"（HTTP 200），即使处理失败也返回成功
     *         以避免企微重复推送同一事件
     */
    @PostMapping
    public String receive(@RequestParam("msg_signature") String msgSignature,
                          @RequestParam("timestamp") String timestamp,
                          @RequestParam("nonce") String nonce,
                          HttpServletRequest request) {
        try {
            // ================================================================
            // 步骤1: 读取请求体（原始 XML）
            // ================================================================
            String body = new String(request.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);

            // ================================================================
            // 步骤2: 校验签名 + AES 解密 → 得到明文 XML
            // ================================================================
            String decryptedXml = validator.decryptMessage(msgSignature, timestamp, nonce, body);

            // ================================================================
            // 步骤3: 快速提取事件关键字段
            //    - Event:        事件类型（如 change_external_contact）
            //    - ChangeType:   变更子类型（如 add_external_contact、del_external_contact）
            //    - ExternalUserID: 外部联系人（客户）的 UserID
            //    - UserID:       企业成员的 UserID（服务人员）
            //    - State:        活码/渠道参数（用于追踪客户来源）
            //    - FailReason:   失败原因（如继承失败时携带）
            //    - Source:       客户来源渠道
            // ================================================================
            String eventType = extractXmlTag(decryptedXml, "Event");
            String changeType = extractXmlTag(decryptedXml, "ChangeType");
            String externalUserId = extractXmlTag(decryptedXml, "ExternalUserID");
            String userId = extractXmlTag(decryptedXml, "UserID");
            String state = extractXmlTag(decryptedXml, "State");
            String failReason = extractXmlTag(decryptedXml, "FailReason");
            String source = extractXmlTag(decryptedXml, "Source");

            // ================================================================
            // 步骤4: 构造事件 JSON（标准化结构，便于消费者统一处理）
            // ================================================================
            Map<String, Object> event = new HashMap<>();
            event.put("event_type", eventType != null ? eventType : changeType);
            event.put("external_userid", externalUserId);
            event.put("userid", userId);
            event.put("state", state);
            event.put("fail_reason", failReason);
            event.put("source", source);
            event.put("timestamp", Instant.now().toString());
            event.put("raw_xml", decryptedXml);       // 保留完整 XML，供消费者按需提取

            String eventJson = objectMapper.writeValueAsString(event);

            // ================================================================
            // 步骤5: XADD → Redis Stream（异步消息队列）
            //   Key: CALLBACK_STREAM_KEY
            //   消费者组: 后台 Worker 通过 XREADGROUP 获取并消费
            //   ⚠️ 此处不做 XACK，由消费者在完成业务处理后 ACK
            // ================================================================
            redisTemplate.opsForStream().add(
                RedisConfig.CALLBACK_STREAM_KEY,
                Map.of("event", eventJson));

            log.debug("回调已入队: type={}, userid={}, external={}",
                eventType, userId, externalUserId);

            // ================================================================
            // 步骤6: 快速确认 — 立即返回 HTTP 200
            //   企微要求响应纯文本 "success"（编码 UTF-8）
            //   注意：不要在此处进行任何数据库操作或第三方调用
            // ================================================================
            return "success";

        } catch (Exception e) {
            log.error("回调处理异常", e);
            // 异常兜底：即使异常也返回 "success"，避免企微不断重试
            return "success";
        }
    }

    /**
     * 简易 XML 标签内容提取（非 XPath 方式）。
     * <p>
     * 优先匹配 CDATA 包裹格式：{@code <tag><![CDATA[content]]></tag>}<br>
     * 其次匹配标准标签格式：{@code <tag>content</tag>}<br>
     * <p>
     * <b>注意事项：</b>
     * <ul>
     *   <li>不支持嵌套同名标签</li>
     *   <li>匹配效率低（indexOf 线性扫描），仅适用于小体量 XML</li>
     *   <li>生产环境建议替换为 XPath 或 StAX 解析器</li>
     * </ul>
     *
     * @param xml 原始 XML 字符串
     * @param tag 标签名称，如 "Event"、"ChangeType"、"ExternalUserID"
     * @return 标签文本内容；标签不存在或 xml 为 {@code null} 时返回 {@code null}
     */
    private String extractXmlTag(String xml, String tag) {
        if (xml == null) return null;
        // 策略1：优先匹配 CDATA 包裹格式（企微回调标准格式）
        String startTag = "<" + tag + "><![CDATA[";
        String endTag = "]]></" + tag + ">";
        int start = xml.indexOf(startTag);
        int end = xml.indexOf(endTag);
        if (start >= 0 && end > start) {
            return xml.substring(start + startTag.length(), end);
        }
        // 策略2：标准标签格式（无 CDATA 包裹）
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

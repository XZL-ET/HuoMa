package com.bookstore.qrcode.wecom;

import com.bookstore.qrcode.config.WecomConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * 企业微信回调消息的签名校验与 AES-256-CBC 解密组件。
 * <p>
 * <b>加解密协议链（企微回调安全模型）：</b><br>
 * <pre>
 * 企微服务器                                        本系统
 *    │                                                │
 *    │  POST XML (含 Encrypt 密文)                     │
 *    │  + msg_signature(timestamp,nonce,Encrypt)       │
 *    │ ──────────────────────────────────────────────> │
 *    │                                                │
 *    │ 步骤1 — SHA-1 签名校验                          │
 *    │   sorted(token, timestamp, nonce, encrypt)      │
 *    │   → sha1 hex string                             │
 *    │   → 比对 msgSignature                           │
 *    │                                                │
 *    │ 步骤2 — Base64 解码密文                         │
 *    │   encrypt = Base64.decode(raw)                  │
 *    │                                                │
 *    │ 步骤3 — AES-256-CBC 解密                        │
 *    │   key = Base64.decode(EncodingAESKey + "=")     │
 *    │   iv  = key[0..15]                              │
 *    │   decrypted = AES/CBC/NoPadding.decrypt(enc)    │
 *    │                                                │
 *    │ 步骤4 — PKCS#7 去除填充                         │
 *    │   pad  = decrypted[last] & 0xFF                 │
 *    │   data = decrypted[0 .. len-pad]                │
 *    │                                                │
 *    │ 步骤5 — 剥离报文头                              │
 *    │   [0..15]   random(16)   ← 丢弃                 │
 *    │   [16..19]  msg_len(4)   ← 网络字节序大端       │
 *    │   [20..msg_len-1]  msg  ← 实际 XML 消息体       │
 *    │   [msg_len..]  corpid     ← 丢弃                 │
 *    │                                                │
 *    │ 步骤6 — 得到最终明文 XML                         │
 *    │   <xml>...通联/事件消息...</xml>                │
 * </pre>
 * 参考企微文档：<a href="https://developer.work.weixin.qq.com/document/path/90930">回调加解密方案</a>
 *
 * @author bookstore
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WecomCallbackValidator {

    private final WecomConfig config;

    /**
     * 验证回调 URL（GET 请求）时的签名校验与 echostr 解密。
     * <p>
     * 此方法用于企微后台配置回调 URL 时的「URL 验证」流程：
     * <ol>
     *   <li>企微 GET 请求本系统，携带 msg_signature / timestamp / nonce / echostr</li>
     *   <li>将 token、timestamp、nonce、echostr 按字典序拼接后做 SHA-1</li>
     *   <li>比对签名是否一致</li>
     *   <li>对 echostr 做 AES 解密，return 给企微 — 企微比对返回值确认 URL 可用</li>
     * </ol>
     *
     * @param msgSignature 企微传入的消息体签名（msg_signature）
     * @param timestamp    时间戳
     * @param nonce        随机数
     * @param echostr      加密的 echostr 字符串（Base64 编码后的密文）
     * @return 解密后的 echostr 明文（企微期望的原始字符串），由 caller 直接返回 HTTP body
     * @throws RuntimeException 签名不匹配或解密失败
     */
    public String verify(String msgSignature, String timestamp, String nonce, String echostr) {
        try {
            // SHA-1 签名校验：token + timestamp + nonce + echostr 字典序拼接
            String signature = sha1(config.getCallbackToken(), timestamp, nonce, echostr);
            if (!signature.equals(msgSignature)) {
                log.error("回调URL验证失败: 签名不匹配");
                throw new RuntimeException("签名验证失败");
            }
            // 解密 echostr 并提取明文 — 企微协议格式:
            //   random(16字节) + msg_len(4字节网络序) + echostr明文 + corpid
            byte[] decryptedBytes = decryptToBytes(config.getCallbackEncodingAesKey(), echostr);
            // 读取 msg_len（网络字节序大端，偏移 16）
            int msgLen = ((decryptedBytes[16] & 0xFF) << 24)
                       | ((decryptedBytes[17] & 0xFF) << 16)
                       | ((decryptedBytes[18] & 0xFF) << 8)
                       |  (decryptedBytes[19] & 0xFF);
            // 提取 echostr（跳过 20 字节头，取 msgLen 长度，丢弃尾部 corpid）
            return new String(decryptedBytes, 20, msgLen, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("回调URL验证异常", e);
            throw new RuntimeException("回调验证失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解密回调消息体（POST 请求的消息体）。
     * <p>
     * 完整解密流程：<br>
     * ① 从 XML 中提取 {@code <Encrypt><![CDATA[...]]></Encrypt>} 字段<br>
     * ② SHA-1 签名校验（token + timestamp + nonce + encrypt 字典序）<br>
     * ③ AES-256-CBC 解密得到字节数组<br>
     * ④ PKCS#7 去除填充字节<br>
     * ⑤ 跳过前 16 字节的随机字符串（random）<br>
     * ⑥ 读取接下来的 4 字节网络序（大端）长度字段（msg_len）<br>
     * ⑦ 截取实际密文消息体（msg）<br>
     * ⑧ 去掉尾部附带的 corpid（企微标识）<br>
     * ⑨ 返回纯 XML 明文
     *
     * @param msgSignature  企微传入的消息体签名（msg_signature）
     * @param timestamp     时间戳
     * @param nonce         随机数
     * @param encryptedBody 回调 POST 的完整 XML 请求体（含 Encrypt 密文标签）
     * @return 解密后的 XML 格式明文事件消息，如 {@code <xml><Event>change_external_contact</Event>...</xml>}
     * @throws RuntimeException 签名校验失败、解密失败或 XML 解析异常
     */
    public String decryptMessage(String msgSignature, String timestamp,
                                  String nonce, String encryptedBody) {
        try {
            // 步骤1：从原始 XML 中提取 <Encrypt> 标签内的 Base64 密文
            String encrypt = extractXmlTag(encryptedBody, "Encrypt");
            if (encrypt == null) {
                throw new RuntimeException("回调消息体中未找到 Encrypt 字段");
            }

            // 步骤2：SHA-1 签名校验
            // 排序规则：token、timestamp、nonce、encrypt（实际密文，不是原始 XML Body）
            String callbackToken = config.getCallbackToken();
            log.info("回调签名调试: timestamp={}, nonce={}, encrypt前20字符={}",
                timestamp, nonce, encrypt != null ? encrypt.substring(0, Math.min(20, encrypt.length())) : "null");
            String signature = sha1(callbackToken, timestamp, nonce, encrypt);
            if (!signature.equals(msgSignature)) {
                log.error("回调消息签名校验失败, 期望={}, 实际={}", msgSignature, signature);
                throw new RuntimeException("回调签名校验失败");
            }

            // 步骤3：AES-256-CBC 解密（含 PKCS#7 去填充）
            String decrypted = decrypt(config.getCallbackEncodingAesKey(), encrypt);

            // 步骤4：剥离协议头 — 去掉 random(16字节) + msg_len(4字节)
            byte[] decryptedBytes = decrypted.getBytes(StandardCharsets.UTF_8);
            byte[] msgBytes = Arrays.copyOfRange(decryptedBytes, 20, decryptedBytes.length);

            // 步骤5：去掉尾部附加的 corpid（企业微信 ID）
            // 企微协议要求明文格式：random(16) + network_order_msg_len(4) + msg + corpid
            // corpid 在消息末尾且无分隔符，取最后一个 '>' 标签结束符截断
            String msg = new String(msgBytes, StandardCharsets.UTF_8);
            int lastBrace = msg.lastIndexOf('>');
            if (lastBrace > 0) {
                msg = msg.substring(0, lastBrace + 1);
            }

            return msg;
        } catch (Exception e) {
            log.error("回调消息解密失败", e);
            throw new RuntimeException("解密失败: " + e.getMessage(), e);
        }
    }

    /**
     * SHA-1 签名生成。
     * <p>
     * 实现逻辑：<br>
     * ① 将参数按字典序（Arrays.sort）排序<br>
     * ② 拼接为连续字符串（无分隔符）<br>
     * ③ SHA-1 摘要 → 格式化为 40 位小写十六进制字符串<br>
     * <br>
     * <b>注意：</b>企微签名的排序规则与普通 SHA-1WithRSA 不同，
     * 不使用密钥签名，而是将待签名字段排序后直做 SHA-1 摘要。
     *
     * @param params 待排序拼接的参数列表
     * @return 40 位小写十六进制 SHA-1 摘要
     * @throws Exception 当 MessageDigest 实例化失败时抛出
     */
    private String sha1(String... params) throws Exception {
        // 步骤1：字典序排序
        String[] sorted = Arrays.copyOf(params, params.length);
        Arrays.sort(sorted);
        // 步骤2：无分隔符拼接
        String raw = String.join("", sorted);
        // 步骤3：SHA-1 摘要
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
        // 步骤4：十六进制格式化
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * AES-256-CBC 解密（PKCS#7 填充模式）。
     * <p>
     * <b>密钥生成规则：</b><br>
     * <pre>
     * EncodingAESKey = 43字符 Base64 安全编码
     *   → Base64解码后得到 43字节的密钥数据 + 补 1个 '=' 补齐 Base64 标准长度
     *   → 实际 AESKey = Base64.decode(EncodingAESKey + "=")，共 32 字节（256 位）
     *   → IV（初始向量）= AESKey 的前 16 字节
     * </pre>
     * <b>解密流程：</b><br>
     * <pre>
     * ciphertext(byte[]) = Base64.decode(encrypted)
     * AES/CBC/NoPadding.decrypt(ciphertext, key, iv) = paddedPlaintext
     * PKCS#7 unpad: pad = paddedPlaintext[last] & 0xFF, unpadded = plaintext[0 .. len-pad]
     * </pre>
     *
     * @param encodingAesKey 企微应用配置的 EncodingAESKey（43 字符 Base64 编码字符串）
     * @param encrypted      待解密的 Base64 密文字符串
     * @return UTF-8 编码的明文（含 protocol header，需调用方自行剥离前 20 字节）
     * @throws Exception 解密密钥无效、数据被篡改或 PKCS#7 填充不合法时抛出
     */
    /**
     * AES-256-CBC 解密，返回原始字节数组（含协议头 random+msg_len+body+corpid）。
     */
    private byte[] decryptToBytes(String encodingAesKey, String encrypted) throws Exception {
        // 步骤1：Base64 解码 AES 密钥（补 = 以符合标准 Base64）
        byte[] aesKey = Base64.getDecoder().decode(encodingAesKey + "=");
        // 步骤2：Base64 解码密文
        byte[] encryptedBytes = Base64.getDecoder().decode(encrypted);

        // 步骤3：初始化 AES/CBC/NoPadding 解密器，IV = AESKey 前 16 字节
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
        IvParameterSpec iv = new IvParameterSpec(Arrays.copyOfRange(aesKey, 0, 16));
        cipher.init(Cipher.DECRYPT_MODE, keySpec, iv);

        // 步骤4：执行 CBC 模式解密 + PKCS#7 去填充
        byte[] decrypted = cipher.doFinal(encryptedBytes);
        int pad = decrypted[decrypted.length - 1] & 0xFF;
        return Arrays.copyOfRange(decrypted, 0, decrypted.length - pad);
    }

    private String decrypt(String encodingAesKey, String encrypted) throws Exception {
        return new String(decryptToBytes(encodingAesKey, encrypted), StandardCharsets.UTF_8);
    }

    /**
     * 简易 XML 标签内容提取（非 XPath 方式）。
     * <p>
     * 优先匹配 CDATA 包裹格式：
     * <pre>{@code <tag><![CDATA[content]]></tag>}</pre>
     * 其次匹配标准标签格式：
     * <pre>{@code <tag>content</tag>}</pre>
     * <p>
     * <b>限制：</b>不支持嵌套同名标签，<b>生产环境建议替换为 JAXB / XStream / XPath 解析</b>。
     * 当前仅适用于企微回调消息的固定 XML 结构。
     *
     * @param xml 原始 XML 字符串
     * @param tag 标签名称，如 "Encrypt"、"Event"、"ChangeType"
     * @return 标签内容字符串，标签不存在则返回 {@code null}
     */
    private String extractXmlTag(String xml, String tag) {
        // 策略1：优先匹配 CDATA 包裹格式 — 企微回调统一使用 CDATA 包裹文本值
        //   例如：<Encrypt><![CDATA[base64密文]]></Encrypt>
        String startTag = "<" + tag + "><![CDATA[";
        String endTag = "]]></" + tag + ">";
        int start = xml.indexOf(startTag);
        int end = xml.indexOf(endTag);
        if (start >= 0 && end > start) {
            return xml.substring(start + startTag.length(), end);
        }
        // 策略2：标准标签格式（无 CDATA）
        //   例如：<CreateTime>1234567890</CreateTime>
        startTag = "<" + tag + ">";
        endTag = "</" + tag + ">";
        start = xml.indexOf(startTag);
        end = xml.indexOf(endTag);
        if (start >= 0 && end > start) {
            String content = xml.substring(start + startTag.length(), end);
            // 防御性处理：如果内容以 CDATA 包裹，自动去除 CDATA 标记
            if (content.startsWith("<![CDATA[")) {
                content = content.substring(9, content.length() - 3);
            }
            return content;
        }
        return null;
    }
}

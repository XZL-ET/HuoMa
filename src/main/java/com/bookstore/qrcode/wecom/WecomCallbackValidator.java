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
 * 企微回调签名校验 + 消息解密。
 * 参考企微文档：https://developer.work.weixin.qq.com/document/path/90930
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WecomCallbackValidator {

    private final WecomConfig config;

    /**
     * 验证回调 URL 时的签名校验。
     */
    public String verify(String msgSignature, String timestamp, String nonce, String echostr) {
        try {
            String signature = sha1(config.getCallbackToken(), timestamp, nonce, echostr);
            if (!signature.equals(msgSignature)) {
                log.error("回调URL验证失败: 签名不匹配");
                throw new RuntimeException("签名验证失败");
            }
            // 解密 echostr
            String decrypted = decrypt(config.getCallbackEncodingAesKey(), echostr);
            return decrypted;
        } catch (Exception e) {
            log.error("回调URL验证异常", e);
            throw new RuntimeException("回调验证失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解密回调消息体。
     * @return XML 格式明文
     */
    public String decryptMessage(String msgSignature, String timestamp,
                                  String nonce, String encryptedBody) {
        try {
            // 从 XML 中提取 Encrypt 字段（简化提取，正式用 XML 解析）
            String encrypt = extractXmlTag(encryptedBody, "Encrypt");
            if (encrypt == null) {
                throw new RuntimeException("回调消息体中未找到 Encrypt 字段");
            }

            // 验证签名
            String signature = sha1(config.getCallbackToken(), timestamp, nonce, encrypt);
            if (!signature.equals(msgSignature)) {
                log.error("回调消息签名校验失败");
                throw new RuntimeException("回调签名校验失败");
            }

            // 解密
            String decrypted = decrypt(config.getCallbackEncodingAesKey(), encrypt);

            // 去掉 random(16字节) + msg_len(4字节) + msg + corpid
            byte[] decryptedBytes = decrypted.getBytes(StandardCharsets.UTF_8);
            byte[] msgBytes = Arrays.copyOfRange(decryptedBytes, 20, decryptedBytes.length);
            // 去掉末尾的 corpid（appid）
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
     * SHA1 签名。
     */
    private String sha1(String... params) throws Exception {
        String[] sorted = Arrays.copyOf(params, params.length);
        Arrays.sort(sorted);
        String raw = String.join("", sorted);
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * AES-256-CBC 解密。
     */
    private String decrypt(String encodingAesKey, String encrypted) throws Exception {
        byte[] aesKey = Base64.getDecoder().decode(encodingAesKey + "=");
        byte[] encryptedBytes = Base64.getDecoder().decode(encrypted);

        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
        IvParameterSpec iv = new IvParameterSpec(Arrays.copyOfRange(aesKey, 0, 16));
        cipher.init(Cipher.DECRYPT_MODE, keySpec, iv);

        byte[] decrypted = cipher.doFinal(encryptedBytes);

        // PKCS#7 去填充
        int pad = decrypted[decrypted.length - 1] & 0xFF;
        byte[] unpadded = Arrays.copyOfRange(decrypted, 0, decrypted.length - pad);
        return new String(unpadded, StandardCharsets.UTF_8);
    }

    /**
     * 简单提取 XML 标签内容（生产环境建议用 JAXB/XStream）。
     */
    private String extractXmlTag(String xml, String tag) {
        String startTag = "<" + tag + ">";
        String endTag = "</" + tag + ">";
        int start = xml.indexOf(startTag);
        int end = xml.indexOf(endTag);
        if (start >= 0 && end > start) {
            return xml.substring(start + startTag.length(), end);
        }
        // CDATA 包裹
        startTag = "<" + tag + "><![CDATA[";
        endTag = "]]></" + tag + ">";
        start = xml.indexOf(startTag);
        end = xml.indexOf(endTag);
        if (start >= 0 && end > start) {
            return xml.substring(start + startTag.length(), end);
        }
        return null;
    }
}

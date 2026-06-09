package com.bookstore.qrcode.wecom;

import com.bookstore.qrcode.config.WecomConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;

/**
 * 企业微信 API 客户端。
 * 封装 access_token 管理 + 活码/标签/继承相关 API。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WecomApiClient {

    private final WecomConfig config;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TOKEN_URL =
        "https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=%s&corpsecret=%s";
    private static final String BASE_URL = "https://qyapi.weixin.qq.com/cgi-bin";

    // ==================== access_token ====================

    /**
     * 获取 access_token，带缓存。线程安全。
     */
    public synchronized String getAccessToken() {
        if (config.getAccessToken() != null
                && Instant.now().getEpochSecond() < config.getAccessTokenExpireAt()) {
            return config.getAccessToken();
        }
        try {
            String url = String.format(TOKEN_URL, config.getCorpId(), config.getCorpSecret());
            String resp = restTemplate.getForObject(url, String.class);
            JsonNode node = objectMapper.readTree(resp);

            int errcode = node.get("errcode").asInt();
            if (errcode != 0) {
                String errmsg = node.has("errmsg") ? node.get("errmsg").asText() : "";
                throw new RuntimeException("获取 access_token 失败: errcode=" + errcode + " " + errmsg);
            }

            String token = node.get("access_token").asText();
            long expiresIn = node.get("expires_in").asLong(); // 默认 7200
            config.setAccessToken(token);
            // 提前 200 秒过期，留缓冲
            config.setAccessTokenExpireAt(Instant.now().getEpochSecond() + expiresIn - 200);
            log.info("access_token 已刷新，过期时间: {}", config.getAccessTokenExpireAt());
            return token;
        } catch (Exception e) {
            log.error("获取 access_token 异常", e);
            throw new RuntimeException("获取 access_token 失败: " + e.getMessage(), e);
        }
    }

    // ==================== 活码（联系我） ====================

    /**
     * 创建「联系我」二维码。
     * @param requestJson JSON 参数字符串
     * @return {config_id, qr_code}
     */
    public JsonNode createContactWay(String requestJson) {
        String url = BASE_URL + "/externalcontact/add_contact_way?access_token=" + getAccessToken();
        String resp = restTemplate.postForObject(url, requestJson, String.class);
        return parseOrThrow(resp, "创建活码");
    }

    /**
     * 更新活码配置。
     */
    public JsonNode updateContactWay(String requestJson) {
        String url = BASE_URL + "/externalcontact/update_contact_way?access_token=" + getAccessToken();
        String resp = restTemplate.postForObject(url, requestJson, String.class);
        return parseOrThrow(resp, "更新活码");
    }

    /**
     * 删除活码。
     */
    public void deleteContactWay(String configId) {
        String url = BASE_URL + "/externalcontact/del_contact_way?access_token=" + getAccessToken();
        String body = "{\"config_id\":\"" + configId + "\"}";
        String resp = restTemplate.postForObject(url, body, String.class);
        parseOrThrow(resp, "删除活码");
    }

    // ==================== 标签 ====================

    /**
     * 创建企业标签（或标签组下的标签）。
     * @param tagName 标签名称
     * @param groupId 标签组 ID（可为 null，则创建到默认组或作为根标签）
     * @return {errcode, errmsg, tagid} — tagid 是 WeCom 标签 ID
     */
    public JsonNode addCorpTag(String tagName, String groupId) {
        String url = BASE_URL + "/externalcontact/add_corp_tag?access_token=" + getAccessToken();
        try {
            String body;
            if (groupId != null && !groupId.isEmpty()) {
                body = String.format(
                    "{\"group_id\":\"%s\",\"tag\":[{\"name\":\"%s\"}]}",
                    groupId, tagName);
            } else {
                body = String.format(
                    "{\"tag\":[{\"name\":\"%s\"}]}", tagName);
            }
            String resp = restTemplate.postForObject(url, body, String.class);
            return parseOrThrow(resp, "创建企业标签");
        } catch (Exception e) {
            throw new RuntimeException("创建企业标签失败: " + e.getMessage(), e);
        }
    }

    /**
     * 创建企业标签（带 group_name，用于首次创建组和标签）。
     * @param tagName 标签名称
     * @param groupName 标签组名称
     * @return {errcode, errmsg, tag_group: {group_id, group_name, tag: [{id, name}]}}
     */
    public JsonNode addCorpTagWithGroup(String tagName, String groupName) {
        String url = BASE_URL + "/externalcontact/add_corp_tag?access_token=" + getAccessToken();
        try {
            String body = String.format(
                "{\"group_name\":\"%s\",\"tag\":[{\"name\":\"%s\"}]}",
                groupName, tagName);
            String resp = restTemplate.postForObject(url, body, String.class);
            return parseOrThrow(resp, "创建企业标签组");
        } catch (Exception e) {
            throw new RuntimeException("创建企业标签组失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取企业标签列表。
     * @return {errcode, errmsg, tag_group: [{group_id, group_name, tag: [{id, name}]}]}
     */
    public JsonNode getCorpTagList() {
        String url = BASE_URL + "/externalcontact/get_corp_tag_list?access_token=" + getAccessToken();
        try {
            String body = "{}";
            String resp = restTemplate.postForObject(url, body, String.class);
            return parseOrThrow(resp, "获取标签列表");
        } catch (Exception e) {
            throw new RuntimeException("获取标签列表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 为客户打标签。
     */
    public void markTag(String externalUserId, String userId, List<String> tagIds) {
        String url = BASE_URL + "/externalcontact/mark_tag?access_token=" + getAccessToken();
        try {
            String tagIdsJson = objectMapper.writeValueAsString(tagIds);
            String body = String.format(
                "{\"userid\":\"%s\",\"external_userid\":\"%s\",\"add_tag\":%s}",
                userId, externalUserId, tagIdsJson);
            String resp = restTemplate.postForObject(url, body, String.class);
            parseOrThrow(resp, "打标签");
        } catch (Exception e) {
            throw new RuntimeException("打标签失败: " + e.getMessage(), e);
        }
    }

    // ==================== 在职继承 ====================

    /**
     * 在职继承 — 发起客户转移。
     * @return {errcode, errmsg}
     */
    public JsonNode transferCustomer(String handoverUserid, String takeoverUserid,
                                      String externalUserid) {
        String url = BASE_URL + "/externalcontact/transfer_customer?access_token=" + getAccessToken();
        String body = String.format(
            "{\"handover_userid\":\"%s\",\"takeover_userid\":\"%s\",\"external_userid\":[\"%s\"]}",
            handoverUserid, takeoverUserid, externalUserid);
        String resp = restTemplate.postForObject(url, body, String.class);
        return parseOrThrow(resp, "在职继承");
    }

    /**
     * 查询继承结果。
     */
    public JsonNode getTransferResult(String handoverUserid, String takeoverUserid,
                                       String externalUserid) {
        String url = BASE_URL + "/externalcontact/get_transfer_result?access_token=" + getAccessToken();
        String body = String.format(
            "{\"handover_userid\":\"%s\",\"takeover_userid\":\"%s\",\"external_userid\":\"%s\"}",
            handoverUserid, takeoverUserid, externalUserid);
        String resp = restTemplate.postForObject(url, body, String.class);
        return parseOrThrow(resp, "查询继承结果");
    }

    // ==================== 客户 ====================

    /**
     * 获取客户详情。
     */
    public JsonNode getExternalContact(String externalUserid) {
        String url = BASE_URL + "/externalcontact/get?access_token=" + getAccessToken()
                     + "&external_userid=" + externalUserid;
        String resp = restTemplate.getForObject(url, String.class);
        return parseOrThrow(resp, "获取客户详情");
    }

    /**
     * 获取客户列表。
     */
    public JsonNode getExternalContactList(String userid) {
        String url = BASE_URL + "/externalcontact/list?access_token=" + getAccessToken()
                     + "&userid=" + userid;
        String resp = restTemplate.getForObject(url, String.class);
        return parseOrThrow(resp, "获取客户列表");
    }

    /**
     * 获取部门成员（递归），用于下拉框选择接待员。
     */
    public JsonNode getUserSimplelist() {
        String url = BASE_URL + "/user/simplelist?access_token=" + getAccessToken()
                     + "&department_id=1&fetch_child=1";
        String resp = restTemplate.getForObject(url, String.class);
        return parseOrThrow(resp, "获取成员列表");
    }

    /**
     * 发送消息给客户（文本）。
     */
    public void sendMessage(String sender, String externalUserid, String text) {
        String url = BASE_URL + "/externalcontact/message/send?access_token=" + getAccessToken();
        try {
            String escapedText = objectMapper.writeValueAsString(text);
            String body = String.format(
                "{\"sender\":\"%s\",\"external_userid\":\"%s\",\"msgtype\":\"text\",\"text\":{\"content\":%s}}",
                sender, externalUserid, escapedText);
            String resp = restTemplate.postForObject(url, body, String.class);
            parseOrThrow(resp, "发送消息");
        } catch (Exception e) {
            throw new RuntimeException("发送消息失败: " + e.getMessage(), e);
        }
    }

    // ==================== 内部工具 ====================

    private JsonNode parseOrThrow(String resp, String action) {
        try {
            JsonNode node = objectMapper.readTree(resp);
            int code = node.has("errcode") ? node.get("errcode").asInt() : -1;
            if (code != 0) {
                String errmsg = node.has("errmsg") ? node.get("errmsg").asText() : "";
                log.error("{} 失败: errcode={} errmsg={}", action, code, errmsg);
                // 返回原始 node，让调用方根据 errcode 做分类处理
            }
            return node;
        } catch (Exception e) {
            log.error("{} 解析响应异常: {}", action, resp, e);
            throw new RuntimeException(action + " 失败: " + resp, e);
        }
    }
}

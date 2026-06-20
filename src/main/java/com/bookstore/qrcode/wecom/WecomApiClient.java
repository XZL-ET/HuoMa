package com.bookstore.qrcode.wecom;

import com.bookstore.qrcode.config.WecomConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 企业微信 API 客户端。
 * <p>
 * 封装 access_token 自动管理及企微服务端 API 调用，涵盖以下模块：
 * <ul>
 *   <li><b>access_token</b> — 自动获取/缓存/刷新，线程安全</li>
 *   <li><b>活码（联系我）</b> — 创建/更新/删除「联系我」二维码</li>
 *   <li><b>标签</b> — 创建企业标签（含标签组）、获取标签列表、客户打标签</li>
 *   <li><b>在职继承</b> — 发起客户转移、查询转移结果</li>
 *   <li><b>客户</b> — 获取客户详情/列表</li>
 *   <li><b>部门成员</b> — 递归获取部门成员</li>
 *   <li><b>消息</b> — 发送文本消息给客户</li>
 * </ul>
 * <p>
 * <b>API 调用规范：</b><br>
 * 所有 API 调用使用 {@link RestTemplate}（HTTP POST/GET），
 * 请求时自动拼接 access_token 参数（通过 {@link #getAccessToken()}），
 * 响应统一经 {@link #parseOrThrow(String, String)} 解析，
 * 非零 errcode 记录日志但不抛异常（由调用方按错误码分类处理）。
 * <p>
 * 参考企微文档：<a href="https://developer.work.weixin.qq.com/document/path/90600">服务端 API 文档</a>
 *
 * @author bookstore
 * @since 1.0.0
 */
@Slf4j
@Component
public class WecomApiClient {

    private final WecomConfig config;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 企微根部门 ID，用于递归拉取成员列表，可通过 app.wecom.root-department-id 配置 */
    @Value("${app.wecom.root-department-id:1}")
    private int rootDepartmentId;

    public WecomApiClient(WecomConfig config,
                          @Value("${app.wecom.connect-timeout:3}") int connectTimeoutSec,
                          @Value("${app.wecom.read-timeout:10}") int readTimeoutSec,
                          RestTemplateBuilder builder) {
        this.config = config;
        this.restTemplate = builder
            .connectTimeout(Duration.ofSeconds(connectTimeoutSec))
            .readTimeout(Duration.ofSeconds(readTimeoutSec))
            .build();
    }

    /** access_token 获取接口: GET https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=ID&corpsecret=SECRET */
    private static final String TOKEN_URL =
        "https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=%s&corpsecret=%s";
    /** 企微 API 基础路径 */
    private static final String BASE_URL = "https://qyapi.weixin.qq.com/cgi-bin";

    /** access_token 读写锁：缓存命中时多线程并发读，刷新时排他写 */
    private final ReentrantReadWriteLock tokenLock = new ReentrantReadWriteLock();

    // ========================================================================
    //  access_token 管理
    //  文档: https://developer.work.weixin.qq.com/document/path/91039
    // ========================================================================

    /**
     * 获取 access_token（自动缓存与刷新，线程安全）。
     * <p>
     * 首次调用或 token 即将过期时自动向企微服务器请求新 token。
     * 缓存提前 200 秒过期，为网络延迟和时钟偏差留出缓冲。
     * <p>
     * <b>企微接口：</b>{@code GET /cgi-bin/gettoken?corpid=ID&corpsecret=SECRET}
     * <pre>
     * 响应示例:
     * {
     *   "errcode": 0,
     *   "errmsg": "ok",
     *   "access_token": "xxxxxx",
     *   "expires_in": 7200
     * }
     * </pre>
     *
     * @return 有效的 access_token 字符串
     * @throws RuntimeException 获取失败时抛出（网络超时、corpid/corpsecret 无效等）
     */
    public String getAccessToken() {
        // 读锁：缓存命中时多线程并发，不互斥
        tokenLock.readLock().lock();
        try {
            if (config.getAccessToken() != null
                    && Instant.now().getEpochSecond() < config.getAccessTokenExpireAt()) {
                return config.getAccessToken();
            }
        } finally {
            tokenLock.readLock().unlock();
        }

        // 写锁：缓存过期/缺失时排他刷新，防止多线程同时调企微 API
        tokenLock.writeLock().lock();
        try {
            // Double-check：可能其他线程已刷新
            if (config.getAccessToken() != null
                    && Instant.now().getEpochSecond() < config.getAccessTokenExpireAt()) {
                return config.getAccessToken();
            }

            String url = String.format(TOKEN_URL, config.getCorpId(), config.getCorpSecret());
            String resp = restTemplate.getForObject(url, String.class);
            JsonNode node = objectMapper.readTree(resp);

            int errcode = node.get("errcode").asInt();
            if (errcode != 0) {
                String errmsg = node.has("errmsg") ? node.get("errmsg").asText() : "";
                throw new RuntimeException("获取 access_token 失败: errcode=" + errcode + " " + errmsg);
            }

            String token = node.get("access_token").asText();
            long expiresIn = node.get("expires_in").asLong(); // 企微默认 7200 秒

            // 写入配置缓存
            config.setAccessToken(token);
            // 提前 200 秒过期，留缓冲防止在过期边缘因时钟偏差导致 42001 错误
            config.setAccessTokenExpireAt(Instant.now().getEpochSecond() + expiresIn - 200);
            log.info("access_token 已刷新，过期时间: {}", config.getAccessTokenExpireAt());
            return token;
        } catch (Exception e) {
            log.error("获取 access_token 异常", e);
            throw new RuntimeException("获取 access_token 失败: " + e.getMessage(), e);
        } finally {
            tokenLock.writeLock().unlock();
        }
    }

    // ========================================================================
    //  活码（联系我）
    //  文档: https://developer.work.weixin.qq.com/document/path/92228
    // ========================================================================

    /**
     * 创建「联系我」二维码（活码）。
     * <p>
     * <b>企微接口：</b>{@code POST /cgi-bin/externalcontact/add_contact_way}
     * <pre>
     * 请求参数示例:
     * {
     *   "type": 1,
     *   "scene": 2,
     *   "user": ["zhangsan"],
     *   "state": "activity_001"
     * }
     * 响应示例:
     * {
     *   "errcode": 0,
     *   "errmsg": "ok",
     *   "config_id": "a7f0b...",
     *   "qr_code": "https://open.work.weixin.qq.com/...",
     *   "qr_code_base64": "base64..."
     * }
     * </pre>
     *
     * @param requestJson 完整的请求 body JSON 字符串（由上层组装，含 type/scene/user/state 等）
     * @return JsonNode 包含 {@code config_id}（活码配置ID）和 {@code qr_code}（二维码图片URL）
     */
    public JsonNode createContactWay(String requestJson) {
        String url = BASE_URL + "/externalcontact/add_contact_way?access_token=" + getAccessToken();
        String resp = postForJson(url, requestJson);
        return parseOrThrow(resp, "创建活码");
    }

    /**
     * 更新活码配置。
     * <p>
     * <b>企微接口：</b>{@code POST /cgi-bin/externalcontact/update_contact_way}
     * <br>
     * 用于修改活码的接待人员、欢迎语、备注等信息。
     * <br>
     * <b>注意：</b>更新后已生成的二维码图片 URL 不变，但扫码后的行为会立即生效。
     *
     * @param requestJson 包含 {@code config_id} 及待更新字段的 JSON 字符串
     * @return JsonNode {@code {errcode, errmsg}}
     */
    public JsonNode updateContactWay(String requestJson) {
        String url = BASE_URL + "/externalcontact/update_contact_way?access_token=" + getAccessToken();
        String resp = postForJson(url, requestJson);
        return parseOrThrow(resp, "更新活码");
    }

    /**
     * 删除活码配置。
     * <p>
     * <b>企微接口：</b>{@code POST /cgi-bin/externalcontact/del_contact_way}
     * <br>
     * 删除后该活码失效，客户扫码将提示「该二维码已过期」。
     *
     * @param configId 活码配置 ID（创建时返回的 config_id）
     */
    public void deleteContactWay(String configId) {
        String url = BASE_URL + "/externalcontact/del_contact_way?access_token=" + getAccessToken();
        try {
            String body = objectMapper.writeValueAsString(Map.of("config_id", configId));
            String resp = postForJson(url, body);
            parseOrThrow(resp, "删除活码");
        } catch (Exception e) {
            throw new RuntimeException("删除活码失败: " + e.getMessage(), e);
        }
    }

    // ========================================================================
    //  企业标签
    //  文档: https://developer.work.weixin.qq.com/document/path/92121
    // ========================================================================

    /**
     * 创建企业标签（可指定标签组或自动归入默认组）。
     * <p>
     * <b>企微接口：</b>{@code POST /cgi-bin/externalcontact/add_corp_tag}
     * <pre>
     * 请求（指定 group_id）:
     *   {"group_id":"xxx","tag":[{"name":"VIP客户"}]}
     * 请求（不指定组）:
     *   {"tag":[{"name":"VIP客户"}]}
     * 响应:
     *   {"errcode":0,"errmsg":"ok","tag_id":"tag_id_value"}
     * </pre>
     *
     * @param tagName 标签名称，如 "VIP客户"、"已到店"
     * @param groupId 标签组 ID。非 null 时在指定组下创建标签；
     *                null 或空字符串则创建到默认组或不指定组（由企微自动分配）
     * @return JsonNode 包含 {@code tag_id}（新标签的企微 ID）
     * @throws RuntimeException 创建失败时抛出
     */
    public JsonNode addCorpTag(String tagName, String groupId) {
        String url = BASE_URL + "/externalcontact/add_corp_tag?access_token=" + getAccessToken();
        try {
            Map<String, Object> bodyMap = new java.util.LinkedHashMap<>();
            if (groupId != null && !groupId.isEmpty()) {
                bodyMap.put("group_id", groupId);
            }
            bodyMap.put("tag", List.of(Map.of("name", tagName)));
            String body = objectMapper.writeValueAsString(bodyMap);
            String resp = postForJson(url, body);
            return parseOrThrow(resp, "创建企业标签");
        } catch (Exception e) {
            throw new RuntimeException("创建企业标签失败: " + e.getMessage(), e);
        }
    }

    /**
     * 创建企业标签组及其下的标签（用于首次创建组和标签的场景）。
     * <p>
     * <b>企微接口：</b>{@code POST /cgi-bin/externalcontact/add_corp_tag}
     * <pre>
     * 请求:
     *   {"group_name":"客户等级","tag":[{"name":"VIP客户"}]}
     * 响应:
     *   {"errcode":0,"errmsg":"ok","tag_group":{"group_id":"xxx","group_name":"客户等级","tag":[{"id":"yyy","name":"VIP客户"}]}}
     * </pre>
     * <b>与 {@link #addCorpTag(String, String)} 的区别：</b><br>
     * 此方法使用 {@code group_name} 参数（而非已有组的 {@code group_id}），
     * 企微会自动创建新的标签组或将标签追加到同名组中。
     *
     * @param tagName   标签名称
     * @param groupName 标签组名称（如果该组名已存在则追加到该组，否则新建组）
     * @return JsonNode 包含 {@code tag_group} 对象，内含新创建的 {@code group_id} 和 {@code tag.id}
     * @throws RuntimeException 创建失败时抛出
     */
    public JsonNode addCorpTagWithGroup(String tagName, String groupName) {
        String url = BASE_URL + "/externalcontact/add_corp_tag?access_token=" + getAccessToken();
        try {
            Map<String, Object> bodyMap = new java.util.LinkedHashMap<>();
            bodyMap.put("group_name", groupName);
            bodyMap.put("tag", List.of(Map.of("name", tagName)));
            String body = objectMapper.writeValueAsString(bodyMap);
            String resp = postForJson(url, body);
            return parseOrThrow(resp, "创建企业标签组");
        } catch (Exception e) {
            throw new RuntimeException("创建企业标签组失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取企业标签列表（所有标签组及其下的标签）。
     * <p>
     * <b>企微接口：</b>{@code POST /cgi-bin/externalcontact/get_corp_tag_list}
     * <pre>
     * 响应结构:
     * {
     *   "errcode": 0,
     *   "errmsg": "ok",
     *   "tag_group": [
     *     {
     *       "group_id": "etgxxx",
     *       "group_name": "客户等级",
     *       "create_time": 1234567890,
     *       "tag": [
     *         {"id": "etxxx", "name": "VIP客户", "create_time": 1234567890}
     *       ]
     *     }
     *   ]
     * }
     * </pre>
     *
     * @return JsonNode 包含 {@code tag_group} 数组，每个元素含标签组信息和 {@code tag} 子数组
     * @throws RuntimeException 获取失败时抛出
     */
    public JsonNode getCorpTagList() {
        String url = BASE_URL + "/externalcontact/get_corp_tag_list?access_token=" + getAccessToken();
        try {
            // 请求体传空 JSON 对象，不传参数时获取全部标签
            String body = "{}";
            String resp = postForJson(url, body);
            return parseOrThrow(resp, "获取标签列表");
        } catch (Exception e) {
            throw new RuntimeException("获取标签列表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 为客户打标签（添加标签）。
     * <p>
     * <b>企微接口：</b>{@code POST /cgi-bin/externalcontact/mark_tag}
     * <pre>
     * 请求:
     *   {
     *     "userid": "zhangsan",
     *     "external_userid": "wmxxx",
     *     "add_tag": ["etxxx", "etyyy"]
     *   }
     * 响应:
     *   {"errcode": 0, "errmsg": "ok"}
     * </pre>
     * <b>注意：</b>目前仅支持「添加标签」操作，暂不支持移除标签（需调用时传 del_tag 参数）。
     *
     * @param externalUserId 外部联系人（客户）的 UserID
     * @param userId         企业成员（服务人员）的 UserID
     * @param tagIds         要添加的标签 ID 列表（企微标签 ID，非名称）
     * @throws RuntimeException 打标签失败时抛出（如标签不存在、客户已删除等）
     */
    public void markTag(String externalUserId, String userId, List<String> tagIds) {
        String url = BASE_URL + "/externalcontact/mark_tag?access_token=" + getAccessToken();
        try {
            Map<String, Object> bodyMap = new java.util.LinkedHashMap<>();
            bodyMap.put("userid", userId);
            bodyMap.put("external_userid", externalUserId);
            bodyMap.put("add_tag", tagIds);
            String body = objectMapper.writeValueAsString(bodyMap);
            String respStr = postForJson(url, body);
            JsonNode resp = objectMapper.readTree(respStr);
            int errcode = resp.has("errcode") ? resp.get("errcode").asInt() : -1;
            if (errcode != 0) {
                String errmsg = resp.has("errmsg") ? resp.get("errmsg").asText() : "";
                throw new RuntimeException("打标签 errcode=" + errcode + " " + errmsg);
            }
        } catch (Exception e) {
            throw new RuntimeException("打标签失败: " + e.getMessage(), e);
        }
    }

    // ========================================================================
    //  在职继承
    //  文档: https://developer.work.weixin.qq.com/document/path/92124
    // ========================================================================

    /**
     * 发起客户在职继承（客户转移）。
     * <p>
     * <b>企微接口：</b>{@code POST /cgi-bin/externalcontact/transfer_customer}
     * <br>
     * 员工的客户关系从一个服务人员（原添加人）转移给另一个服务人员。
     * 仅支持在职员工之间的转移。转移成功后，客户会收到一条转移通知。
     * <pre>
     * 请求:
     *   {
     *     "handover_userid": "zhangsan",    // 原添加人
     *     "takeover_userid": "lisi",        // 接替人
     *     "external_userid": ["wmxxxxxx"]   // 待转移的客户列表
     *   }
     * 响应:
     *   {"errcode":0,"errmsg":"ok"}
     * </pre>
     *
     * @param handoverUserid 原添加人（转出方）的 userid
     * @param takeoverUserid 接替人（转入方）的 userid
     * @param externalUserid 待转移客户的 external_userid
     * @return JsonNode {@code {errcode, errmsg}}
     */
    public JsonNode transferCustomer(String handoverUserid, String takeoverUserid,
                                      String externalUserid) {
        String url = BASE_URL + "/externalcontact/transfer_customer?access_token=" + getAccessToken();
        try {
            Map<String, Object> bodyMap = new java.util.LinkedHashMap<>();
            bodyMap.put("handover_userid", handoverUserid);
            bodyMap.put("takeover_userid", takeoverUserid);
            bodyMap.put("external_userid", List.of(externalUserid));
            String body = objectMapper.writeValueAsString(bodyMap);
            String resp = postForJson(url, body);
            return parseOrThrow(resp, "在职继承");
        } catch (Exception e) {
            throw new RuntimeException("在职继承失败: " + e.getMessage(), e);
        }
    }

    /**
     * 查询客户转移结果。
     * <p>
     * <b>企微接口：</b>{@code POST /cgi-bin/externalcontact/get_transfer_result}
     * <br>
     * 在调用 {@link #transferCustomer} 后，通过此接口查询转移状态。
     * 企微转移是异步的，需要轮询此接口确认是否成功。
     * <pre>
     * 请求:
     *   {
     *     "handover_userid": "zhangsan",
     *     "takeover_userid": "lisi",
     *     "external_userid": "wmxxxxxx"
     *   }
     * 响应:
     *   {
     *     "errcode": 0,
     *     "errmsg": "ok",
     *     "customer": [
     *       {"external_userid": "wmxxx", "status": 1}  // 1=成功 2=失败
     *     ]
     *   }
     * </pre>
     *
     * @param handoverUserid 原添加人（转出方）的 userid
     * @param takeoverUserid 接替人（转入方）的 userid
     * @param externalUserid 要查询的客户 external_userid
     * @return JsonNode 包含 {@code customer} 数组，每个元素含 {@code external_userid} 和 {@code status}
     */
    public JsonNode getTransferResult(String handoverUserid, String takeoverUserid,
                                       String externalUserid) {
        String url = BASE_URL + "/externalcontact/get_transfer_result?access_token=" + getAccessToken();
        try {
            Map<String, Object> bodyMap = new java.util.LinkedHashMap<>();
            bodyMap.put("handover_userid", handoverUserid);
            bodyMap.put("takeover_userid", takeoverUserid);
            bodyMap.put("external_userid", externalUserid);
            String body = objectMapper.writeValueAsString(bodyMap);
            String resp = postForJson(url, body);
            return parseOrThrow(resp, "查询继承结果");
        } catch (Exception e) {
            throw new RuntimeException("查询继承结果失败: " + e.getMessage(), e);
        }
    }

    // ========================================================================
    //  客户管理
    //  文档: https://developer.work.weixin.qq.com/document/path/92114
    // ========================================================================

    /**
     * 获取客户详细信息。
     * <p>
     * <b>企微接口：</b>{@code GET /cgi-bin/externalcontact/get?external_userid=xxx}
     * <pre>
     * 响应示例:
     * {
     *   "errcode": 0,
     *   "errmsg": "ok",
     *   "external_contact": {
     *     "external_userid": "wmxxx",
     *     "name": "张三",
     *     "avatar": "https://...",
     *     "type": 1,
     *     "gender": 1,
     *     "corp_name": "某某公司",
     *     ...
     *   },
     *   "follow_info": {
     *     "userid": "zhangsan",
     *     "remark": "客户备注",
     *     "description": "客户描述",
     *     "createtime": 1234567890,
     *     "tags": [...]
     *   }
     * }
     * </pre>
     *
     * @param externalUserid 外部联系人（客户）的 UserID
     * @return JsonNode 包含 {@code external_contact}（客户基本信息）和 {@code follow_info}（跟进信息）
     */
    public JsonNode getExternalContact(String externalUserid) {
        String url = BASE_URL + "/externalcontact/get?access_token=" + getAccessToken()
                     + "&external_userid=" + externalUserid;
        String resp = restTemplate.getForObject(url, String.class);
        return parseOrThrow(resp, "获取客户详情");
    }

    /**
     * 获取指定员工的外部联系人（客户）列表。
     * <p>
     * <b>企微接口：</b>{@code GET /cgi-bin/externalcontact/list?userid=xxx}
     * <pre>
     * 响应示例:
     * {
     *   "errcode": 0,
     *   "errmsg": "ok",
     *   "external_userid": ["wmxxx", "wmyyy", ...]
     * }
     * </pre>
     * 注意：此接口只返回客户 ID 列表，不包含详细信息。
     * 需要详细信息请结合 {@link #getExternalContact(String)} 逐个获取。
     *
     * @param userid 企业成员（服务人员）的 UserID
     * @return JsonNode 包含 {@code external_userid} 数组
     */
    public JsonNode getExternalContactList(String userid) {
        String url = BASE_URL + "/externalcontact/list?access_token=" + getAccessToken()
                     + "&userid=" + userid;
        String resp = restTemplate.getForObject(url, String.class);
        return parseOrThrow(resp, "获取客户列表");
    }

    /**
     * 获取部门成员列表（递归），用于前端下拉框选择接待员。
     * <p>
     * <b>企微接口：</b>{@code GET /cgi-bin/user/simplelist?department_id=1&fetch_child=1}
     * <pre>
     * 响应示例:
     * {
     *   "errcode": 0,
     *   "errmsg": "ok",
     *   "userlist": [
     *     {"userid": "zhangsan", "name": "张三"},
     *     {"userid": "lisi", "name": "李四"}
     *   ]
     * }
     * </pre>
     * <b>固定参数：</b>
     * <ul>
     *   <li>department_id=1 — 根部门（企业全部成员）</li>
     *   <li>fetch_child=1 — 递归获取子部门成员</li>
     * </ul>
     *
     * @return JsonNode 包含 {@code userlist} 数组，每项含 {@code userid} 和 {@code name}
     */
    public JsonNode getUserSimplelist() {
        String url = BASE_URL + "/user/simplelist?access_token=" + getAccessToken()
                     + "&department_id=" + rootDepartmentId + "&fetch_child=1";
        String resp = restTemplate.getForObject(url, String.class);
        return parseOrThrow(resp, "获取成员列表");
    }

    /**
     * 获取部门成员详情列表（递归），含 status 字段。
     *
     * <p><b>企微接口：</b>{@code GET /cgi-bin/user/list?department_id=1&fetch_child=1}
     * 与 {@link #getUserSimplelist()} 的区别：此接口返回每个用户的
     * {@code status}（1=已激活 2=禁用 4=未激活 5=已离职）
     * 和 {@code enable}（1=启用 0=禁用），用于主动过滤不可用员工。</p>
     *
     * @return JsonNode 包含 {@code userlist} 数组，每项含 userid、name、status 等字段
     */
    public JsonNode getUserList() {
        String url = BASE_URL + "/user/list?access_token=" + getAccessToken()
                     + "&department_id=" + rootDepartmentId + "&fetch_child=1";
        String resp = restTemplate.getForObject(url, String.class);
        return parseOrThrow(resp, "获取成员详情列表");
    }

    // ========================================================================
    //  消息推送
    //  文档: https://developer.work.weixin.qq.com/document/path/92123
    // ========================================================================

    /**
     * 向客户发送文本消息（主动推送）。
     * <p>
     * <b>企微接口：</b>{@code POST /cgi-bin/externalcontact/message/send}
     * <pre>
     * 请求:
     *   {
     *     "sender": "zhangsan",              // 发送人（企业成员 userid）
     *     "external_userid": "wmxxx",        // 客户 external_userid
     *     "msgtype": "text",                 // 消息类型
     *     "text": {"content": "您好，很高兴为您服务"}
     *   }
     * 响应:
     *   {"errcode": 0, "errmsg": "ok"}
     * </pre>
     * <b>限制与说明：</b>
     * <ul>
     *   <li>每个客户每天最多接收 1 条主动推送</li>
     *   <li>客户在 48 小时内主动发消息后，推送额度扩展</li>
     *   <li>文本消息长度不超过 2048 字节</li>
     * </ul>
     *
     * @param sender         发送消息的企业成员 userid
     * @param externalUserid 接收消息的客户 external_userid
     * @param text           消息文本内容
     * @throws RuntimeException 发送失败时抛出（如客户已删除员工、被拉黑等）
     */
    public void sendMessage(String sender, String externalUserid, String text) {
        String url = BASE_URL + "/externalcontact/message/send?access_token=" + getAccessToken();
        try {
            Map<String, Object> bodyMap = new java.util.LinkedHashMap<>();
            bodyMap.put("sender", sender);
            bodyMap.put("external_userid", externalUserid);
            bodyMap.put("msgtype", "text");
            bodyMap.put("text", Map.of("content", text));
            String body = objectMapper.writeValueAsString(bodyMap);
            String resp = postForJson(url, body);
            parseOrThrow(resp, "发送消息");
        } catch (Exception e) {
            throw new RuntimeException("发送消息失败: " + e.getMessage(), e);
        }
    }

    // ========================================================================
    //  内部工具方法
    // ========================================================================

    /**
     * POST JSON 字符串到企微 API，强制使用 {@code application/json;charset=UTF-8} 内容类型。
     * <p>解决 {@link RestTemplate} 默认使用 {@code text/plain} 发送 String 正文
     * 导致中文标签名在企微侧显示为乱码的问题。</p>
     */
    private String postForJson(String url, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
        HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);
        return restTemplate.postForObject(url, entity, String.class);
    }

    /**
     * 解析企微 API 响应并校验错误码。
     * <p>
     * 响应被解析为 {@link JsonNode} 后，检查 {@code errcode} 字段：
     * <ul>
     *   <li>{@code errcode == 0 || errcode 不存在} — 视为成功，返回节点</li>
     *   <li>{@code errcode != 0} — 记录错误日志，但<b>不抛异常</b>，
     *       将原始节点返回给调用方，由调用方根据 errcode 做分类处理
     *       （如 {@link WecomErrorCodes#RATE_LIMITED} 触发熔断）</li>
     *   <li>JSON 解析失败 — 直接抛出 {@link RuntimeException}</li>
     * </ul>
     * <p>
     * 这种设计使得调用方可以统一处理企微的错误码，而不是在每个 API 方法中重复 try-catch。
     *
     * @param resp   企微返回的原始 JSON 字符串
     * @param action 当前操作名称（仅用于日志，如 "创建活码"、"打标签"）
     * @return 解析后的 JsonNode 对象
     * @throws RuntimeException JSON 解析失败或网络响应异常时抛出
     */
    private JsonNode parseOrThrow(String resp, String action) {
        try {
            JsonNode node = objectMapper.readTree(resp);
            int code = node.has("errcode") ? node.get("errcode").asInt() : -1;
            if (code != 0) {
                String errmsg = node.has("errmsg") ? node.get("errmsg").asText() : "";
                log.error("{} 失败: errcode={} errmsg={}", action, code, errmsg);
                // 不抛异常，返回原始节点让调用方根据 errcode 做分类处理
            }
            return node;
        } catch (Exception e) {
            log.error("{} 解析响应异常: {}", action, resp, e);
            throw new RuntimeException(action + " 失败: " + resp, e);
        }
    }

    // ========================================================================
    //  网页授权 (OAuth)
    //  文档: https://developer.work.weixin.qq.com/document/path/91023
    // ========================================================================

    /**
     * 通过 OAuth code 获取企微用户身份。
     * <p>
     * <b>企微接口：</b>{@code GET /cgi-bin/user/getuserinfo?access_token=TOKEN&code=CODE}
     * <pre>
     * 响应示例:
     * {
     *   "errcode": 0,
     *   "errmsg": "ok",
     *   "UserId": "zhangsan",
     *   "DeviceId": "xxx"
     * }
     * </pre>
     * <p>注意：企微返回字段首字母大写 {@code UserId}（与通讯录 API 的 {@code userid} 不同）。</p>
     *
     * @param code OAuth 授权临时 code（有效期 5 分钟，仅可使用一次）
     * @return JsonNode 含 errcode + UserId
     * @throws RuntimeException 接口调用失败时抛出
     */
    public JsonNode getUserInfo(String code) {
        String token = getAccessToken();
        String url = BASE_URL + "/user/getuserinfo?access_token=" + token + "&code=" + code;
        try {
            String resp = restTemplate.getForObject(url, String.class);
            JsonNode node = objectMapper.readTree(resp);
            int errcode = node.has("errcode") ? node.get("errcode").asInt() : -1;
            if (errcode != 0) {
                String errmsg = node.has("errmsg") ? node.get("errmsg").asText() : "未知错误";
                log.error("getuserinfo 失败: errcode={}, errmsg={}", errcode, errmsg);
                throw new RuntimeException("获取用户信息失败 [" + errcode + "]: " + errmsg);
            }
            return node;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("getuserinfo 请求异常: " + e.getMessage(), e);
        }
    }

    /**
     * 构造企微网页授权 URL。
     * <p>
     * 文档: https://developer.work.weixin.qq.com/document/path/91022
     * <p>静默授权（snsapi_base）：不弹窗，仅获取 userid，用于企业内部应用。</p>
     *
     * @param redirectUri 回调地址（需已在企微应用设置的可信域名下）
     * @param state       自定义参数（如防 CSRF token），回调时原样返回
     * @return 完整授权 URL
     */
    public String buildOAuthUrl(String redirectUri, String state) {
        return "https://open.weixin.qq.com/connect/oauth2/authorize"
            + "?appid=" + config.getCorpId()
            + "&redirect_uri=" + java.net.URLEncoder.encode(redirectUri, java.nio.charset.StandardCharsets.UTF_8)
            + "&response_type=code"
            + "&scope=snsapi_base"
            + "&state=" + state
            + "#wechat_redirect";
    }
}

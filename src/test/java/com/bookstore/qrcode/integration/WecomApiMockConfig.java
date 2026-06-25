package com.bookstore.qrcode.integration;

import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 集成测试共享的 WeChat Work API Mock 配置。
 *
 * <p>在 {@code @SpringBootTest} 中导入此配置后，Spring 上下文中的
 * {@link WecomApiClient} bean 会被替换为 Mockito mock，
 * 所有企微 HTTP 调用被拦截返回预制 JSON 响应。</p>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * @Import(WecomApiMockConfig.class)
 * class QrCodeCreationFlowTest extends BaseIntegrationTest {
 *     @Autowired private WecomApiClient wecomApi; // 拿到的是 mock
 * }
 * }</pre>
 *
 * <h3>扩展 Mock 行为</h3>
 * <p>个别测试需要特殊返回值时，直接在测试方法中用
 * {@code when(wecomApi.someMethod(...)).thenReturn(...)} 覆盖即可。
 * Mockito 的后续 stubbing 自动覆盖之前的。</p>
 *
 * @author Bookstore Dev
 * @since 2026-06-21
 */
@TestConfiguration
public class WecomApiMockConfig {

    // 递增计数器生成唯一的 config_id
    private static int configIdCounter = 0;

    @Bean
    @Primary
    public WecomApiClient mockWecomApiClient(ObjectMapper objectMapper) {
        WecomApiClient mock = mock(WecomApiClient.class);

        // ---- 活码（联系我）API ----

        // createContactWay: 返回唯一的 config_id + qr_code URL
        try {
            int id = ++configIdCounter;
            JsonNode createResp = objectMapper.readTree(
                    "{\"errcode\":0,\"errmsg\":\"ok\"," +
                    "\"config_id\":\"mock-config-" + id + "\"," +
                    "\"qr_code\":\"https://wecom-mock/qrcode/" + id + "\"}");
            when(mock.createContactWay(anyString())).thenReturn(createResp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // updateContactWay: 更新成功 (2 个重载)
        try {
            JsonNode updateResp = objectMapper.readTree(
                    "{\"errcode\":0,\"errmsg\":\"ok\"}");
            when(mock.updateContactWay(anyString())).thenReturn(updateResp);
            when(mock.updateContactWay(anyString(), anyList())).thenReturn(updateResp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // getContactWay: 返回活码当前的 userid 列表
        try {
            JsonNode getResp = objectMapper.readTree(
                    "{\"errcode\":0,\"errmsg\":\"ok\"," +
                    "\"contact_way\":{\"config_id\":\"mock-config-1\"," +
                    "\"type\":2,\"scene\":2,\"user\":[\"agent1\",\"agent2\"]}}");
            when(mock.getContactWay(anyString())).thenReturn(getResp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // deleteContactWay: 删除成功 (void 方法)
        // doNothing().when(mock).deleteContactWay(anyString()); — mock 默认就是 doNothing

        // ---- 客户 API ----

        // getExternalContact: 返回客户详情
        try {
            JsonNode contactResp = objectMapper.readTree(
                    "{\"errcode\":0,\"errmsg\":\"ok\"," +
                    "\"external_contact\":{" +
                    "\"external_userid\":\"wm-mock-001\"," +
                    "\"name\":\"Mock Customer\"," +
                    "\"avatar\":\"https://wecom-mock/avatar.jpg\"," +
                    "\"type\":1,\"gender\":1,\"unionid\":\"mock-unionid\"}," +
                    "\"follow_user\":[{\"userid\":\"agent1\",\"remark\":\"\",\"add_time\":1600000000}]}}");
            when(mock.getExternalContact(anyString())).thenReturn(contactResp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // ---- 通讯录 API ----

        // getUserSimplelist: 返回部门成员列表（用于下拉框）
        try {
            JsonNode userlistResp = objectMapper.readTree(
                    "{\"errcode\":0,\"errmsg\":\"ok\"," +
                    "\"userlist\":[" +
                    "{\"userid\":\"agent1\",\"name\":\"Agent One\",\"department\":[1]}," +
                    "{\"userid\":\"agent2\",\"name\":\"Agent Two\",\"department\":[1]}," +
                    "{\"userid\":\"agent3\",\"name\":\"Agent Three\",\"department\":[1]}," +
                    "{\"userid\":\"agent4\",\"name\":\"Agent Four\",\"department\":[1]}," +
                    "{\"userid\":\"agent5\",\"name\":\"Agent Five\",\"department\":[1]}" +
                    "]}");
            when(mock.getUserSimplelist()).thenReturn(userlistResp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // getUserList: 返回部门成员详情（含 status/mobile）
        try {
            JsonNode userlistDetailResp = objectMapper.readTree(
                    "{\"errcode\":0,\"errmsg\":\"ok\"," +
                    "\"userlist\":[" +
                    "{\"userid\":\"agent1\",\"name\":\"Agent One\",\"department\":[1],\"status\":1,\"mobile\":\"13800000001\"}," +
                    "{\"userid\":\"agent2\",\"name\":\"Agent Two\",\"department\":[1],\"status\":1,\"mobile\":\"13800000002\"}," +
                    "{\"userid\":\"agent3\",\"name\":\"Agent Three\",\"department\":[1],\"status\":1,\"mobile\":\"13800000003\"}," +
                    "{\"userid\":\"agent4\",\"name\":\"Agent Four\",\"department\":[1],\"status\":1,\"mobile\":\"13800000004\"}," +
                    "{\"userid\":\"agent5\",\"name\":\"Agent Five\",\"department\":[1],\"status\":1,\"mobile\":\"13800000005\"}" +
                    "]}");
            when(mock.getUserList()).thenReturn(userlistDetailResp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // ---- 标签 API ----

        // getCorpTagList: 返回企业标签组
        try {
            JsonNode tagResp = objectMapper.readTree(
                    "{\"errcode\":0,\"errmsg\":\"ok\"," +
                    "\"tag_group\":[{\"group_id\":\"g1\",\"group_name\":\"年级\"," +
                    "\"tag\":[{\"id\":\"t1\",\"name\":\"一年级\"}]}]}");
            when(mock.getCorpTagList()).thenReturn(tagResp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // markTag: 打标签成功 (void 方法)

        // ---- 在职继承 API ----

        // transferCustomer: 发起转移成功
        try {
            JsonNode transferResp = objectMapper.readTree(
                    "{\"errcode\":0,\"errmsg\":\"ok\"," +
                    "\"customer\":[{\"external_userid\":\"wm-mock-001\",\"errcode\":0}]}");
            when(mock.transferCustomer(anyString(), anyString(), anyString()))
                    .thenReturn(transferResp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // getTransferResult: 查询转移结果
        try {
            JsonNode resultResp = objectMapper.readTree(
                    "{\"errcode\":0,\"errmsg\":\"ok\"," +
                    "\"customer\":[{\"external_userid\":\"wm-mock-001\",\"status\":3}]}");
            when(mock.getTransferResult(anyString(), anyString(), anyString()))
                    .thenReturn(resultResp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return mock;
    }

    /**
     * 对已有的 mock 重新应用基础 stubs。
     *
     * <p>用于测试间 {@code reset(mock)} 后恢复默认返回值，
     * 避免每个测试类各自维护 stub 副本。</p>
     */
    public static void reapplyBaseStubs(WecomApiClient mock) {
        ObjectMapper om = new ObjectMapper();
        try {
            // createContactWay
            int id = ++configIdCounter;
            when(mock.createContactWay(anyString()))
                .thenReturn(om.readTree(
                    "{\"errcode\":0,\"errmsg\":\"ok\"," +
                    "\"config_id\":\"mock-config-" + id + "\"," +
                    "\"qr_code\":\"https://wecom-mock/qrcode/" + id + "\"}"));

            // updateContactWay
            JsonNode updateOk = om.readTree("{\"errcode\":0,\"errmsg\":\"ok\"}");
            when(mock.updateContactWay(anyString())).thenReturn(updateOk);
            when(mock.updateContactWay(anyString(), anyList())).thenReturn(updateOk);

            // getContactWay
            when(mock.getContactWay(anyString())).thenReturn(om.readTree(
                "{\"errcode\":0,\"errmsg\":\"ok\"," +
                "\"contact_way\":{\"config_id\":\"mock-config-1\"," +
                "\"type\":2,\"scene\":2,\"user\":[\"agent1\",\"agent2\"]}}"));

            // getExternalContact
            when(mock.getExternalContact(anyString())).thenReturn(om.readTree(
                "{\"errcode\":0,\"errmsg\":\"ok\"," +
                "\"external_contact\":{" +
                "\"external_userid\":\"wm-mock-001\"," +
                "\"name\":\"Mock Customer\"," +
                "\"avatar\":\"https://wecom-mock/avatar.jpg\"," +
                "\"type\":1,\"gender\":1,\"unionid\":\"mock-unionid\"}," +
                "\"follow_user\":[{\"userid\":\"agent1\",\"remark\":\"\",\"add_time\":1600000000}]}}"));

            // getUserSimplelist
            when(mock.getUserSimplelist()).thenReturn(om.readTree(
                "{\"errcode\":0,\"errmsg\":\"ok\"," +
                "\"userlist\":[" +
                "{\"userid\":\"agent1\",\"name\":\"Agent One\",\"department\":[1]}," +
                "{\"userid\":\"agent2\",\"name\":\"Agent Two\",\"department\":[1]}," +
                "{\"userid\":\"agent3\",\"name\":\"Agent Three\",\"department\":[1]}," +
                "{\"userid\":\"agent4\",\"name\":\"Agent Four\",\"department\":[1]}," +
                "{\"userid\":\"agent5\",\"name\":\"Agent Five\",\"department\":[1]}" +
                "]}"));

            // getUserList
            when(mock.getUserList()).thenReturn(om.readTree(
                "{\"errcode\":0,\"errmsg\":\"ok\"," +
                "\"userlist\":[" +
                "{\"userid\":\"agent1\",\"name\":\"Agent One\",\"department\":[1],\"status\":1,\"mobile\":\"13800000001\"}," +
                "{\"userid\":\"agent2\",\"name\":\"Agent Two\",\"department\":[1],\"status\":1,\"mobile\":\"13800000002\"}," +
                "{\"userid\":\"agent3\",\"name\":\"Agent Three\",\"department\":[1],\"status\":1,\"mobile\":\"13800000003\"}," +
                "{\"userid\":\"agent4\",\"name\":\"Agent Four\",\"department\":[1],\"status\":1,\"mobile\":\"13800000004\"}," +
                "{\"userid\":\"agent5\",\"name\":\"Agent Five\",\"department\":[1],\"status\":1,\"mobile\":\"13800000005\"}" +
                "]}"));

            // getCorpTagList
            when(mock.getCorpTagList()).thenReturn(om.readTree(
                "{\"errcode\":0,\"errmsg\":\"ok\"," +
                "\"tag_group\":[{\"group_id\":\"g1\",\"group_name\":\"年级\"," +
                "\"tag\":[{\"id\":\"t1\",\"name\":\"一年级\"}]}]}"));

            // transferCustomer
            when(mock.transferCustomer(anyString(), anyString(), anyString()))
                .thenReturn(om.readTree(
                    "{\"errcode\":0,\"errmsg\":\"ok\"," +
                    "\"customer\":[{\"external_userid\":\"wm-mock-001\",\"errcode\":0}]}"));

            // getTransferResult
            when(mock.getTransferResult(anyString(), anyString(), anyString()))
                .thenReturn(om.readTree(
                    "{\"errcode\":0,\"errmsg\":\"ok\"," +
                    "\"customer\":[{\"external_userid\":\"wm-mock-001\",\"status\":3}]}"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

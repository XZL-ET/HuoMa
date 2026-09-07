package com.bookstore.qrcode.wecom;

import com.bookstore.qrcode.config.WecomConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 验证 getTransferResult 调用的是新接口 {@code transfer_result}，
 * 而非已废弃的 {@code get_transfer_result}，且请求体不再携带
 * {@code external_userid}（新接口按 handover/takeover 维度返回 customer 数组，
 * 由 {@code findCustomerStatus} 在响应中按 external_userid 过滤）。
 */
class WecomApiClientTransferResultTest {

    private WecomConfig config;
    private WecomApiClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() throws Exception {
        config = new WecomConfig();
        config.setCorpId("test_corp");
        config.setCorpSecret("test_secret");
        // 预置未过期的 access_token，避免 gettoken 触发额外的 HTTP 调用
        config.setAccessToken("test_token");
        config.setAccessTokenExpireAt(Instant.now().getEpochSecond() + 300);

        client = createClient(config);

        // 读取客户端内部 restTemplate 并绑定 mock，使 postForJson 走 mock 而非真实网络
        Field rtField = WecomApiClient.class.getDeclaredField("restTemplate");
        rtField.setAccessible(true);
        RestTemplate restTemplate = (RestTemplate) rtField.get(client);
        server = MockRestServiceServer.createServer(restTemplate);
    }

    @Test
    @DisplayName("getTransferResult 调用新接口 transfer_result 且请求体不含 external_userid")
    void shouldCallNewTransferResultEndpointWithoutExternalUserid() {
        server.expect(requestTo(
                "https://qyapi.weixin.qq.com/cgi-bin/externalcontact/transfer_result?access_token=test_token"))
            .andExpect(content().string(allOf(
                containsString("\"handover_userid\":\"h1\""),
                containsString("\"takeover_userid\":\"t1\""),
                not(containsString("external_userid"))
            )))
            .andRespond(withSuccess(
                "{\"errcode\":0,\"errmsg\":\"ok\",\"customer\":[{\"external_userid\":\"wm-1\",\"status\":1}]}",
                MediaType.APPLICATION_JSON));

        JsonNode result = client.getTransferResult("h1", "t1");

        assertNotNull(result);
        assertEquals(1, result.get("customer").get(0).get("status").asInt());
        server.verify();
    }

    @Test
    @DisplayName("带 cursor 时请求体应包含 cursor 分页参数且不含 external_userid")
    void shouldIncludeCursorWhenProvided() {
        server.expect(requestTo(
                "https://qyapi.weixin.qq.com/cgi-bin/externalcontact/transfer_result?access_token=test_token"))
            .andExpect(content().string(allOf(
                containsString("\"cursor\":\"abc123\""),
                not(containsString("external_userid"))
            )))
            .andRespond(withSuccess(
                "{\"errcode\":0,\"errmsg\":\"ok\",\"customer\":[],\"next_cursor\":\"\"}",
                MediaType.APPLICATION_JSON));

        JsonNode result = client.getTransferResult("h1", "t1", "abc123");

        assertNotNull(result);
        server.verify();
    }

    private WecomApiClient createClient(WecomConfig c) throws Exception {
        java.lang.reflect.Constructor<WecomApiClient> ctor =
            WecomApiClient.class.getDeclaredConstructor(
                WecomConfig.class, int.class, int.class, RestTemplateBuilder.class, ObjectMapper.class);
        ctor.setAccessible(true);
        return ctor.newInstance(c, 3, 10, new RestTemplateBuilder(), new ObjectMapper());
    }
}

package com.bookstore.qrcode.wecom;

import com.bookstore.qrcode.config.WecomConfig;
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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 验证删除日报走独立的 report 自建应用（1000014）推送：
 * {@code sendReportMessage} 使用 report 应用的 access_token 与 agentId，
 * 与主应用（1000002）的 {@code sendAppMessage} 分离。
 */
class WecomApiClientReportMessageTest {

    private WecomConfig config;
    private WecomApiClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() throws Exception {
        config = new WecomConfig();
        config.setCorpId("test_corp");
        config.setCorpSecret("test_secret");
        config.setAgentId(1000002);
        // 主应用 token
        config.setAccessToken("main_token");
        config.setAccessTokenExpireAt(Instant.now().getEpochSecond() + 300);
        // report 应用
        config.setReportAgentId(1000014);
        config.setReportCorpSecret("report_secret");
        config.setReportAccessToken("report_token");
        config.setReportAccessTokenExpireAt(Instant.now().getEpochSecond() + 300);

        client = createClient(config);

        Field rtField = WecomApiClient.class.getDeclaredField("restTemplate");
        rtField.setAccessible(true);
        RestTemplate restTemplate = (RestTemplate) rtField.get(client);
        server = MockRestServiceServer.createServer(restTemplate);
    }

    @Test
    @DisplayName("sendReportMessage 用 report token 与 report agentId 发送")
    void shouldSendWithReportTokenAndAgentId() {
        server.expect(requestTo(
                "https://qyapi.weixin.qq.com/cgi-bin/message/send?access_token=report_token"))
            .andExpect(content().string(allOf(
                containsString("\"agentid\":1000014"),
                containsString("\"touser\":\"admin1\""),
                not(containsString("\"agentid\":1000002"))
            )))
            .andRespond(withSuccess("{\"errcode\":0,\"errmsg\":\"ok\"}", MediaType.APPLICATION_JSON));

        client.sendReportMessage("admin1", "日报内容");

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

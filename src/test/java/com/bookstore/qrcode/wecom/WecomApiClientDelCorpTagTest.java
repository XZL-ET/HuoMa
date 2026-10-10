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
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WecomApiClientDelCorpTagTest {

    private WecomApiClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() throws Exception {
        client = createClient();
        Field rtField = WecomApiClient.class.getDeclaredField("restTemplate");
        rtField.setAccessible(true);
        server = MockRestServiceServer.createServer((RestTemplate) rtField.get(client));
    }

    /** 与 WecomApiClientTransferResultTest#createClient 同款：预置未过期 token，避免额外的 gettoken 调用。 */
    private WecomApiClient createClient() throws Exception {
        WecomConfig config = new WecomConfig();
        config.setCorpId("test_corp");
        config.setCorpSecret("test_secret");
        config.setAccessToken("test_token");
        config.setAccessTokenExpireAt(Instant.now().getEpochSecond() + 300);

        java.lang.reflect.Constructor<WecomApiClient> ctor =
            WecomApiClient.class.getDeclaredConstructor(
                WecomConfig.class, int.class, int.class, RestTemplateBuilder.class, ObjectMapper.class);
        ctor.setAccessible(true);
        return ctor.newInstance(config, 3, 10, new RestTemplateBuilder(), new ObjectMapper());
    }

    @Test
    @DisplayName("delCorpTag 调用 del_corp_tag 且请求体为 tag_id 数组")
    void shouldCallDelCorpTag() {
        server.expect(requestTo(
                "https://qyapi.weixin.qq.com/cgi-bin/externalcontact/del_corp_tag?access_token=test_token"))
            .andExpect(content().string(containsString("\"tag_id\":[\"a\",\"b\"]")))
            .andRespond(withSuccess("{\"errcode\":0,\"errmsg\":\"ok\"}", MediaType.APPLICATION_JSON));

        JsonNode resp = client.delCorpTag(List.of("a", "b"));

        assertEquals(0, resp.get("errcode").asInt());
        server.verify();
    }

    @Test
    @DisplayName("delCorpTag 遇非零 errcode 抛异常")
    void shouldThrowOnError() {
        server.expect(requestTo(
                "https://qyapi.weixin.qq.com/cgi-bin/externalcontact/del_corp_tag?access_token=test_token"))
            .andRespond(withSuccess("{\"errcode\":40058,\"errmsg\":\"invalid tagid\"}",
                MediaType.APPLICATION_JSON));

        assertThrows(WecomApiException.class, () -> client.delCorpTag(List.of("x")));
    }
}

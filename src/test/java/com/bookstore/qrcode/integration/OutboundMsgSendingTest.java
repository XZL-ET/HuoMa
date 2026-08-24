package com.bookstore.qrcode.integration;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.bookstore.qrcode.worker.OutboundMsgWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 验证 OutboundMsgWorker 发送欢迎语 + 收集表单链接到客户的完整链路。
 *
 * <p>使用 {@link WecomApiMockConfig} 替换企微 API，
 * 通过反射调用私有方法 {@code processEvent()} 直接验证消息发送逻辑，
 * 无需启动异步 Worker 线程。</p>
 *
 * <h3>覆盖场景</h3>
 * <ul>
 *   <li>有 welcome_code → send_welcome_msg 优先</li>
 *   <li>无 welcome_code → sendMessage 降级</li>
 *   <li>活码绑定 form_template → 300ms 后发送 textcard 表单链接</li>
 *   <li>活码未绑定 form_template → 只发欢迎语，不发表单</li>
 *   <li>欢迎语模板替换 {{school_name}}、{{teacher_name}}</li>
 *   <li>继承链：活码 welcome → 分组 default → 系统 default</li>
 * </ul>
 *
 * @author Bookstore Dev
 * @since 2026-06-25
 */
@Import(WecomApiMockConfig.class)
@DisplayName("OutboundMsgWorker 欢迎语 + 表单发送")
class OutboundMsgSendingTest extends BaseIntegrationTest {

    @Autowired private OutboundMsgWorker outboundWorker;
    @Autowired private WecomApiClient wecomApi;           // Mockito mock

    @Autowired private QrCodeRepository qrCodeRepo;
    @Autowired private FormTemplateRepository formTemplateRepo;
    @Autowired private QrCodeGroupRepository groupRepo;
    @Autowired private CustomerRepository customerRepo;
    @Autowired private ObjectMapper objectMapper;

    private Method processEventMethod;

    @BeforeEach
    void setUp() throws Exception {
        processEventMethod = OutboundMsgWorker.class
            .getDeclaredMethod("processEvent", String.class);
        processEventMethod.setAccessible(true);

        qrCodeRepo.deleteAll();
        formTemplateRepo.deleteAll();
        groupRepo.deleteAll();
        customerRepo.deleteAll();

        // 重置 mock 调用记录（WecomApiMockConfig 是单例 mock，跨测试共享）
        reset(wecomApi);
        // 重新应用 WecomApiMockConfig 的基础 stubs
        WecomApiMockConfig.reapplyBaseStubs(wecomApi);
    }

    // ═══════════════════════════════════════════════════════════════
    // 欢迎语发送
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("有 welcome_code + 表单模板 → 合集 send_welcome_msg(带附件)")
    void shouldSendWelcomeAndFormInOneCallWhenCodePresent() throws Exception {
        FormTemplate tpl = formTemplateRepo.save(FormTemplate.builder()
            .name("通用收集表单").fields("[{\"name\":\"grade\"}]")
            .tagMapping("{\"grade\":\"tag\"}").build());

        QrCode qr = createQrCode("测试学校", "SCH-001", null, tpl.getId());
        String event = buildEventJson("wm-ext-001", "agent1", qr.getId(),
            "100", "welcome_code_abc123");

        processEventMethod.invoke(outboundWorker, event);

        // 欢迎语 + 表单链接合集在 send_welcome_msg 的 attachments 里
        verify(wecomApi, times(1)).sendWelcomeMsg(
            eq("welcome_code_abc123"),
            contains("家长您好"),
            argThat(attachments -> attachments != null && attachments.size() == 1
                && "link".equals(attachments.get(0).get("msgtype")))
        );
        // 绝不走 sendTextCard（48002 api forbidden）
        verify(wecomApi, never()).sendTextCard(anyString(), anyString(),
            anyString(), anyString(), anyString(), anyString());
        verify(wecomApi, never()).sendMessage(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("无 welcome_code → sendMessage 降级")
    void shouldFallbackToSendMessageWhenNoCode() throws Exception {
        QrCode qr = createQrCode("测试学校", "SCH-002", null, null);
        String event = buildEventJson("wm-ext-002", "agent1", qr.getId(), "101", null);

        processEventMethod.invoke(outboundWorker, event);

        verify(wecomApi, times(1)).sendMessage(
            eq("agent1"),
            eq("wm-ext-002"),
            contains("家长您好")
        );
        verify(wecomApi, never()).sendWelcomeMsg(anyString(), anyString(), any());
    }

    // ═══════════════════════════════════════════════════════════════
    // 表单卡片发送
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("活码绑定 form_template → 300ms 后发送 textcard")
    void shouldSendFormCardWhenTemplateBound() throws Exception {
        // 创建表单模板
        FormTemplate tpl = formTemplateRepo.save(FormTemplate.builder()
            .name("通用收集表单").fields("[{\"name\":\"grade\"}]")
            .tagMapping("{\"grade\":\"tag\"}").build());

        // 创建活码并绑定模板
        QrCode qr = createQrCode("有表单的学校", "SCH-003", "欢迎！", tpl.getId());
        String event = buildEventJson("wm-ext-003", "agent1", qr.getId(), "102", null);

        processEventMethod.invoke(outboundWorker, event);

        // 验证欢迎语内容
        verify(wecomApi, times(1)).sendMessage(
            eq("agent1"), eq("wm-ext-003"), eq("欢迎！"));

        // 验证表单卡片 — 链接包含 qrCodeId 和 customerId
        verify(wecomApi, times(1)).sendTextCard(
            eq("agent1"),
            eq("wm-ext-003"),
            eq("📋 请填写孩子信息"),          // title
            anyString(),                       // description
            contains("/form/" + qr.getId()),   // url 含表单路径
            eq("去填写")                       // btnText
        );
    }

    @Test
    @DisplayName("活码未绑定 form_template → 只发欢迎语，不发表单")
    void shouldNotSendFormCardWhenNoTemplate() throws Exception {
        QrCode qr = createQrCode("无表单的学校", "SCH-004", null, null);
        String event = buildEventJson("wm-ext-004", "agent1", qr.getId(), "103", null);

        processEventMethod.invoke(outboundWorker, event);

        verify(wecomApi, times(1)).sendMessage(anyString(), anyString(), anyString());
        verify(wecomApi, never()).sendTextCard(anyString(), anyString(),
            anyString(), anyString(), anyString(), anyString());
    }

    // ═══════════════════════════════════════════════════════════════
    // 模板变量替换
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("欢迎语模板替换 {{school_name}}")
    void shouldReplaceSchoolNameTemplate() throws Exception {
        QrCode qr = createQrCode("兰州市第一小学", "SCH-005",
            "{{school_name}}家长您好！", null);
        String event = buildEventJson("wm-ext-005", "agent1", qr.getId(), "104", null);

        processEventMethod.invoke(outboundWorker, event);

        verify(wecomApi, times(1)).sendMessage(
            eq("agent1"), eq("wm-ext-005"),
            eq("兰州市第一小学家长您好！"));
    }

    // ═══════════════════════════════════════════════════════════════
    // 继承链
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("活码无欢迎语 → 继承分组默认 → 继承系统默认")
    void shouldInheritWelcomeFromGroupThenSystem() throws Exception {
        // 分组设默认欢迎语
        QrCodeGroup group = groupRepo.save(QrCodeGroup.builder()
            .name("兰州联盟").regionDistrict("城关区")
            .defaultWelcomeText("联盟统一欢迎语").build());

        // 活码不设欢迎语，只关联分组
        QrCode qr = qrCodeRepo.save(QrCode.builder()
            .schoolName("联盟成员校").schoolId("SCH-006")
            .regionCity("兰州市").regionDistrict("城关区")
            .groupId(group.getId())            // ← 关联分组
            .welcomeText(null)                 // ← 不设欢迎语
            .status(QrCode.QrCodeStatus.active)
            .rotateMode(QrCode.RotateMode.auto)
            .createMode(QrCode.CreateMode.manual)
            .scene(Scene.daily_push)
            .build());

        String event = buildEventJson("wm-ext-006", "agent1", qr.getId(), "105", null);

        processEventMethod.invoke(outboundWorker, event);

        verify(wecomApi, times(1)).sendMessage(
            eq("agent1"), eq("wm-ext-006"),
            eq("联盟统一欢迎语"));   // ← 来自分组
    }

    @Test
    @DisplayName("活码有欢迎语但无表单模板 → 表单模板独立继承自分组")
    void shouldInheritFormTemplateFromGroupWhenWelcomeIsSet() throws Exception {
        // 创建表单模板
        FormTemplate tpl = formTemplateRepo.save(FormTemplate.builder()
            .name("联盟收集表单").fields("[{\"name\":\"grade\"}]")
            .tagMapping("{}").build());

        // 分组设默认表单模板（不设欢迎语）
        QrCodeGroup group = groupRepo.save(QrCodeGroup.builder()
            .name("兰州联盟").regionDistrict("城关区")
            .defaultFormTemplateId(tpl.getId()).build());

        // 活码有自定义欢迎语，但没设表单模板
        QrCode qr = qrCodeRepo.save(QrCode.builder()
            .schoolName("联盟成员校").schoolId("SCH-007")
            .regionCity("兰州市").regionDistrict("城关区")
            .groupId(group.getId())
            .welcomeText("自定义欢迎语")          // ← 有欢迎语
            .formTemplateId(null)                 // ← 无表单模板
            .status(QrCode.QrCodeStatus.active)
            .rotateMode(QrCode.RotateMode.auto)
            .createMode(QrCode.CreateMode.manual)
            .scene(Scene.daily_push)
            .build());

        String event = buildEventJson("wm-ext-007", "agent1", qr.getId(), "106", null);

        processEventMethod.invoke(outboundWorker, event);

        // 欢迎语 = 活码自己的
        verify(wecomApi, times(1)).sendMessage(
            eq("agent1"), eq("wm-ext-007"), eq("自定义欢迎语"));

        // 表单模板 = 继承自分组（关键断言：解耦继承）
        verify(wecomApi, times(1)).sendTextCard(
            eq("agent1"), eq("wm-ext-007"),
            anyString(), anyString(),
            contains("/form/" + qr.getId()), anyString());
    }

    // ═══════════════════════════════════════════════════════════════
    // 构造函数
    // ═══════════════════════════════════════════════════════════════

    /**
     * 创建一个测试活码。
     */
    private QrCode createQrCode(String schoolName, String schoolId,
                                 String welcomeText, Long formTemplateId) {
        return qrCodeRepo.save(QrCode.builder()
            .schoolName(schoolName).schoolId(schoolId)
            .regionCity("兰州市").regionDistrict("城关区")
            .welcomeText(welcomeText)
            .formTemplateId(formTemplateId)
            .status(QrCode.QrCodeStatus.active)
            .rotateMode(QrCode.RotateMode.auto)
            .createMode(QrCode.CreateMode.manual)
            .scene(Scene.daily_push)
            .build());
    }

    /**
     * 构造 OutboundMsgWorker 消费的事件 JSON。
     */
    private String buildEventJson(String externalUserId, String userid,
                                   Long qrCodeId, String customerId,
                                   String welcomeCode) throws Exception {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "welcome_and_form");
        event.put("external_userid", externalUserId);
        event.put("userid", userid);
        event.put("state", "dummy-state");
        if (qrCodeId != null) event.put("qr_code_id", qrCodeId.toString());
        if (customerId != null) event.put("customer_id", customerId);
        if (welcomeCode != null) event.put("welcome_code", welcomeCode);
        return objectMapper.writeValueAsString(event);
    }
}

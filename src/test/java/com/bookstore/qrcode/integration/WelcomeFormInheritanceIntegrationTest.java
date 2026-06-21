package com.bookstore.qrcode.integration;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.service.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.*;

/**
 * Task 20: FormTemplate + QrCodeGroup CRUD 集成测试。
 *
 * <p>验证 {@link FormTemplateService} 和 {@link QrCodeGroupService} 的完整
 * 增删改查链路，确认 Spring 上下文、H2 数据库、Embedded Redis 均正常工作。</p>
 *
 * @author Bookstore Dev
 * @since 2026-06-21
 */
@DisplayName("表单模板 + 活码分组 CRUD 集成测试")
class WelcomeFormInheritanceIntegrationTest extends BaseIntegrationTest {

    @Autowired private FormTemplateService formTemplateService;
    @Autowired private FormTemplateRepository formTemplateRepo;
    @Autowired private QrCodeGroupService groupService;
    @Autowired private QrCodeGroupRepository groupRepo;

    // ================================================================
    // FormTemplate CRUD
    // ================================================================

    @Test
    @DisplayName("表单模板 增 → 查 → 改 → 删 全链路")
    void formTemplateCrud() {
        // 创建
        FormTemplate t = formTemplateService.create(
            "测试模板", "测试",
            "[{\"name\":\"test\",\"label\":\"测试\",\"type\":\"text\"}]",
            "{\"test\":\"tag\"}", "{{test}}");
        assertThat(t.getId()).isNotNull();

        // 查询
        FormTemplate found = formTemplateService.getById(t.getId());
        assertThat(found.getName()).isEqualTo("测试模板");

        // 更新
        formTemplateService.update(t.getId(), "更新后", null, null, null, null);
        assertThat(formTemplateService.getById(t.getId()).getName()).isEqualTo("更新后");

        // 删除
        formTemplateService.delete(t.getId());
        assertThat(formTemplateRepo.findById(t.getId())).isEmpty();
    }

    // ================================================================
    // QrCodeGroup CRUD
    // ================================================================

    @Test
    @DisplayName("活码分组 增 → 删 全链路")
    void qrCodeGroupCrud() {
        QrCodeGroup g = groupService.create("测试联盟", "兰州市", "城关区", "欢迎语", null, null, null);
        assertThat(g.getId()).isNotNull();
        groupService.delete(g.getId());
    }
}

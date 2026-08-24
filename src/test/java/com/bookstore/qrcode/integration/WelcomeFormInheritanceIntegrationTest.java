package com.bookstore.qrcode.integration;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.service.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.*;

/**
 * 表单模板 + 活码分组 + 学校分类 CRUD 及继承链集成测试。
 *
 * <p>验证 {@link FormTemplateService}、{@link QrCodeGroupService} 和
 * {@link SchoolCategoryService} 的完整增删改查链路，
 * 确认 Spring 上下文、H2 数据库、Embedded Redis 均正常工作。</p>
 *
 * @author Bookstore Dev
 * @since 2026-06-21
 */
@DisplayName("表单模板 + 活码分组 + 学校分类 集成测试")
class WelcomeFormInheritanceIntegrationTest extends BaseIntegrationTest {

    @Autowired private FormTemplateService formTemplateService;
    @Autowired private FormTemplateRepository formTemplateRepo;
    @Autowired private QrCodeGroupService groupService;
    @Autowired private QrCodeGroupRepository groupRepo;
    @Autowired private SchoolCategoryService categoryService;
    @Autowired private SchoolCategoryRepository categoryRepo;

    // ================================================================
    // FormTemplate CRUD
    // ================================================================

    @Test
    @DisplayName("表单模板 增 → 查 → 改 → 删 全链路")
    void formTemplateCrud() {
        // 创建
        FormTemplate t = formTemplateService.create(
            "测试模板", "测试", "表单副标题", null, null, null,
            "[{\"name\":\"test\",\"label\":\"测试\",\"type\":\"text\"}]",
            "{\"test\":\"tag\"}", "{{test}}");
        assertThat(t.getId()).isNotNull();

        // 查询
        FormTemplate found = formTemplateService.getById(t.getId());
        assertThat(found.getName()).isEqualTo("测试模板");

        // 更新
        formTemplateService.update(t.getId(), "更新后", null, null, null, null, null, null, null, null);
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

    // ================================================================
    // SchoolCategory CRUD
    // ================================================================

    @Test
    @DisplayName("学校分类 增 → 查 → 改 → 删 全链路")
    void schoolCategoryCrud() {
        // 创建
        SchoolCategory cat = categoryService.create("重点高中", 1, "欢迎语", null);
        assertThat(cat.getId()).isNotNull();
        assertThat(cat.getName()).isEqualTo("重点高中");

        // 查询
        SchoolCategory found = categoryService.getById(cat.getId());
        assertThat(found.getDefaultWelcomeText()).isEqualTo("欢迎语");

        // 更新
        categoryService.update(cat.getId(), "普通高中", 2, "新欢迎语", null);
        assertThat(categoryService.getById(cat.getId()).getName()).isEqualTo("普通高中");

        // 删除
        int unlinked = categoryService.delete(cat.getId());
        assertThat(unlinked).isEqualTo(0);
        assertThat(categoryRepo.findById(cat.getId())).isEmpty();
    }

    @Test
    @DisplayName("学校分类 名称重复检查")
    void schoolCategoryDuplicateName() {
        categoryService.create("初中", 1, null, null);
        assertThatThrownBy(() -> categoryService.create("初中", 2, null, null))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("已存在");
        // cleanup
        categoryRepo.findByName("初中").ifPresent(c -> categoryRepo.deleteById(c.getId()));
    }

    @Test
    @DisplayName("学校分类 保护默认分类不可删除")
    void schoolCategoryDefaultProtected() {
        // 默认"未分类"由 migration 插入
        categoryRepo.findByName("未分类").ifPresentOrElse(
            uncategorized -> {
                assertThatThrownBy(() -> categoryService.delete(uncategorized.getId()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("不可删除");
            },
            () -> fail("默认分类「未分类」未找到")
        );
    }
}

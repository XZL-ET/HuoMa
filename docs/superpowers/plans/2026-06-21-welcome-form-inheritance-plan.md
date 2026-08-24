# 欢迎语 · 收集表单 · 在职继承 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build welcome message sending, customizable H5 collection forms with auto-tagging/remark, and on-duty customer inheritance (manual + scheduled) — all in the existing Redis Stream async worker pattern.

**Architecture:** New entities follow existing no-JPA-relationship pattern. Welcome/form sending and inheritance use new Stream consumers (OutboundMsgWorker, TransferWorker) following the CallbackWorker/TagWorker pattern. TagWorker extended for form-submit events. Existing callback flow unchanged except one XADD at end of handleAddSuccess().

**Tech Stack:** Spring Boot 3.x + JPA/Hibernate + Thymeleaf + Redis Streams + WeChat Work API

## Global Constraints

- All entities use explicit FK ID fields, no JPA `@ManyToOne`/`@OneToMany`
- All Workers follow Redis Stream Consumer Group pattern with retry/DLQ
- WeChat API: 300ms between welcome+form messages, 50ms between tags, 200ms between transfers
- New qr_code columns are nullable DEFAULT NULL (no impact on existing rows)
- Template files in `src/main/resources/templates/`, Thymeleaf layout system

---

## Phase 1: Foundation

### Task 1: DB Migration + WecomApiClient.updateRemark

**Files:**
- Create: `src/main/resources/db/migration/V2__welcome_form_inheritance.sql`
- Modify: `src/main/java/com/bookstore/qrcode/wecom/WecomApiClient.java`

**Produces:** `WecomApiClient.updateRemark(String userId, String externalUserid, String remark)`

- [ ] **Step 1: Write migration SQL**

```sql
CREATE TABLE IF NOT EXISTS form_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    fields JSON NOT NULL,
    tag_mapping JSON NOT NULL,
    remark_template VARCHAR(500),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS form_submission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    form_template_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    qr_code_id BIGINT,
    field_data JSON NOT NULL,
    tags_applied VARCHAR(500),
    remark_updated VARCHAR(500),
    submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_fs_customer (customer_id),
    INDEX idx_fs_template (form_template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS qr_code_group (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    region_city VARCHAR(50),
    region_district VARCHAR(50) NOT NULL,
    group_type VARCHAR(20) NOT NULL DEFAULT 'alliance',
    default_welcome_text VARCHAR(500),
    default_form_template_id BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE qr_code
    ADD COLUMN form_template_id BIGINT NULL,
    ADD COLUMN welcome_text VARCHAR(500) NULL,
    ADD COLUMN group_id BIGINT NULL;

INSERT IGNORE INTO system_config (config_key, config_value) VALUES
('default_welcome_text', '{{school_name}}家长您好～欢迎加入XX书店家校服务！');
```

- [ ] **Step 2: Add updateRemark() to WecomApiClient**

In `WecomApiClient.java`, append after the `sendMessage()` method and before `// ==== 内部工具方法 ====`:

```java
/**
 * 修改客户备注。
 * POST /cgi-bin/externalcontact/remark
 */
public void updateRemark(String userId, String externalUserid, String remark) {
    String url = BASE_URL + "/externalcontact/remark?access_token=" + getAccessToken();
    try {
        Map<String, Object> bodyMap = new java.util.LinkedHashMap<>();
        bodyMap.put("userid", userId);
        bodyMap.put("external_userid", externalUserid);
        bodyMap.put("remark", remark != null ? remark : "");
        String body = objectMapper.writeValueAsString(bodyMap);
        String resp = postForJson(url, body);
        parseAndCheck(resp, "修改备注");
    } catch (WecomApiException e) {
        throw e;
    } catch (Exception e) {
        throw new WecomTransientException(-1,
            "修改备注失败: " + e.getMessage(), null);
    }
}
```

- [ ] **Step 3: Run migration and compile check**

```bash
cd D:/ClaudeCode/HuoMa && mvn compile -q
```

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/db/migration/V2__welcome_form_inheritance.sql src/main/java/com/bookstore/qrcode/wecom/WecomApiClient.java
git commit -m "feat: add form_template/form_submission/qr_code_group tables + WecomApiClient.updateRemark"
```

---

### Task 2: FormTemplate Entity + Repository + Service

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/entity/FormTemplate.java`
- Create: `src/main/java/com/bookstore/qrcode/repository/FormTemplateRepository.java`
- Create: `src/main/java/com/bookstore/qrcode/service/FormTemplateService.java`

- [ ] **Step 1: Write FormTemplate entity**

```java
package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "form_template")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FormTemplate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "fields", columnDefinition = "JSON", nullable = false)
    private String fields;

    @Column(name = "tag_mapping", columnDefinition = "JSON", nullable = false)
    private String tagMapping;

    @Column(name = "remark_template", length = 500)
    private String remarkTemplate;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() { createdAt = updatedAt = LocalDateTime.now(); }

    @PreUpdate
    void preUpdate() { updatedAt = LocalDateTime.now(); }
}
```

- [ ] **Step 2: Write FormTemplateRepository**

```java
package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.FormTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface FormTemplateRepository extends JpaRepository<FormTemplate, Long> {
    List<FormTemplate> findAllByOrderByName();
}
```

- [ ] **Step 3: Write FormTemplateService**

```java
package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.FormTemplate;
import com.bookstore.qrcode.repository.FormTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FormTemplateService {

    private final FormTemplateRepository templateRepo;

    public List<FormTemplate> listAll() {
        return templateRepo.findAllByOrderByName();
    }

    public FormTemplate getById(Long id) {
        return templateRepo.findById(id)
            .orElseThrow(() -> new RuntimeException("表单模板不存在: " + id));
    }

    @Transactional
    public FormTemplate create(String name, String description,
                                String fields, String tagMapping, String remarkTemplate) {
        return templateRepo.save(FormTemplate.builder()
            .name(name).description(description)
            .fields(fields).tagMapping(tagMapping).remarkTemplate(remarkTemplate).build());
    }

    @Transactional
    public FormTemplate update(Long id, String name, String description,
                                String fields, String tagMapping, String remarkTemplate) {
        FormTemplate t = getById(id);
        if (name != null) t.setName(name);
        if (description != null) t.setDescription(description);
        if (fields != null) t.setFields(fields);
        if (tagMapping != null) t.setTagMapping(tagMapping);
        if (remarkTemplate != null) t.setRemarkTemplate(remarkTemplate);
        return templateRepo.save(t);
    }

    @Transactional
    public void delete(Long id) {
        if (!templateRepo.existsById(id))
            throw new RuntimeException("表单模板不存在: " + id);
        templateRepo.deleteById(id);
    }
}
```

- [ ] **Step 4: Compile and commit**

```bash
cd D:/ClaudeCode/HuoMa && mvn compile -q
git add src/main/java/com/bookstore/qrcode/entity/FormTemplate.java src/main/java/com/bookstore/qrcode/repository/FormTemplateRepository.java src/main/java/com/bookstore/qrcode/service/FormTemplateService.java
git commit -m "feat: add FormTemplate entity, repository, and service"
```

---

### Task 3: FormSubmission Entity + Repository

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/entity/FormSubmission.java`
- Create: `src/main/java/com/bookstore/qrcode/repository/FormSubmissionRepository.java`

- [ ] **Step 1: Write entities**

```java
package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "form_submission")
@Data
@NoArgsConstructor @AllArgsConstructor @Builder
public class FormSubmission {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "form_template_id", nullable = false)
    private Long formTemplateId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "qr_code_id")
    private Long qrCodeId;

    @Column(name = "field_data", columnDefinition = "JSON", nullable = false)
    private String fieldData;

    @Column(name = "tags_applied", length = 500)
    private String tagsApplied;

    @Column(name = "remark_updated", length = 500)
    private String remarkUpdated;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @PrePersist
    void prePersist() {
        if (submittedAt == null) submittedAt = LocalDateTime.now();
    }
}
```

```java
package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.FormSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface FormSubmissionRepository extends JpaRepository<FormSubmission, Long> {
    List<FormSubmission> findByCustomerIdOrderBySubmittedAtDesc(Long customerId);
    boolean existsByCustomerId(Long customerId);
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/entity/FormSubmission.java src/main/java/com/bookstore/qrcode/repository/FormSubmissionRepository.java
git commit -m "feat: add FormSubmission entity and repository"
```

---

### Task 4: QrCodeGroup Entity + Repository + Service

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/entity/QrCodeGroup.java`
- Create: `src/main/java/com/bookstore/qrcode/repository/QrCodeGroupRepository.java`
- Create: `src/main/java/com/bookstore/qrcode/service/QrCodeGroupService.java`

- [ ] **Step 1: Write all three files**

```java
// QrCodeGroup.java
package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "qr_code_group")
@Data
@NoArgsConstructor @AllArgsConstructor @Builder
public class QrCodeGroup {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "region_city", length = 50)
    private String regionCity;

    @Column(name = "region_district", nullable = false, length = 50)
    private String regionDistrict;

    @Column(name = "group_type", nullable = false, length = 20)
    @Builder.Default
    private String groupType = "alliance";

    @Column(name = "default_welcome_text", length = 500)
    private String defaultWelcomeText;

    @Column(name = "default_form_template_id")
    private Long defaultFormTemplateId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist void prePersist() { createdAt = updatedAt = LocalDateTime.now(); }
    @PreUpdate void preUpdate() { updatedAt = LocalDateTime.now(); }
}
```

```java
// QrCodeGroupRepository.java
package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.QrCodeGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface QrCodeGroupRepository extends JpaRepository<QrCodeGroup, Long> {
    List<QrCodeGroup> findAllByOrderByName();
    Optional<QrCodeGroup> findByRegionDistrict(String regionDistrict);
}
```

```java
// QrCodeGroupService.java
package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.QrCodeGroup;
import com.bookstore.qrcode.repository.QrCodeGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class QrCodeGroupService {

    private final QrCodeGroupRepository groupRepo;

    public List<QrCodeGroup> listAll() {
        return groupRepo.findAllByOrderByName();
    }

    public QrCodeGroup getById(Long id) {
        return groupRepo.findById(id)
            .orElseThrow(() -> new RuntimeException("分组不存在: " + id));
    }

    @Transactional
    public QrCodeGroup create(String name, String regionCity, String regionDistrict,
                               String defaultWelcomeText, Long defaultFormTemplateId) {
        return groupRepo.save(QrCodeGroup.builder()
            .name(name).regionCity(regionCity).regionDistrict(regionDistrict)
            .defaultWelcomeText(defaultWelcomeText).defaultFormTemplateId(defaultFormTemplateId)
            .build());
    }

    @Transactional
    public QrCodeGroup update(Long id, String name, String regionCity, String regionDistrict,
                               String defaultWelcomeText, Long defaultFormTemplateId) {
        QrCodeGroup g = getById(id);
        if (name != null) g.setName(name);
        if (regionCity != null) g.setRegionCity(regionCity);
        if (regionDistrict != null) g.setRegionDistrict(regionDistrict);
        if (defaultWelcomeText != null) g.setDefaultWelcomeText(defaultWelcomeText);
        g.setDefaultFormTemplateId(defaultFormTemplateId);
        return groupRepo.save(g);
    }

    @Transactional
    public void delete(Long id) {
        if (!groupRepo.existsById(id))
            throw new RuntimeException("分组不存在: " + id);
        groupRepo.deleteById(id);
    }
}
```

- [ ] **Step 2: Commit**

```bash
cd D:/ClaudeCode/HuoMa && mvn compile -q
git add src/main/java/com/bookstore/qrcode/entity/QrCodeGroup.java src/main/java/com/bookstore/qrcode/repository/QrCodeGroupRepository.java src/main/java/com/bookstore/qrcode/service/QrCodeGroupService.java
git commit -m "feat: add QrCodeGroup entity, repository, and service"
```

---

## Phase 2: Form Template Admin UI

### Task 5: FormTemplate Admin Pages

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/controller/FormTemplateController.java`
- Create: `src/main/resources/templates/admin/form-templates.html`
- Create: `src/main/resources/templates/admin/form-template-edit.html`

- [ ] **Step 1: Write FormTemplateController**

```java
package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.service.FormTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/form-templates")
@RequiredArgsConstructor
public class FormTemplateController {

    private final FormTemplateService templateService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("templates", templateService.listAll());
        return "admin/form-templates";
    }

    @GetMapping("/create")
    public String createForm(Model model) {
        model.addAttribute("template", null);
        return "admin/form-template-edit";
    }

    @PostMapping("/create")
    public String create(@RequestParam String name,
                         @RequestParam(required = false) String description,
                         @RequestParam String fields,
                         @RequestParam String tagMapping,
                         @RequestParam(required = false) String remarkTemplate,
                         RedirectAttributes redirect) {
        try {
            templateService.create(name, description, fields, tagMapping, remarkTemplate);
            redirect.addFlashAttribute("message", "模板创建成功");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/form-templates";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("template", templateService.getById(id));
        return "admin/form-template-edit";
    }

    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id, @RequestParam String name,
                         @RequestParam(required = false) String description,
                         @RequestParam String fields,
                         @RequestParam String tagMapping,
                         @RequestParam(required = false) String remarkTemplate,
                         RedirectAttributes redirect) {
        try {
            templateService.update(id, name, description, fields, tagMapping, remarkTemplate);
            redirect.addFlashAttribute("message", "模板已更新");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/form-templates";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            templateService.delete(id);
            redirect.addFlashAttribute("message", "模板已删除");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/form-templates";
    }
}
```

- [ ] **Step 2: Write form-templates.html**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout :: layout(title='表单模板管理', content=~{::main})}">
<main>
<div class="card p-4">
    <div class="d-flex justify-content-between align-items-center mb-3">
        <h5>📋 收集表单模板</h5>
        <a href="/admin/form-templates/create" class="btn btn-primary btn-sm">+ 新建模板</a>
    </div>
    <div th:if="${templates.isEmpty()}" class="text-muted text-center py-4">
        暂无模板，点击「新建模板」创建第一个
    </div>
    <table th:if="${!templates.isEmpty()}" class="table table-sm">
        <thead><tr><th>名称</th><th>说明</th><th>操作</th></tr></thead>
        <tbody>
            <tr th:each="t : ${templates}">
                <td><strong th:text="${t.name}"></strong></td>
                <td th:text="${t.description}"></td>
                <td class="text-nowrap">
                    <a th:href="@{/admin/form-templates/{id}/edit(id=${t.id})}"
                       class="btn btn-sm btn-outline-secondary">编辑</a>
                    <form th:action="@{/admin/form-templates/{id}/delete(id=${t.id})}"
                          method="post" class="d-inline"
                          onsubmit="return confirm('确定删除该模板？已有活码引用时不影响已收集数据。')">
                        <button class="btn btn-sm btn-outline-danger">删除</button>
                    </form>
                </td>
            </tr>
        </tbody>
    </table>
</div>
</main>
</html>
```

- [ ] **Step 3: Write form-template-edit.html**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout :: layout(title='编辑表单模板', content=~{::main})}">
<main>
<div class="card p-4 mx-auto" style="max-width: 800px;">
    <h5 th:text="${template == null ? '新建表单模板' : '编辑表单模板'}"></h5>
    <form method="post"
          th:action="${template == null ? '/admin/form-templates/create' : '/admin/form-templates/' + template.id + '/update'}">
        <div class="mb-3">
            <label class="form-label">模板名称 <span class="text-danger">*</span></label>
            <input type="text" name="name" class="form-control" required
                   th:value="${template?.name}" placeholder="如：学校通用表单">
        </div>
        <div class="mb-3">
            <label class="form-label">说明</label>
            <input type="text" name="description" class="form-control"
                   th:value="${template?.description}" placeholder="表单用途说明">
        </div>
        <div class="mb-3">
            <label class="form-label">字段定义 (JSON) <span class="text-danger">*</span></label>
            <textarea name="fields" class="form-control" rows="12" required
                      style="font-family:monospace;font-size:13px;"
                      th:text="${template?.fields}">[{"name":"grade","label":"孩子年级","type":"select","required":true,"options":["一年级","二年级","三年级","四年级","五年级","六年级","初一","初二","初三","高一","高二","高三"]},{"name":"class","label":"孩子班级","type":"select","required":true,"options":["1班","2班",...,"20班"]},{"name":"child_name","label":"孩子姓名","type":"text","required":false}]</textarea>
            <div class="form-text">type: text | select。select 需提供 options 数组。</div>
        </div>
        <div class="mb-3">
            <label class="form-label">字段映射规则 (JSON) <span class="text-danger">*</span></label>
            <textarea name="tagMapping" class="form-control" rows="4" required
                      style="font-family:monospace;font-size:13px;"
                      th:text="${template?.tagMapping}">{"grade":"tag","class":"tag","child_name":"remark"}</textarea>
            <div class="form-text">tag=打企微标签, remark=写企微备注</div>
        </div>
        <div class="mb-3">
            <label class="form-label">备注模板</label>
            <input type="text" name="remarkTemplate" class="form-control"
                   th:value="${template?.remarkTemplate}"
                   placeholder="如：{{child_name}}-{{grade}}{{class}}">
            <div class="form-text">支持 {{fieldName}} 占位符</div>
        </div>
        <div class="d-flex gap-2">
            <button type="submit" class="btn btn-primary">保存</button>
            <a href="/admin/form-templates" class="btn btn-outline-secondary">取消</a>
        </div>
    </form>
</div>
</main>
</html>
```

- [ ] **Step 4: Compile and commit**

```bash
cd D:/ClaudeCode/HuoMa && mvn compile -q
git add src/main/java/com/bookstore/qrcode/controller/FormTemplateController.java src/main/resources/templates/admin/form-templates.html src/main/resources/templates/admin/form-template-edit.html
git commit -m "feat: add FormTemplate admin CRUD pages"
```

---

### Task 6: QrCodeGroup Admin Pages

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/controller/QrCodeGroupController.java`
- Create: `src/main/resources/templates/admin/groups.html`

- [ ] **Step 1: Write controller + page**

```java
package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.service.QrCodeGroupService;
import com.bookstore.qrcode.service.FormTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/groups")
@RequiredArgsConstructor
public class QrCodeGroupController {

    private final QrCodeGroupService groupService;
    private final FormTemplateService formTemplateService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("groups", groupService.listAll());
        model.addAttribute("formTemplates", formTemplateService.listAll());
        return "admin/groups";
    }

    @PostMapping("/create")
    public String create(@RequestParam String name,
                         @RequestParam(required = false) String regionCity,
                         @RequestParam String regionDistrict,
                         @RequestParam(required = false) String defaultWelcomeText,
                         @RequestParam(required = false) Long defaultFormTemplateId,
                         RedirectAttributes redirect) {
        try {
            groupService.create(name, regionCity, regionDistrict,
                defaultWelcomeText, defaultFormTemplateId);
            redirect.addFlashAttribute("message", "分组创建成功");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/groups";
    }

    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id, @RequestParam String name,
                         @RequestParam(required = false) String regionCity,
                         @RequestParam String regionDistrict,
                         @RequestParam(required = false) String defaultWelcomeText,
                         @RequestParam(required = false) Long defaultFormTemplateId,
                         RedirectAttributes redirect) {
        try {
            groupService.update(id, name, regionCity, regionDistrict,
                defaultWelcomeText, defaultFormTemplateId);
            redirect.addFlashAttribute("message", "分组已更新");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/groups";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            groupService.delete(id);
            redirect.addFlashAttribute("message", "分组已删除");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/groups";
    }
}
```

```html
<!-- admin/groups.html -->
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout :: layout(title='活码分组管理', content=~{::main})}">
<main>
<div class="card p-4">
    <div class="d-flex justify-content-between align-items-center mb-3">
        <h5>🏫 活码分组（教育联盟）</h5>
        <button class="btn btn-primary btn-sm" data-bs-toggle="modal" data-bs-target="#createModal">+ 新建分组</button>
    </div>
    <div th:if="${groups.isEmpty()}" class="text-muted text-center py-4">暂无分组</div>
    <table th:if="${!groups.isEmpty()}" class="table table-sm">
        <thead><tr><th>名称</th><th>市州</th><th>县区</th><th>默认欢迎语</th><th>默认表单</th><th>操作</th></tr></thead>
        <tbody>
            <tr th:each="g : ${groups}">
                <td><strong th:text="${g.name}"></strong></td>
                <td th:text="${g.regionCity}"></td>
                <td th:text="${g.regionDistrict}"></td>
                <td><small th:text="${g.defaultWelcomeText}"></small></td>
                <td><small th:text="${g.defaultFormTemplateId}"></small></td>
                <td class="text-nowrap">
                    <button class="btn btn-sm btn-outline-secondary edit-btn"
                            th:attr="data-id=${g.id},data-name=${g.name},data-city=${g.regionCity},data-district=${g.regionDistrict},data-welcome=${g.defaultWelcomeText},data-formid=${g.defaultFormTemplateId}"
                            data-bs-toggle="modal" data-bs-target="#editModal">编辑</button>
                    <form th:action="@{/admin/groups/{id}/delete(id=${g.id})}" method="post" class="d-inline"
                          onsubmit="return confirm('确定删除该分组？分组下的活码将变为未分组。')">
                        <button class="btn btn-sm btn-outline-danger">删除</button>
                    </form>
                </td>
            </tr>
        </tbody>
    </table>
</div>
<!-- Create/Edit modals omitted for brevity — same pattern as detail.html modals -->
</main>
</html>
```

- [ ] **Step 2: Commit**

```bash
cd D:/ClaudeCode/HuoMa && mvn compile -q
git add src/main/java/com/bookstore/qrcode/controller/QrCodeGroupController.java src/main/resources/templates/admin/groups.html
git commit -m "feat: add QrCodeGroup admin pages"
```

---

## Phase 3: H5 Form (Customer-Facing)

### Task 7: H5 Form Page + Submission API

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/controller/FormController.java`
- Create: `src/main/resources/templates/form/fill.html`
- Create: `src/main/resources/templates/form/success.html`

- [ ] **Step 1: Write FormController**

```java
package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.config.RedisConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@Slf4j
@Controller
@RequiredArgsConstructor
public class FormController {

    private final QrCodeRepository qrCodeRepo;
    private final FormTemplateRepository formTemplateRepo;
    private final FormSubmissionRepository submissionRepo;
    private final CustomerRepository customerRepo;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @GetMapping("/form/{qrCodeId}")
    public String fillForm(@PathVariable Long qrCodeId,
                           @RequestParam(required = false) Long c,
                           Model model) {
        QrCode qr = qrCodeRepo.findById(qrCodeId).orElse(null);
        if (qr == null || qr.getFormTemplateId() == null) {
            return "form/success";
        }
        FormTemplate tpl = formTemplateRepo.findById(qr.getFormTemplateId()).orElse(null);
        if (tpl == null) return "form/success";

        model.addAttribute("qrCodeId", qrCodeId);
        model.addAttribute("customerId", c);
        model.addAttribute("schoolName", qr.getSchoolName());
        model.addAttribute("fieldsJson", tpl.getFields());
        return "form/fill";
    }

    @PostMapping("/api/form/submit")
    @ResponseBody
    public Map<String, Object> submit(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Long qrCodeId = body.get("qrCodeId") != null
                ? Long.valueOf(body.get("qrCodeId").toString()) : null;
            Long customerId = body.get("customerId") != null
                ? Long.valueOf(body.get("customerId").toString()) : null;
            String fieldData = objectMapper.writeValueAsString(
                body.getOrDefault("fieldData", Map.of()));

            if (qrCodeId == null || customerId == null) {
                result.put("success", false); result.put("error", "缺少必要参数"); return result;
            }

            QrCode qr = qrCodeRepo.findById(qrCodeId).orElse(null);
            if (qr == null || qr.getFormTemplateId() == null) {
                result.put("success", false); result.put("error", "活码未配置表单"); return result;
            }

            FormSubmission sub = FormSubmission.builder()
                .formTemplateId(qr.getFormTemplateId())
                .customerId(customerId).qrCodeId(qrCodeId)
                .fieldData(fieldData).build();
            submissionRepo.save(sub);

            Customer customer = customerRepo.findById(customerId).orElse(null);
            if (customer != null && customer.getCurrentAgent() != null) {
                Map<String, Object> tagEvent = new LinkedHashMap<>();
                tagEvent.put("type", "form_submit");
                tagEvent.put("external_userid", customer.getExternalUserid());
                tagEvent.put("userid", customer.getCurrentAgent());
                tagEvent.put("form_template_id", qr.getFormTemplateId().toString());
                tagEvent.put("field_data", fieldData);
                redisTemplate.opsForStream().add(
                    RedisConfig.TAG_STREAM_KEY,
                    Map.of("event", objectMapper.writeValueAsString(tagEvent)));
            }

            result.put("success", true);
        } catch (Exception e) {
            log.error("表单提交失败", e);
            result.put("success", false); result.put("error", e.getMessage());
        }
        return result;
    }

    @GetMapping("/form/success")
    public String success() { return "form/success"; }
}
```

- [ ] **Step 2: Write fill.html and success.html**

```html
<!-- form/fill.html — standalone, no layout (customer-facing) -->
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>信息收集</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css" rel="stylesheet">
</head>
<body style="background:#f5f6fa;">
<div class="container py-4" style="max-width:500px;">
    <div class="card shadow-sm">
        <div class="card-body p-4">
            <h5 class="text-center mb-2" th:text="${schoolName}"></h5>
            <p class="text-center text-muted small mb-4">请填写孩子信息，以便为您精准服务</p>
            <form id="collectForm">
                <input type="hidden" id="qrCodeId" th:value="${qrCodeId}">
                <input type="hidden" id="customerId" th:value="${customerId}">
                <div id="formFields"></div>
                <button type="submit" class="btn btn-primary w-100 mt-3">提交</button>
            </form>
            <div id="submitMsg" class="text-center mt-2" style="display:none;"></div>
        </div>
    </div>
</div>
<script>
    var fields = JSON.parse(/*[[${fieldsJson}]]*/ '[]');
    var container = document.getElementById('formFields');
    fields.forEach(function(f) {
        var div = document.createElement('div'); div.className = 'mb-3';
        var label = document.createElement('label');
        label.className = 'form-label'; label.textContent = f.label + (f.required ? ' *' : '');
        div.appendChild(label);
        if (f.type === 'select') {
            var sel = document.createElement('select');
            sel.className = 'form-select'; sel.name = f.name; sel.required = f.required || false;
            sel.innerHTML = '<option value="">请选择</option>' +
                (f.options || []).map(function(o) { return '<option value="' + o + '">' + o + '</option>'; }).join('');
            div.appendChild(sel);
        } else {
            var input = document.createElement('input');
            input.type = 'text'; input.className = 'form-control';
            input.name = f.name; input.required = f.required || false;
            div.appendChild(input);
        }
        container.appendChild(div);
    });

    document.getElementById('collectForm').addEventListener('submit', function(e) {
        e.preventDefault();
        var fieldData = {};
        fields.forEach(function(f) {
            var el = document.querySelector('[name="' + f.name + '"]');
            if (el) fieldData[f.name] = el.value;
        });
        var btn = e.target.querySelector('button');
        btn.disabled = true; btn.textContent = '提交中...';
        fetch('/api/form/submit', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                qrCodeId: parseInt(document.getElementById('qrCodeId').value),
                customerId: parseInt(document.getElementById('customerId').value),
                fieldData: fieldData
            })
        }).then(function(r) { return r.json(); }).then(function(d) {
            var msg = document.getElementById('submitMsg'); msg.style.display = '';
            if (d.success) {
                msg.innerHTML = '<span class="text-success">✅ 提交成功！</span>';
                setTimeout(function() { window.location.href = '/form/success'; }, 1000);
            } else {
                msg.innerHTML = '<span class="text-danger">❌ ' + (d.error || '提交失败') + '</span>';
                btn.disabled = false; btn.textContent = '提交';
            }
        });
    });
</script>
</body>
</html>
```

```html
<!-- form/success.html -->
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>提交成功</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css" rel="stylesheet">
</head>
<body style="background:#f5f6fa;">
<div class="container py-5 text-center" style="max-width:400px;">
    <div class="card shadow-sm p-5">
        <div style="font-size:56px;">✅</div>
        <h5 class="mt-3">信息提交成功</h5>
        <p class="text-muted">老师已收到孩子信息，后续服务将更加精准</p>
        <button class="btn btn-outline-secondary mt-2" onclick="window.close()">关闭</button>
    </div>
</div>
</body>
</html>
```

- [ ] **Step 3: Compile and commit**

```bash
cd D:/ClaudeCode/HuoMa && mvn compile -q
git add src/main/java/com/bookstore/qrcode/controller/FormController.java src/main/resources/templates/form/
git commit -m "feat: add H5 form fill page and submission API with async TAG_STREAM event"
```

---

### Task 8: Extend TagWorker for form_submit Events

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/worker/TagWorker.java`
- Modify: `src/main/java/com/bookstore/qrcode/service/TagService.java`

- [ ] **Step 1: Update TagService — add formTemplateRepo + formSubmissionRepo fields and applyFormTags()**

In `TagService.java`:

Add new final fields (Lombok @RequiredArgsConstructor will auto-inject):
```java
private final FormTemplateRepository formTemplateRepo;
private final FormSubmissionRepository formSubmissionRepo;
```

Add method:
```java
/**
 * 表单提交后异步打标+备注（由 TagWorker 消费 form_submit 事件调用）。
 */
@Transactional
public void applyFormTags(String externalUserId, String userId,
                           Long formTemplateId, Long submissionId, String fieldDataJson) {
    try {
        FormTemplate tpl = formTemplateRepo.findById(formTemplateId).orElse(null);
        if (tpl == null) { log.warn("表单模板不存在: {}", formTemplateId); return; }

        Customer customer = customerRepo.findByExternalUserid(externalUserId).orElse(null);
        if (customer == null) { log.warn("客户不存在: {}", externalUserId); return; }

        com.fasterxml.jackson.databind.JsonNode fieldData =
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(fieldDataJson);
        com.fasterxml.jackson.databind.JsonNode tagMapping =
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(tpl.getTagMapping());

        List<String> appliedTags = new ArrayList<>();
        String remarkText = null;

        java.util.Iterator<String> fn = fieldData.fieldNames();
        while (fn.hasNext()) {
            String fieldName = fn.next();
            String fieldValue = fieldData.get(fieldName).asText();
            if (fieldValue == null || fieldValue.isBlank()) continue;

            String action = tagMapping.has(fieldName)
                ? tagMapping.get(fieldName).asText() : null;
            if (action == null) continue;

            if ("tag".equals(action)) {
                Tag tag = getOrCreateTag(fieldValue, Tag.TagType.form, null, "学校");
                bindCustomerTag(customer.getId(), tag.getId(), "form");
                if (tag.getWecomTagId() != null) {
                    wecomApi.markTag(externalUserId, userId, List.of(tag.getWecomTagId()));
                    appliedTags.add(tag.getName());
                }
            }
        }

        // 按 remark_template 拼接备注
        if (tpl.getRemarkTemplate() != null && !tpl.getRemarkTemplate().isBlank()) {
            remarkText = tpl.getRemarkTemplate();
            java.util.Iterator<String> fn2 = fieldData.fieldNames();
            while (fn2.hasNext()) {
                String key = fn2.next();
                String val = fieldData.get(key).asText();
                remarkText = remarkText.replace("{{" + key + "}}", val != null ? val : "");
            }
            wecomApi.updateRemark(userId, externalUserId, remarkText);
        }

        // 回填 submission 记录
        formSubmissionRepo.findById(submissionId).ifPresent(sub -> {
            sub.setTagsApplied(String.join(",", appliedTags));
            sub.setRemarkUpdated(remarkText);
            formSubmissionRepo.save(sub);
        });

        log.info("表单打标完成: external={}, tags={}, remark={}", externalUserId, appliedTags, remarkText);
    } catch (Exception e) {
        log.error("表单打标异常: external={}", externalUserId, e);
        throw new RuntimeException("表单打标失败", e);
    }
}
```

- [ ] **Step 2: Update TagWorker.processEvent() — add form_submit branch**

In `TagWorker.java` `processEvent()`, add at top of the method (after null check):
```java
// Check for form_submit event type first
if (event.has("type")) {
    String type = event.get("type").asText();
    if ("form_submit".equals(type)) {
        String externalUserId = getField(event, "external_userid");
        String userId = getField(event, "userid");
        Long formTemplateId = event.has("form_template_id")
            ? Long.valueOf(event.get("form_template_id").asText()) : null;
        Long submissionId = event.has("submission_id")
            ? Long.valueOf(event.get("submission_id").asText()) : null;
        String fieldData = event.has("field_data")
            ? event.get("field_data").asText() : "{}";

        if (externalUserId == null || userId == null || formTemplateId == null) {
            log.warn("form_submit 事件缺少关键字段");
            return;
        }
        tagService.applyFormTags(externalUserId, userId,
            formTemplateId, submissionId, fieldData);
        return;
    }
}
// ... existing auto-tag logic below ...
```

Add `getField` helper (same as in CallbackWorker):
```java
private String getField(com.fasterxml.jackson.databind.JsonNode event, String field) {
    return event.has(field) && !event.get(field).isNull()
        ? event.get(field).asText() : null;
}
```

- [ ] **Step 3: Update imports in TagService and TagWorker**

TagService needs:
```java
import com.bookstore.qrcode.repository.FormTemplateRepository;
import com.bookstore.qrcode.repository.FormSubmissionRepository;
```

TagWorker needs:
```java
// getField() already usable via existing imports
```

- [ ] **Step 4: Compile and commit**

```bash
cd D:/ClaudeCode/HuoMa && mvn compile -q
git add src/main/java/com/bookstore/qrcode/worker/TagWorker.java src/main/java/com/bookstore/qrcode/service/TagService.java
git commit -m "feat: extend TagWorker/TagService to handle form_submit events with tag+remark"
```

---

## Phase 4: Welcome Message + OutboundMsgWorker

### Task 9: RedisConfig — Add OUTBOUND_STREAM Constants + Consumer Group Init

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/config/RedisConfig.java`
- Modify: `src/main/java/com/bookstore/qrcode/config/AsyncConfig.java` (if new executor needed)

- [ ] **Step 1: Add constants to RedisConfig**

After existing TAG_STREAM constants, add:
```java
// ==================== 出站消息 Stream 相关常量 ====================
public static final String OUTBOUND_STREAM_KEY = "wecom:outbound:stream";
public static final String OUTBOUND_CONSUMER_GROUP = "outbound-worker-group";
```

- [ ] **Step 2: Add Consumer Group init bean**

After `datafillConsumerGroup()` bean:
```java
@Bean
public String outboundConsumerGroup(
        @Qualifier("stringRedisTemplate") StringRedisTemplate redisTemplate) {
    try {
        RecordId initId = redisTemplate.opsForStream()
            .add(OUTBOUND_STREAM_KEY, Map.of("_init", "1"));
        redisTemplate.opsForStream().createGroup(OUTBOUND_STREAM_KEY,
            ReadOffset.from("0-0"), OUTBOUND_CONSUMER_GROUP);
        redisTemplate.opsForStream().delete(OUTBOUND_STREAM_KEY, initId);
    } catch (Exception e) { /* already exists */ }
    return OUTBOUND_CONSUMER_GROUP;
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/config/RedisConfig.java
git commit -m "feat: add OUTBOUND_STREAM constants and consumer group init"
```

---

### Task 10: OutboundMsgWorker — Async Welcome+Form Sender

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/worker/OutboundMsgWorker.java`

**Pattern:** Follows TagWorker exactly — same Stream consumer, retry/DLQ, NOGROUP recovery, trim logic. Consumes `OUTBOUND_STREAM_KEY`.

- [ ] **Step 1: Write OutboundMsgWorker**

```java
package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.service.MessageGuardService;
import com.bookstore.qrcode.service.MessageGuardService.ErrorAction;
import com.bookstore.qrcode.wecom.*;
import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboundMsgWorker {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Executor taskExecutor;
    private final WecomApiClient wecomApi;
    private final MessageGuardService messageGuardService;
    private final QrCodeRepository qrCodeRepo;
    private final QrCodeGroupRepository groupRepo;
    private final SystemConfigRepository systemConfigRepo;
    private final CustomerRepository customerRepo;

    private volatile boolean running = true;
    @Value("${app.worker.outbound.threads:4}")
    private int consumerThreads;
    private static final String CONSUMER_PREFIX = "outbound-worker";

    @PostConstruct
    public void start() {
        for (int i = 1; i <= consumerThreads; i++) {
            final int tid = i;
            final String name = RedisConfig.consumerName(CONSUMER_PREFIX, tid);
            taskExecutor.execute(() -> consumeLoop(name, tid));
        }
        log.info("OutboundMsgWorker started {} threads", consumerThreads);
    }

    @PreDestroy
    public void shutdown() { running = false; }

    private void consumeLoop(String consumerName, int threadId) {
        while (running) {
            try {
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                    .read(
                        Consumer.from(RedisConfig.OUTBOUND_CONSUMER_GROUP, consumerName),
                        StreamReadOptions.empty().count(50).block(Duration.ofSeconds(5)),
                        StreamOffset.create(RedisConfig.OUTBOUND_STREAM_KEY, ReadOffset.lastConsumed())
                    );

                if (records == null || records.isEmpty()) {
                    Thread.sleep(100);
                    continue;
                }

                for (MapRecord<String, Object, Object> record : records) {
                    String msgId = record.getId().getValue();
                    String eventJson = (String) record.getValue().get("event");
                    if (eventJson == null) {
                        redisTemplate.opsForStream().acknowledge(
                            RedisConfig.OUTBOUND_STREAM_KEY,
                            RedisConfig.OUTBOUND_CONSUMER_GROUP, msgId);
                        continue;
                    }
                    Map<String, String> fields = Map.of("event", eventJson);

                    String retryAt = (String) record.getValue().get("_retry_at");
                    if (retryAt != null) {
                        try {
                            if (Long.parseLong(retryAt) > java.time.Instant.now().getEpochSecond()) {
                                redisTemplate.opsForStream().acknowledge(
                                    RedisConfig.OUTBOUND_STREAM_KEY,
                                    RedisConfig.OUTBOUND_CONSUMER_GROUP, msgId);
                                continue;
                            }
                        } catch (NumberFormatException ignored) {}
                    }

                    try {
                        processEvent(eventJson);
                        redisTemplate.opsForStream().acknowledge(
                            RedisConfig.OUTBOUND_STREAM_KEY,
                            RedisConfig.OUTBOUND_CONSUMER_GROUP, msgId);
                    } catch (WecomApiException e) {
                        ErrorAction action = MessageGuardService.classifyWecomError(e);
                        log.error("OutboundMsg 失败 (action={}): msgId={}", action, msgId, e);
                        switch (action) {
                            case DLQ:
                                messageGuardService.sendToDlq(RedisConfig.OUTBOUND_STREAM_KEY, fields);
                                redisTemplate.opsForStream().acknowledge(
                                    RedisConfig.OUTBOUND_STREAM_KEY,
                                    RedisConfig.OUTBOUND_CONSUMER_GROUP, msgId);
                                break;
                            case REFRESH_TOKEN_AND_RETRY:
                                wecomApi.refreshToken();
                                messageGuardService.markRetryOrDead(RedisConfig.OUTBOUND_STREAM_KEY,
                                    RedisConfig.OUTBOUND_CONSUMER_GROUP, msgId, fields, e.getMessage());
                                break;
                            case WAIT_AND_RETRY:
                                if (e instanceof WecomRateLimitException rle) {
                                    try { Thread.sleep(rle.getRetryAfterSeconds() * 1000L); }
                                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                                }
                                messageGuardService.markRetryOrDead(RedisConfig.OUTBOUND_STREAM_KEY,
                                    RedisConfig.OUTBOUND_CONSUMER_GROUP, msgId, fields, e.getMessage());
                                break;
                            default:
                                messageGuardService.markRetryOrDead(RedisConfig.OUTBOUND_STREAM_KEY,
                                    RedisConfig.OUTBOUND_CONSUMER_GROUP, msgId, fields, e.getMessage());
                                break;
                        }
                    } catch (Exception e) {
                        log.error("OutboundMsg failed: msgId={}", msgId, e);
                        messageGuardService.markRetryOrDead(RedisConfig.OUTBOUND_STREAM_KEY,
                            RedisConfig.OUTBOUND_CONSUMER_GROUP, msgId, fields, e.getMessage());
                    }
                }

                try {
                    redisTemplate.opsForStream().trim(
                        RedisConfig.OUTBOUND_STREAM_KEY, 10000, true);
                } catch (Exception e) { log.debug("OUTBOUND trim skip: {}", e.getMessage()); }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("NOGROUP")) {
                    try {
                        RecordId initId = redisTemplate.opsForStream()
                            .add(RedisConfig.OUTBOUND_STREAM_KEY, Map.of("_init", "1"));
                        redisTemplate.opsForStream().createGroup(RedisConfig.OUTBOUND_STREAM_KEY,
                            ReadOffset.from("0-0"), RedisConfig.OUTBOUND_CONSUMER_GROUP);
                        redisTemplate.opsForStream().delete(RedisConfig.OUTBOUND_STREAM_KEY, initId);
                    } catch (Exception e2) {}
                    continue;
                }
                log.error("OutboundWorker-{} error, retry 5s", threadId, e);
                try { Thread.sleep(5000); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
    }

    private void processEvent(String eventJson) throws Exception {
        JsonNode event = objectMapper.readTree(eventJson);
        String externalUserId = getField(event, "external_userid");
        String userid = getField(event, "userid");
        String state = getField(event, "state");
        Long qrCodeId = event.has("qr_code_id") && !event.get("qr_code_id").isNull()
            ? event.get("qr_code_id").asLong() : null;

        if (externalUserId == null || userid == null) return;

        // Resolve welcome text: qrCode.welcomeText → group default → system default
        String welcomeText = null;
        Long formTemplateId = null;

        if (qrCodeId != null) {
            QrCode qr = qrCodeRepo.findById(qrCodeId).orElse(null);
            if (qr != null) {
                welcomeText = qr.getWelcomeText();
                formTemplateId = qr.getFormTemplateId();
                // Inherit from group
                if (welcomeText == null && qr.getGroupId() != null) {
                    QrCodeGroup grp = groupRepo.findById(qr.getGroupId()).orElse(null);
                    if (grp != null) {
                        welcomeText = grp.getDefaultWelcomeText();
                        if (formTemplateId == null) formTemplateId = grp.getDefaultFormTemplateId();
                    }
                }
            }
        }
        // System default fallback
        if (welcomeText == null) {
            welcomeText = systemConfigRepo.findByConfigKey("default_welcome_text")
                .map(SystemConfig::getConfigValue).orElse("欢迎来到XX书店家校服务！");
        }

        // Template variable replacement
        String schoolName = "";
        String teacherName = "";
        if (qrCodeId != null) {
            QrCode qr = qrCodeRepo.findById(qrCodeId).orElse(null);
            if (qr != null) schoolName = qr.getSchoolName();
        }
        // Get teacher name from Employee table or Agent table
        teacherName = userid; // fallback
        try {
            JsonNode ul = wecomApi.getUserSimplelist();
            for (JsonNode u : ul.get("userlist")) {
                if (userid.equals(u.get("userid").asText())) {
                    teacherName = u.has("name") ? u.get("name").asText() : userid;
                    break;
                }
            }
        } catch (Exception ignored) {}

        welcomeText = welcomeText
            .replace("{{school_name}}", schoolName)
            .replace("{{teacher_name}}", teacherName);

        // ① Send welcome text
        wecomApi.sendMessage(userid, externalUserId, welcomeText);
        log.info("欢迎语已发送: to={}, sender={}", externalUserId, userid);

        // ② Send form link (300ms gap for API rate limit)
        if (formTemplateId != null) {
            Thread.sleep(300);
            String formUrl = "请点击链接填写孩子信息👇\n"
                + "https://你的域名/form/" + qrCodeId + "?c=客户ID";
            // TODO: replace with actual domain config + actual customer ID
            wecomApi.sendMessage(userid, externalUserId, formUrl);
            log.info("表单链接已发送: to={}", externalUserId);
        }
    }

    private String getField(JsonNode event, String field) {
        return event.has(field) && !event.get(field).isNull()
            ? event.get(field).asText() : null;
    }
}
```

- [ ] **Step 2: Add OUTBOUND_STREAM trim maxlen config via application.properties**

```properties
# app.redis-stream.outbound-maxlen is not yet defined; OutboundMsgWorker uses hardcoded 10000.
# Future: add @Value injection following existing pattern.
```

- [ ] **Step 3: Compile and commit**

```bash
cd D:/ClaudeCode/HuoMa && mvn compile -q
git add src/main/java/com/bookstore/qrcode/worker/OutboundMsgWorker.java
git commit -m "feat: add OutboundMsgWorker for async welcome+form sending"
```

---

### Task 11: Modify CallbackWorker — Add Step ⑤ XADD to OUTBOUND_STREAM

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/worker/CallbackWorker.java`

- [ ] **Step 1: Add XADD at end of handleAddSuccess()**

In `CallbackWorker.java`, `handleAddSuccess()` method, after step ④ (日计数), add:

```java
        // ⑤ 发布欢迎语+表单事件 → OutboundMsgWorker 异步发送
        try {
            if (state != null) {
                QrCode qr = qrCodeRepo.findBySchoolId(state).orElse(null);
                if (qr != null) {
                    Map<String, Object> outEvent = new java.util.LinkedHashMap<>();
                    outEvent.put("type", "welcome_and_form");
                    outEvent.put("external_userid", externalUserId);
                    outEvent.put("userid", userId);
                    outEvent.put("state", state);
                    outEvent.put("qr_code_id", qr.getId().toString());
                    outEvent.put("customer_id", customerId.toString());
                    redisTemplate.opsForStream().add(
                        RedisConfig.OUTBOUND_STREAM_KEY,
                        Map.of("event", objectMapper.writeValueAsString(outEvent)));
                }
            }
        } catch (Exception e) {
            log.error("发布欢迎语事件失败: external={}", externalUserId, e);
            // 不抛异常，不影响主流程（客户已入库+日计数已完成）
        }
```

- [ ] **Step 2: Add QrCodeRepository field to CallbackWorker**

```java
// Add as new final field (Lombok RequiredArgsConstructor will inject):
private final QrCodeRepository qrCodeRepo;
```

With import:
```java
import com.bookstore.qrcode.repository.QrCodeRepository;
```

- [ ] **Step 3: Compile and commit**

```bash
cd D:/ClaudeCode/HuoMa && mvn compile -q
git add src/main/java/com/bookstore/qrcode/worker/CallbackWorker.java
git commit -m "feat: add OUTBOUND_STREAM XADD in CallbackWorker step 5 for welcome+form events"
```

---

## Phase 5: Welcome Config + Batch + Detail Page

### Task 12: QrCodeController — Add Welcome/Form Config Endpoints

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/controller/QrCodeController.java`

- [ ] **Step 1: Add POST endpoints for welcome config and batch config**

Add to `QrCodeController.java`:

```java
private final FormTemplateRepository formTemplateRepo;
// Add to constructor via @RequiredArgsConstructor

@PostMapping("/{id}/welcome")
public String updateWelcome(@PathVariable Long id,
                             @RequestParam(required = false) String welcomeText,
                             @RequestParam(required = false) Long formTemplateId,
                             RedirectAttributes redirect) {
    try {
        QrCode qr = qrCodeService.getById(id);
        if (welcomeText != null) qr.setWelcomeText(welcomeText);
        qr.setFormTemplateId(formTemplateId);
        qrCodeRepo.save(qr);
        redirect.addFlashAttribute("message", "客户配置已更新");
    } catch (Exception e) {
        redirect.addFlashAttribute("error", e.getMessage());
    }
    return "redirect:/qrcodes/" + id;
}

@PostMapping("/batch-config")
public String batchConfig(@RequestParam List<Long> ids,
                           @RequestParam(required = false) String welcomeText,
                           @RequestParam(required = false) Long formTemplateId,
                           @RequestParam(required = false) Long groupId,
                           RedirectAttributes redirect) {
    int count = 0;
    for (Long id : ids) {
        try {
            QrCode qr = qrCodeService.getById(id);
            if (welcomeText != null && !welcomeText.isBlank()) qr.setWelcomeText(welcomeText);
            if (formTemplateId != null) qr.setFormTemplateId(formTemplateId);
            if (groupId != null) qr.setGroupId(groupId);
            qrCodeRepo.save(qr);
            count++;
        } catch (Exception e) {
            log.warn("批量配置失败: id={}", id, e);
        }
    }
    redirect.addFlashAttribute("message", "已更新 " + count + " 个活码");
    return "redirect:/qrcodes";
}
```

- [ ] **Step 2: Add FormTemplateRepository import and field**

```java
import com.bookstore.qrcode.repository.FormTemplateRepository;
```

- [ ] **Step 3: Compile and commit**

```bash
cd D:/ClaudeCode/HuoMa && mvn compile -q
git add src/main/java/com/bookstore/qrcode/controller/QrCodeController.java
git commit -m "feat: add welcome config and batch config endpoints to QrCodeController"
```

---

### Task 13: Detail Page — Add Welcome/Form/Inheritance UI

**Files:**
- Modify: `src/main/resources/templates/qrcode/detail.html`
- Modify: `src/main/java/com/bookstore/qrcode/controller/QrCodeController.java` (add formTemplates + groups to detail model)

- [ ] **Step 1: Pass formTemplates + groups to detail page**

In `QrCodeController.detail()` method, add after existing model attributes:

```java
model.addAttribute("formTemplates", formTemplateRepo.findAllByOrderByName());
// Add QrCodeGroupRepository field:
private final QrCodeGroupRepository groupRepo;
// ...
model.addAttribute("groups", groupRepo.findAllByOrderByName());
```

- [ ] **Step 2: Add UI sections to detail.html**

After the "二维码展示与下载卡片" div, add:

```html
<!-- 客户配置区 -->
<div class="col-12">
    <div class="card p-4">
        <h6>💬 客户侧配置</h6>
        <form th:action="@{/qrcodes/{id}/welcome(id=${qr.id})}" method="post">
            <div class="row g-3">
                <div class="col-md-6">
                    <label class="form-label">欢迎语文案
                        <small class="text-muted">（留空继承分组或系统默认）</small>
                    </label>
                    <textarea name="welcomeText" class="form-control" rows="2"
                              th:text="${qr.welcomeText}"
                              placeholder="留空使用默认：{{school_name}}家长您好～"></textarea>
                </div>
                <div class="col-md-6">
                    <label class="form-label">收集表单模板
                        <small class="text-muted">（选"无"则不收集）</small>
                    </label>
                    <select name="formTemplateId" class="form-select">
                        <option value="">无</option>
                        <option th:each="t : ${formTemplates}"
                                th:value="${t.id}"
                                th:text="${t.name}"
                                th:selected="${qr.formTemplateId == t.id}"></option>
                    </select>
                </div>
            </div>
            <button type="submit" class="btn btn-sm btn-primary mt-3">保存配置</button>
        </form>
    </div>
</div>

<!-- 在职继承区 -->
<div class="col-12">
    <div class="card p-4">
        <h6>🔄 在职继承
            <a th:href="@{/qrcodes/{id}/transfers(id=${qr.id})}"
               class="btn btn-sm btn-outline-secondary float-end">查看记录</a>
        </h6>
        <p class="small text-muted">将接待员当日积累的客户批量转移给服务老师</p>
        <button class="btn btn-sm btn-outline-primary" id="triggerInheritanceBtn"
                th:attr="data-qrid=${qr.id}">执行在职继承</button>
        <span id="transferPreview" class="ms-2 small text-muted"></span>
    </div>
</div>
```

- [ ] **Step 3: Add JS for inheritance preview + trigger**

```html
<script>
document.getElementById('triggerInheritanceBtn')?.addEventListener('click', async function() {
    const qrId = this.dataset.qrid;
    if (!confirm('确定要执行在职继承吗？')) return;
    this.disabled = true;
    this.textContent = '执行中...';
    try {
        const resp = await fetch('/api/qrcodes/' + qrId + '/transfer/trigger', {method:'POST'});
        const data = await resp.json();
        document.getElementById('transferPreview').textContent =
            data.success ? '已发起 ' + data.count + ' 条继承任务' : '失败: ' + data.error;
    } catch(e) {
        document.getElementById('transferPreview').textContent = '网络错误';
    }
    this.disabled = false;
    this.textContent = '执行在职继承';
});
</script>
```

- [ ] **Step 4: Compile and commit**

```bash
cd D:/ClaudeCode/HuoMa && mvn compile -q
git add src/main/resources/templates/qrcode/detail.html src/main/java/com/bookstore/qrcode/controller/QrCodeController.java
git commit -m "feat: add welcome/form/inheritance UI to qrcode detail page"
```

---

## Phase 6: On-Duty Inheritance

### Task 14: RedisConfig — Add TRANSFER_STREAM Constants

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/config/RedisConfig.java`

- [ ] **Step 1: Add constants + init bean**

```java
public static final String TRANSFER_STREAM_KEY = "wecom:transfer:stream";
public static final String TRANSFER_CONSUMER_GROUP = "transfer-worker-group";

@Bean
public String transferConsumerGroup(
        @Qualifier("stringRedisTemplate") StringRedisTemplate redisTemplate) {
    try {
        RecordId initId = redisTemplate.opsForStream()
            .add(TRANSFER_STREAM_KEY, Map.of("_init", "1"));
        redisTemplate.opsForStream().createGroup(TRANSFER_STREAM_KEY,
            ReadOffset.from("0-0"), TRANSFER_CONSUMER_GROUP);
        redisTemplate.opsForStream().delete(TRANSFER_STREAM_KEY, initId);
    } catch (Exception e) { /* exists */ }
    return TRANSFER_CONSUMER_GROUP;
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/config/RedisConfig.java
git commit -m "feat: add TRANSFER_STREAM constants and consumer group init"
```

---

### Task 15: TransferWorker — Consume TRANSFER_STREAM

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/worker/TransferWorker.java`

Pattern: Same as OutboundMsgWorker — Stream consumer calling TransferService.initiate() with 200ms delay between items.

- [ ] **Step 1: Write TransferWorker**

```java
package com.bookstore.qrcode.worker;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.service.TransferService;
import com.bookstore.qrcode.service.MessageGuardService;
import com.bookstore.qrcode.service.MessageGuardService.ErrorAction;
import com.bookstore.qrcode.wecom.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransferWorker {

    private final StringRedisTemplate redisTemplate;
    private final TransferService transferService;
    private final ObjectMapper objectMapper;
    private final Executor taskExecutor;
    private final WecomApiClient wecomApi;
    private final MessageGuardService messageGuardService;

    private volatile boolean running = true;
    @Value("${app.worker.transfer.threads:2}")
    private int consumerThreads;
    @Value("${app.worker.transfer.delay-ms:200}")
    private long transferDelayMs;
    private static final String CONSUMER_PREFIX = "transfer-worker";

    @PostConstruct
    public void start() {
        for (int i = 1; i <= consumerThreads; i++) {
            final int tid = i;
            final String name = RedisConfig.consumerName(CONSUMER_PREFIX, tid);
            taskExecutor.execute(() -> consumeLoop(name, tid));
        }
        log.info("TransferWorker started {} threads", consumerThreads);
    }

    @PreDestroy
    public void shutdown() { running = false; }

    private void consumeLoop(String consumerName, int threadId) {
        while (running) {
            try {
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                    .read(
                        Consumer.from(RedisConfig.TRANSFER_CONSUMER_GROUP, consumerName),
                        StreamReadOptions.empty().count(50).block(Duration.ofSeconds(5)),
                        StreamOffset.create(RedisConfig.TRANSFER_STREAM_KEY, ReadOffset.lastConsumed())
                    );

                if (records == null || records.isEmpty()) {
                    Thread.sleep(100);
                    continue;
                }

                for (MapRecord<String, Object, Object> record : records) {
                    String msgId = record.getId().getValue();
                    String eventJson = (String) record.getValue().get("event");
                    if (eventJson == null) {
                        redisTemplate.opsForStream().acknowledge(
                            RedisConfig.TRANSFER_STREAM_KEY,
                            RedisConfig.TRANSFER_CONSUMER_GROUP, msgId);
                        continue;
                    }
                    Map<String, String> fields = Map.of("event", eventJson);

                    try {
                        JsonNode event = objectMapper.readTree(eventJson);
                        Long customerId = event.has("customer_id")
                            ? event.get("customer_id").asLong() : null;
                        String fromUserid = getField(event, "from_userid");
                        String toUserid = getField(event, "to_userid");
                        String externalUserid = getField(event, "external_userid");
                        String state = getField(event, "state");

                        if (customerId != null && fromUserid != null
                                && toUserid != null && externalUserid != null) {
                            transferService.initiate(customerId, fromUserid, externalUserid, state);
                        }
                        redisTemplate.opsForStream().acknowledge(
                            RedisConfig.TRANSFER_STREAM_KEY,
                            RedisConfig.TRANSFER_CONSUMER_GROUP, msgId);
                    } catch (WecomApiException e) {
                        ErrorAction action = MessageGuardService.classifyWecomError(e);
                        log.error("Transfer 失败 (action={}): msgId={}", action, msgId, e);
                        handleError(action, msgId, fields, e);
                    } catch (Exception e) {
                        log.error("Transfer failed: msgId={}", msgId, e);
                        messageGuardService.markRetryOrDead(RedisConfig.TRANSFER_STREAM_KEY,
                            RedisConfig.TRANSFER_CONSUMER_GROUP, msgId, fields, e.getMessage());
                    }

                    if (transferDelayMs > 0) {
                        try { Thread.sleep(transferDelayMs); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    }
                }

                try {
                    redisTemplate.opsForStream().trim(RedisConfig.TRANSFER_STREAM_KEY, 10000, true);
                } catch (Exception e) { log.debug("TRANSFER trim skip: {}", e.getMessage()); }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("NOGROUP")) {
                    try {
                        RecordId initId = redisTemplate.opsForStream()
                            .add(RedisConfig.TRANSFER_STREAM_KEY, Map.of("_init", "1"));
                        redisTemplate.opsForStream().createGroup(RedisConfig.TRANSFER_STREAM_KEY,
                            ReadOffset.from("0-0"), RedisConfig.TRANSFER_CONSUMER_GROUP);
                        redisTemplate.opsForStream().delete(RedisConfig.TRANSFER_STREAM_KEY, initId);
                    } catch (Exception e2) {}
                    continue;
                }
                log.error("TransferWorker-{} error, retry 5s", threadId, e);
                try { Thread.sleep(5000); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
    }

    private void handleError(ErrorAction action, String msgId,
                              Map<String, String> fields, WecomApiException e) {
        switch (action) {
            case DLQ:
                messageGuardService.sendToDlq(RedisConfig.TRANSFER_STREAM_KEY, fields);
                redisTemplate.opsForStream().acknowledge(
                    RedisConfig.TRANSFER_STREAM_KEY,
                    RedisConfig.TRANSFER_CONSUMER_GROUP, msgId);
                break;
            case REFRESH_TOKEN_AND_RETRY:
                wecomApi.refreshToken();
                messageGuardService.markRetryOrDead(RedisConfig.TRANSFER_STREAM_KEY,
                    RedisConfig.TRANSFER_CONSUMER_GROUP, msgId, fields, e.getMessage());
                break;
            case WAIT_AND_RETRY:
                if (e instanceof WecomRateLimitException rle) {
                    try { Thread.sleep(rle.getRetryAfterSeconds() * 1000L); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
                messageGuardService.markRetryOrDead(RedisConfig.TRANSFER_STREAM_KEY,
                    RedisConfig.TRANSFER_CONSUMER_GROUP, msgId, fields, e.getMessage());
                break;
            default:
                messageGuardService.markRetryOrDead(RedisConfig.TRANSFER_STREAM_KEY,
                    RedisConfig.TRANSFER_CONSUMER_GROUP, msgId, fields, e.getMessage());
                break;
        }
    }

    private String getField(JsonNode event, String field) {
        return event.has(field) && !event.get(field).isNull()
            ? event.get(field).asText() : null;
    }
}
```

- [ ] **Step 2: Compile and commit**

```bash
cd D:/ClaudeCode/HuoMa && mvn compile -q
git add src/main/java/com/bookstore/qrcode/worker/TransferWorker.java
git commit -m "feat: add TransferWorker for async inheritance execution"
```

---

### Task 16: InheritanceJob + Manual Trigger API

**Files:**
- Create: `src/main/java/com/bookstore/qrcode/job/InheritanceJob.java`
- Modify: `src/main/java/com/bookstore/qrcode/controller/QrCodeController.java` (add transfer preview/trigger)

- [ ] **Step 1: Write InheritanceJob (scheduled)**

```java
package com.bookstore.qrcode.job;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class InheritanceJob {

    private final QrCodeRepository qrCodeRepo;
    private final QrAgentRepository qrAgentRepo;
    private final CustomerRepository customerRepo;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(cron = "${app.inheritance.cron:0 0 2 * * *}")  // default 02:00 daily
    public void execute() {
        log.info("在职继承定时任务开始");
        List<QrCode> activeQrs = qrCodeRepo.findByStatus(QrCode.QrCodeStatus.active);
        int totalTransfers = 0;

        for (QrCode qr : activeQrs) {
            try {
                // Find receptionists for this QR code
                List<QrAgent> agents = qrAgentRepo.findByQrCodeId(qr.getId());
                List<QrAgent> receptionists = agents.stream()
                    .filter(a -> a.getRole() == QrAgent.AgentRole.receptionist)
                    .toList();
                QrAgent serviceTeacher = agents.stream()
                    .filter(a -> a.getRole() == QrAgent.AgentRole.service)
                    .findFirst().orElse(null);

                if (receptionists.isEmpty() || serviceTeacher == null) continue;

                LocalDateTime todayStart = LocalDateTime.now()
                    .withHour(0).withMinute(0).withSecond(0).withNano(0);

                for (QrAgent rec : receptionists) {
                    // Find customers added by this receptionist today
                    List<Customer> customers = customerRepo
                        .findByAddedAgentAndAddTimeAfter(rec.getAgentUserid(), todayStart);

                    for (Customer c : customers) {
                        Map<String, Object> event = new LinkedHashMap<>();
                        event.put("customer_id", c.getId().toString());
                        event.put("from_userid", rec.getAgentUserid());
                        event.put("to_userid", serviceTeacher.getAgentUserid());
                        event.put("external_userid", c.getExternalUserid());
                        event.put("state", qr.getSchoolId());

                        redisTemplate.opsForStream().add(
                            RedisConfig.TRANSFER_STREAM_KEY,
                            Map.of("event", objectMapper.writeValueAsString(event)));
                        totalTransfers++;
                    }
                }
            } catch (Exception e) {
                log.error("在职继承失败: qrCodeId={}", qr.getId(), e);
            }
        }
        log.info("在职继承定时任务完成: 共发起 {} 条转移", totalTransfers);
    }
}
```

- [ ] **Step 2: Add required customerRepo query method**

In `CustomerRepository.java`, add:
```java
List<Customer> findByAddedAgentAndAddTimeAfter(String addedAgent, LocalDateTime addTime);
```

- [ ] **Step 3: Add QrCodeRepository.findByStatus**

In `QrCodeRepository.java`, add:
```java
List<QrCode> findByStatus(QrCode.QrCodeStatus status);
```

- [ ] **Step 4: Add transfer preview + trigger endpoints to QrCodeController**

```java
@GetMapping("/api/qrcodes/{id}/transfer/preview")
@ResponseBody
public Map<String, Object> transferPreview(@PathVariable Long id) {
    Map<String, Object> result = new LinkedHashMap<>();
    try {
        QrCode qr = qrCodeService.getById(id);
        List<QrAgent> agents = qrAgentRepo.findByQrCodeId(qr.getId());
        long recCount = agents.stream()
            .filter(a -> a.getRole() == QrAgent.AgentRole.receptionist).count();
        boolean hasService = agents.stream()
            .anyMatch(a -> a.getRole() == QrAgent.AgentRole.service);

        if (!hasService) {
            result.put("error", "该活码未配置服务老师");
            return result;
        }

        LocalDateTime todayStart = LocalDateTime.now()
            .withHour(0).withMinute(0).withSecond(0).withNano(0);
        long customerCount = 0;
        for (QrAgent a : agents) {
            if (a.getRole() == QrAgent.AgentRole.receptionist) {
                customerCount += customerRepo
                    .countByAddedAgentAndAddTimeAfter(a.getAgentUserid(), todayStart);
            }
        }
        result.put("receptionistCount", recCount);
        result.put("customerCount", customerCount);
    } catch (Exception e) {
        result.put("error", e.getMessage());
    }
    return result;
}

@PostMapping("/api/qrcodes/{id}/transfer/trigger")
@ResponseBody
public Map<String, Object> transferTrigger(@PathVariable Long id) {
    // Same logic as InheritanceJob but for single QR code
    // Reuses the job logic or delegates to a shared service
    // ...
}
```

- [ ] **Step 5: Compile and commit**

```bash
cd D:/ClaudeCode/HuoMa && mvn compile -q
git add src/main/java/com/bookstore/qrcode/job/InheritanceJob.java src/main/java/com/bookstore/qrcode/controller/QrCodeController.java src/main/java/com/bookstore/qrcode/repository/CustomerRepository.java src/main/java/com/bookstore/qrcode/repository/QrCodeRepository.java
git commit -m "feat: add InheritanceJob (scheduled) and transfer preview/trigger endpoints"
```

---

### Task 17: Transfer Records Page

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/controller/QrCodeController.java`
- Create: `src/main/resources/templates/qrcode/transfers.html`

- [ ] **Step 1: Add transfer list endpoint to QrCodeController**

```java
@GetMapping("/{id}/transfers")
public String transfers(@PathVariable Long id,
                         @RequestParam(defaultValue = "0") int page,
                         Model model) {
    QrCode qr = qrCodeService.getById(id);
    model.addAttribute("qr", qr);

    Page<CustomerTransfer> transfers = transferRepo
        .findByQrCodeIdOrderByTransferTimeDesc(id,
            PageRequest.of(page, 20));
    model.addAttribute("transfers", transfers);

    // Name map
    Map<String, String> nameMap = new HashMap<>();
    for (CustomerTransfer t : transfers.getContent()) {
        nameMap.putIfAbsent(t.getFromUserid(),
            employeeRepo.findByUserid(t.getFromUserid())
                .map(Employee::getName).orElse(t.getFromUserid()));
        nameMap.putIfAbsent(t.getToUserid(),
            employeeRepo.findByUserid(t.getToUserid())
                .map(Employee::getName).orElse(t.getToUserid()));
    }
    model.addAttribute("nameMap", nameMap);

    return "qrcode/transfers";
}
```

- [ ] **Step 2: Write qrcode/transfers.html**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout :: layout(title='在职继承记录', content=~{::main})}">
<main>
<div class="card p-4">
    <h5>🔄 在职继承记录 — <span th:text="${qr.schoolName}"></span></h5>
    <table class="table table-sm mt-3">
        <thead><tr><th>客户ID</th><th>转出方</th><th>转入方</th><th>状态</th><th>时间</th></tr></thead>
        <tbody>
            <tr th:each="t : ${transfers.content}">
                <td th:text="${t.customerId}"></td>
                <td th:text="${nameMap.getOrDefault(t.fromUserid, t.fromUserid)}"></td>
                <td th:text="${nameMap.getOrDefault(t.toUserid, t.toUserid)}"></td>
                <td>
                    <span class="badge"
                          th:classappend="${t.status.name() == 'confirmed' ? 'bg-success'
                              : t.status.name() == 'rejected' ? 'bg-danger'
                              : 'bg-secondary'}"
                          th:text="${t.status.name()}"></span>
                </td>
                <td th:text="${t.transferTime}"></td>
            </tr>
        </tbody>
    </table>
</div>
</main>
</html>
```

- [ ] **Step 3: Add fields and imports to QrCodeController**

```java
private final CustomerTransferRepository transferRepo;
private final EmployeeRepository employeeRepo;
// Already has employeeRepo from existing code
```

- [ ] **Step 4: Compile and commit**

```bash
cd D:/ClaudeCode/HuoMa && mvn compile -q
git add src/main/java/com/bookstore/qrcode/controller/QrCodeController.java src/main/resources/templates/qrcode/transfers.html
git commit -m "feat: add transfer records page"
```

---

## Phase 7: List Page Enhancements

### Task 18: Left Sidebar Group Tree in List Page

**Files:**
- Modify: `src/main/resources/templates/qrcode/list.html`
- Modify: `src/main/java/com/bookstore/qrcode/controller/QrCodeController.java` (add tree data)

The list page adds:
1. Left sidebar with City → District → Alliance → QR Codes tree
2. "分组" column in table
3. Multi-select checkboxes with batch config button

This is the largest single-page change. Follow the `create.html` autocomplete pattern for the tree UI.

- [ ] **Step 1: Add tree data endpoint**

```java
@GetMapping("/api/qrcodes/tree")
@ResponseBody
public List<Map<String, Object>> tree() {
    // Build city → district → group → qrcode tree
    // ...
}
```

- [ ] **Step 2: Modify qrcode/list.html**

Add left sidebar + tree + batch actions. Follow existing Bootstrap patterns from detail.html.

- [ ] **Step 3: Compile and commit**

```bash
cd D:/ClaudeCode/HuoMa && mvn compile -q
git add src/main/resources/templates/qrcode/list.html src/main/java/com/bookstore/qrcode/controller/QrCodeController.java
git commit -m "feat: add group tree sidebar + batch config to qrcode list page"
```

---

## Task 19: SystemConfigRepository (for default welcome text)

**Files:**
- Modify: `src/main/java/com/bookstore/qrcode/repository/SystemConfigRepository.java` (add findByConfigKey if missing)

- [ ] **Step 1: Verify or add method**

```java
Optional<SystemConfig> findByConfigKey(String configKey);
```

If this method already exists in SystemConfigRepository, skip creation.

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/bookstore/qrcode/repository/SystemConfigRepository.java
git commit -m "feat: add SystemConfigRepository.findByConfigKey"
```

---

## Task 20: Integration Test & Verification

**Files:**
- Create: `src/test/java/com/bookstore/qrcode/integration/WelcomeFormInheritanceIntegrationTest.java`

- [ ] **Step 1: Write integration test skeleton**

```java
package com.bookstore.qrcode.integration;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
public class WelcomeFormInheritanceIntegrationTest {

    @Autowired private FormTemplateService formTemplateService;
    @Autowired private FormTemplateRepository formTemplateRepo;
    @Autowired private QrCodeGroupService groupService;
    @Autowired private QrCodeGroupRepository groupRepo;

    @Test
    void formTemplateCrud() {
        FormTemplate t = formTemplateService.create(
            "测试模板", "测试", "[{\"name\":\"test\",\"label\":\"测试\",\"type\":\"text\"}]",
            "{\"test\":\"tag\"}", "{{test}}");
        assertThat(t.getId()).isNotNull();

        FormTemplate found = formTemplateService.getById(t.getId());
        assertThat(found.getName()).isEqualTo("测试模板");

        formTemplateService.update(t.getId(), "更新后", null, null, null, null);
        assertThat(formTemplateService.getById(t.getId()).getName()).isEqualTo("更新后");

        formTemplateService.delete(t.getId());
        assertThat(formTemplateRepo.findById(t.getId())).isEmpty();
    }

    @Test
    void qrCodeGroupCrud() {
        QrCodeGroup g = groupService.create("测试联盟", "兰州市", "城关区", "欢迎语", null);
        assertThat(g.getId()).isNotNull();
        groupService.delete(g.getId());
    }
}
```

- [ ] **Step 2: Run tests**

```bash
cd D:/ClaudeCode/HuoMa && mvn test -pl . -Dtest=WelcomeFormInheritanceIntegrationTest -DfailIfNoTests=false
```

- [ ] **Step 3: Full build verification**

```bash
cd D:/ClaudeCode/HuoMa && mvn clean compile -q
```

Expected: BUILD SUCCESS, all existing + new code compiles without error.

- [ ] **Step 4: Final commit**

```bash
git add src/test/
git commit -m "test: add WelcomeFormInheritanceIntegrationTest"
```

---

## Summary

| Task | Files Created | Files Modified | Phase |
|------|--------------|----------------|-------|
| 1 | 1 SQL | 1 (WecomApiClient) | Foundation |
| 2 | 3 (entity/repo/service) | 0 | Foundation |
| 3 | 2 (entity/repo) | 0 | Foundation |
| 4 | 3 (entity/repo/service) | 0 | Foundation |
| 5 | 3 (controller + 2 HTML) | 0 | Admin UI |
| 6 | 2 (controller + HTML) | 0 | Admin UI |
| 7 | 3 (controller + 2 HTML) | 0 | H5 Form |
| 8 | 0 | 2 (TagWorker + TagService) | Form Tag |
| 9 | 0 | 1 (RedisConfig) | Welcome |
| 10 | 1 (worker) | 0 | Welcome |
| 11 | 0 | 1 (CallbackWorker) | Welcome |
| 12 | 0 | 1 (QrCodeController) | Config |
| 13 | 0 | 2 (detail.html + controller) | Detail UI |
| 14 | 0 | 1 (RedisConfig) | Inheritance |
| 15 | 1 (worker) | 0 | Inheritance |
| 16 | 1 (job) | 3 (controller + 2 repo) | Inheritance |
| 17 | 1 (HTML) | 1 (controller) | Records |
| 18 | 0 | 2 (list.html + controller) | List UI |
| 19 | 0 | 1 (repo) | Config |
| 20 | 1 (test) | 0 | Verify |

**Total: 21 new files, 17 modified files, 20 tasks**

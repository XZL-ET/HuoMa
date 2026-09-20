package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.FormTemplate;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.entity.QrCodeGroup;
import com.bookstore.qrcode.entity.School;
import com.bookstore.qrcode.entity.SchoolCategory;
import com.bookstore.qrcode.entity.SystemConfig;
import com.bookstore.qrcode.repository.FormTemplateRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.repository.QrCodeGroupRepository;
import com.bookstore.qrcode.repository.SchoolCategoryRepository;
import com.bookstore.qrcode.repository.SchoolRepository;
import com.bookstore.qrcode.repository.SystemConfigRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class FormTemplateService {

    private final FormTemplateRepository templateRepo;
    private final QrCodeRepository qrCodeRepo;
    private final QrCodeGroupRepository groupRepo;
    private final SchoolCategoryRepository categoryRepo;
    private final SchoolRepository schoolRepo;
    private final SystemConfigRepository systemConfigRepo;
    private final ObjectMapper objectMapper;

    /** 县区码默认表单模板的固定名称，find-or-create 锚点 */
    public static final String COUNTY_TEMPLATE_NAME = "县区码默认模板";

    /** 补发表单默认模板的固定名称，find-or-create 锚点 */
    public static final String RESEND_TEMPLATE_NAME = "补发表单默认模板";

    /** 图片版收集表单模板的固定名称，find-or-create 锚点 */
    public static final String IMAGE_TEMPLATE_NAME = "收集表单·图片版";

    /** 收集表单全局版本配置键：text=原解析链，image=全站覆盖图片版模板 */
    public static final String VERSION_CONFIG_KEY = "collection_form_version";

    /** 图片版模式取值 */
    public static final String IMAGE_VERSION = "image";

    /** 图片版模板的年级选项（含高中，共 12 个） */
    public static final List<String> GRADE_OPTIONS = List.of(
        "一年级", "二年级", "三年级", "四年级", "五年级", "六年级",
        "七年级", "八年级", "九年级", "高一", "高二", "高三");

    /** 图片版表单文案配置键（system_config），缺省/空白回退默认值 */
    public static final String IMAGE_HEADING_LINE1_KEY = "image.heading.line1";
    public static final String IMAGE_HEADING_LINE2_KEY = "image.heading.line2";
    public static final String IMAGE_SUBTITLE_KEY = "image.subtitle";
    public static final String IMAGE_GRADE_HINT_KEY = "image.grade.hint";
    public static final String IMAGE_BUTTON_TEXT_KEY = "image.button.text";
    public static final String PRIVACY_NOTICE_KEY = "privacy.notice";

    /** 图片版文案默认值（imageSubtitle 默认空 = 隐藏副标题） */
    public static final String DEFAULT_IMAGE_HEADING_LINE1 = "您是 {school} 的学生";
    public static final String DEFAULT_IMAGE_HEADING_LINE2 = "请您选择正在使用的教材版本";
    public static final String DEFAULT_IMAGE_GRADE_HINT = "请选择年级";
    public static final String DEFAULT_IMAGE_BUTTON_TEXT = "确认";
    public static final String DEFAULT_PRIVACY_NOTICE = "您填写的信息仅用于新华书店教材发行对接服务，不会向第三方泄露或用于其他目的。提交即表示您同意我们收集以下信息。";

    public List<FormTemplate> listAll() {
        return templateRepo.findAllByOrderByName();
    }

    /**
     * 图片版表单文案（含隐私声明），从 system_config 读取，缺省/空白回退默认值。
     * 返回的 key 直接作为前端 model 属性名；imageSubtitle 为空字符串表示隐藏副标题。
     */
    public Map<String, String> resolveImageCopy() {
        Map<String, String> copy = new LinkedHashMap<>();
        copy.put("imageHeadingLine1", resolveCopy(IMAGE_HEADING_LINE1_KEY, DEFAULT_IMAGE_HEADING_LINE1));
        copy.put("imageHeadingLine2", resolveCopy(IMAGE_HEADING_LINE2_KEY, DEFAULT_IMAGE_HEADING_LINE2));
        copy.put("imageSubtitle", resolveCopy(IMAGE_SUBTITLE_KEY, ""));
        copy.put("imageGradeHint", resolveCopy(IMAGE_GRADE_HINT_KEY, DEFAULT_IMAGE_GRADE_HINT));
        copy.put("imageButtonText", resolveCopy(IMAGE_BUTTON_TEXT_KEY, DEFAULT_IMAGE_BUTTON_TEXT));
        copy.put("privacyNotice", resolveCopy(PRIVACY_NOTICE_KEY, DEFAULT_PRIVACY_NOTICE));
        return copy;
    }

    private String resolveCopy(String key, String defaultValue) {
        return systemConfigRepo.findByConfigKey(key)
            .map(SystemConfig::getConfigValue)
            .filter(v -> v != null && !v.isBlank())
            .orElse(defaultValue);
    }

    public FormTemplate getById(Long id) {
        return templateRepo.findById(id)
            .orElseThrow(() -> new RuntimeException("表单模板不存在: " + id));
    }

    @Transactional
    public FormTemplate create(String name, String description, String subtitle,
                                String cardTitle, String cardDesc, String cardPicUrl,
                                String fields, String tagMapping, String remarkTemplate) {
        return templateRepo.save(FormTemplate.builder()
            .name(name).description(description).subtitle(subtitle)
            .cardTitle(cardTitle).cardDesc(cardDesc).cardPicUrl(cardPicUrl)
            .fields(fields).tagMapping(tagMapping).remarkTemplate(remarkTemplate).build());
    }

    /**
     * 幂等获取「县区码默认模板」：不存在则创建。
     * 并发兜底靠 V16 唯一索引（不产生重复行）；catch 里的重查仅在
     * 胜者行已提交且本事务快照可见时恢复，真并发下可能抛「创建失败」，
     * 由 POST 兜底为友好错误后操作员重试。
     */
    @Transactional
    public FormTemplate ensureCountyTemplate() {
        return templateRepo.findByName(COUNTY_TEMPLATE_NAME)
            .orElseGet(() -> {
                try {
                    return create(COUNTY_TEMPLATE_NAME, "县区码默认收集模板",
                        null, null, null, null,
                        "[]", "{\"grade\":\"tag\",\"class\":\"tag\"}", null);
                } catch (DataIntegrityViolationException e) {
                    return templateRepo.findByName(COUNTY_TEMPLATE_NAME)
                        .orElseThrow(() -> new RuntimeException("县区码默认模板创建失败", e));
                }
            });
    }

    /**
     * 幂等获取「补发表单默认模板」：年级（单选）+ 科目（多选），不存在则创建。
     * 文案/字段/标签映射均可由后台模板编辑器自定义。
     */
    @Transactional
    public FormTemplate ensureResendTemplate() {
        return templateRepo.findByName(RESEND_TEMPLATE_NAME)
            .orElseGet(() -> {
                try {
                    String fields = "[{\"name\":\"grade\",\"label\":\"年级\",\"type\":\"select\",\"required\":true,"
                        + "\"options\":[\"一年级\",\"二年级\",\"三年级\",\"四年级\",\"五年级\",\"六年级\","
                        + "\"初一\",\"初二\",\"初三\",\"高一\",\"高二\",\"高三\"]},"
                        + "{\"name\":\"subject\",\"label\":\"科目（可多选）\",\"type\":\"multiselect\",\"required\":true,"
                        + "\"options\":[\"语文\",\"数学\",\"英语\",\"物理\",\"化学\",\"生物\",\"政治\",\"历史\",\"地理\"]}]";
                    return create(RESEND_TEMPLATE_NAME, "补发表单·送试题模板",
                        "填写年级和科目，免费领本年级试题", null, null, null,
                        fields, "{\"grade\":\"tag\",\"subject\":\"tag:科目\"}", null);
                } catch (DataIntegrityViolationException e) {
                    return templateRepo.findByName(RESEND_TEMPLATE_NAME)
                        .orElseThrow(() -> new RuntimeException("补发表单模板创建失败", e));
                }
            });
    }

    /**
     * 幂等获取「收集表单·图片版」：仅一个年级下拉（12 选项，含高中），
     * 提交只收集年级；封面图联动由 fill.html 依据字段名 grade 读取
     * grade_textbook_cover 表（见 {@code GradeTextbookCoverService}）。
     */
    @Transactional
    public FormTemplate ensureImageTemplate() {
        return templateRepo.findByName(IMAGE_TEMPLATE_NAME)
            .orElseGet(() -> {
                try {
                    Map<String, Object> gradeField = new LinkedHashMap<>();
                    gradeField.put("name", "grade");
                    gradeField.put("label", "年级");
                    gradeField.put("type", "select");
                    gradeField.put("required", true);
                    gradeField.put("options", GRADE_OPTIONS);
                    String fields = objectMapper.writeValueAsString(List.of(gradeField));
                    return create(IMAGE_TEMPLATE_NAME, "图片版·按年级展示语文课本封面",
                        null, null, null, null,
                        fields, "{\"grade\":\"tag\"}", null);
                } catch (DataIntegrityViolationException e) {
                    return templateRepo.findByName(IMAGE_TEMPLATE_NAME)
                        .orElseThrow(() -> new RuntimeException("图片版模板创建失败", e));
                } catch (JsonProcessingException e) {
                    throw new RuntimeException("图片版模板字段序列化失败", e);
                }
            });
    }

    /** 是否处于图片版模式（全站覆盖）。缺省/非 image 一律视为 text 模式。 */
    public boolean isImageMode() {
        return systemConfigRepo.findById(VERSION_CONFIG_KEY)
            .map(c -> IMAGE_VERSION.equals(c.getConfigValue()))
            .orElse(false);
    }

    /**
     * 解析活码应使用的表单模板 id。
     * image 模式直接返回图片版模板；text 模式与原「活码 → 分组 → 学校分类」解析链逐字一致。
     * 均未命中返回 null（调用方按「无表单」处理）。
     */
    public Long resolveTemplateId(QrCode qr) {
        if (qr == null) return null;
        // 图片版全局覆盖，但县区码活码保留自己的三级级联，不套用
        if (isImageMode() && !isCountyCode(qr)) {
            return ensureImageTemplate().getId();
        }
        Long formTemplateId = qr.getFormTemplateId();
        if (formTemplateId == null && qr.getGroupId() != null) {
            QrCodeGroup group = groupRepo.findById(qr.getGroupId()).orElse(null);
            if (group != null) formTemplateId = group.getDefaultFormTemplateId();
        }
        if (formTemplateId == null && qr.getSchoolId() != null) {
            School school = schoolRepo.findBySchoolIdAndDeletedFalse(qr.getSchoolId()).orElse(null);
            if (school != null && school.getCategoryId() != null) {
                SchoolCategory cat = categoryRepo.findById(school.getCategoryId()).orElse(null);
                if (cat != null) formTemplateId = cat.getDefaultFormTemplateId();
            }
        }
        return formTemplateId;
    }

    private boolean isCountyCode(QrCode qr) {
        return qr.getSchoolId() != null
            && qr.getSchoolId().startsWith(SchoolSelectionService.COUNTY_PREFIX);
    }

    /** 图片版支持的学段；未分类学段无对应封面，回退全部年级 */
    private static final Set<String> IMAGE_STAGES = Set.of("小学", "初中", "高中", "幼儿园");

    /**
     * 图片版模式：按活码绑定学校的学段解析年级选项，复用县区码学段→年级映射；
     * 无学段/学段不在支持范围（幼儿园等）返回全部年级。
     */
    public List<String> resolveImageGradeOptions(QrCode qr) {
        if (qr != null && qr.getSchoolId() != null) {
            School school = schoolRepo.findBySchoolIdAndDeletedFalse(qr.getSchoolId()).orElse(null);
            if (school != null && school.getCategoryId() != null) {
                SchoolCategory cat = categoryRepo.findById(school.getCategoryId()).orElse(null);
                if (cat != null && IMAGE_STAGES.contains(cat.getName())) {
                    List<String> filtered = SchoolSelectionService.GRADE_MAP.get(cat.getName());
                    if (filtered != null) return filtered;
                }
            }
        }
        return GRADE_OPTIONS;
    }

    /**
     * 图片版模式：把 fields JSON 中 grade 字段的 options 替换为按学段解析出的年级。
     */
    public String filterImageGradeOptions(String fieldsJson, QrCode qr) {
        List<String> grades = resolveImageGradeOptions(qr);
        try {
            List<Map<String, Object>> fields = objectMapper.readValue(fieldsJson,
                new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> f : fields) {
                if ("grade".equals(f.get("name"))) {
                    f.put("options", grades);
                }
            }
            return objectMapper.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            log.warn("过滤图片版年级选项失败，回退原字段", e);
            return fieldsJson;
        }
    }

    @Transactional
    public FormTemplate update(Long id, String name, String description, String subtitle,
                                String cardTitle, String cardDesc, String cardPicUrl,
                                String fields, String tagMapping, String remarkTemplate) {
        FormTemplate t = getById(id);
        if (name != null) t.setName(name);
        if (description != null) t.setDescription(description);
        if (subtitle != null) t.setSubtitle(subtitle);
        if (cardTitle != null) t.setCardTitle(cardTitle);
        if (cardDesc != null) t.setCardDesc(cardDesc);
        if (cardPicUrl != null) t.setCardPicUrl(cardPicUrl);
        if (fields != null) t.setFields(fields);
        if (tagMapping != null) t.setTagMapping(tagMapping);
        if (remarkTemplate != null) t.setRemarkTemplate(remarkTemplate);
        return templateRepo.save(t);
    }

    @Transactional
    public void delete(Long id) {
        if (!templateRepo.existsById(id))
            throw new RuntimeException("表单模板不存在: " + id);

        // 检查引用：活码 / 分组 / 分类
        long qrRefs = qrCodeRepo.countByFormTemplateId(id);
        long groupRefs = groupRepo.countByDefaultFormTemplateId(id);
        long categoryRefs = categoryRepo.countByDefaultFormTemplateId(id);
        if (qrRefs + groupRefs + categoryRefs > 0) {
            throw new RuntimeException(String.format(
                "无法删除：该模板被 %d 个活码、%d 个分组、%d 个分类引用",
                qrRefs, groupRefs, categoryRefs));
        }

        templateRepo.deleteById(id);
    }
}

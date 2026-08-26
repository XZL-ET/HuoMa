package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.FormTemplate;
import com.bookstore.qrcode.repository.FormTemplateRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.repository.QrCodeGroupRepository;
import com.bookstore.qrcode.repository.SchoolCategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FormTemplateService {

    private final FormTemplateRepository templateRepo;
    private final QrCodeRepository qrCodeRepo;
    private final QrCodeGroupRepository groupRepo;
    private final SchoolCategoryRepository categoryRepo;

    /** 县区码默认表单模板的固定名称，find-or-create 锚点 */
    public static final String COUNTY_TEMPLATE_NAME = "县区码默认模板";

    public List<FormTemplate> listAll() {
        return templateRepo.findAllByOrderByName();
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
     * 并发兜底靠 V15 唯一索引（不产生重复行）；catch 里的重查仅在
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

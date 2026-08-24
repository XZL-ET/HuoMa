package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.FormTemplate;
import com.bookstore.qrcode.repository.FormTemplateRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.repository.QrCodeGroupRepository;
import com.bookstore.qrcode.repository.SchoolCategoryRepository;
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
    private final QrCodeRepository qrCodeRepo;
    private final QrCodeGroupRepository groupRepo;
    private final SchoolCategoryRepository categoryRepo;

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

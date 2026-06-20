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

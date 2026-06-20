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

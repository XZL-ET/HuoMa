package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.entity.QrCodeGroup;
import com.bookstore.qrcode.repository.QrCodeGroupRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class QrCodeGroupService {

    private final QrCodeGroupRepository groupRepo;
    private final QrCodeRepository qrCodeRepo;

    public List<QrCodeGroup> listAll() {
        return groupRepo.findAllByOrderByName();
    }

    /** 分页搜索联盟（关键词模糊匹配名称/市州/区县） */
    public Page<QrCodeGroup> search(String keyword, Pageable pageable) {
        return groupRepo.search(keyword, pageable);
    }

    public QrCodeGroup getById(Long id) {
        return groupRepo.findById(id)
            .orElseThrow(() -> new RuntimeException("分组不存在: " + id));
    }

    @Transactional
    public QrCodeGroup create(String name, String regionCity, String regionDistrict,
                               String defaultWelcomeText, Long defaultFormTemplateId,
                               Long qrCodeId, String schoolList) {
        QrCodeGroup g = groupRepo.save(QrCodeGroup.builder()
            .name(name).regionCity(regionCity).regionDistrict(regionDistrict)
            .defaultWelcomeText(defaultWelcomeText != null && defaultWelcomeText.isBlank()
                ? null : defaultWelcomeText).defaultFormTemplateId(defaultFormTemplateId)
            .qrCodeId(qrCodeId).schoolList(schoolList)
            .build());
        // 同步活码的 group_id ← 联盟 ID（用于树形导航）
        if (qrCodeId != null) {
            syncQrCodeGroup(qrCodeId, g.getId());
        }
        return g;
    }

    @Transactional
    public QrCodeGroup update(Long id, String name, String regionCity, String regionDistrict,
                               String defaultWelcomeText, Long defaultFormTemplateId,
                               Long qrCodeId, String schoolList) {
        QrCodeGroup g = getById(id);
        if (name != null) g.setName(name);
        if (regionCity != null) g.setRegionCity(regionCity);
        if (regionDistrict != null) g.setRegionDistrict(regionDistrict);
        if (defaultWelcomeText != null)
            g.setDefaultWelcomeText(defaultWelcomeText.isBlank() ? null : defaultWelcomeText);
        g.setDefaultFormTemplateId(defaultFormTemplateId);

        // 处理活码关联变更
        Long oldQrCodeId = g.getQrCodeId();
        if (qrCodeId != null && !qrCodeId.equals(oldQrCodeId)) {
            // 新活码 → 绑定本联盟
            syncQrCodeGroup(qrCodeId, id);
            // 旧活码 → 解除绑定
            if (oldQrCodeId != null) {
                clearQrCodeGroup(oldQrCodeId);
            }
        } else if (qrCodeId == null && oldQrCodeId != null) {
            // 解除关联
            clearQrCodeGroup(oldQrCodeId);
        }

        g.setQrCodeId(qrCodeId);
        g.setSchoolList(schoolList);
        return groupRepo.save(g);
    }

    @Transactional
    public void delete(Long id) {
        QrCodeGroup g = getById(id);
        // 解除关联活码的 group_id
        if (g.getQrCodeId() != null) {
            clearQrCodeGroup(g.getQrCodeId());
        }
        // 同时解除所有 group_id 指向本联盟的活码
        List<QrCode> linked = qrCodeRepo.findByGroupIdOrderBySchoolName(id);
        for (QrCode qr : linked) {
            qr.setGroupId(null);
            qrCodeRepo.save(qr);
        }
        groupRepo.deleteById(id);
    }

    // ── 私有辅助 ──

    private void syncQrCodeGroup(Long qrCodeId, Long groupId) {
        qrCodeRepo.findById(qrCodeId).ifPresent(qr -> {
            qr.setGroupId(groupId);
            qrCodeRepo.save(qr);
        });
    }

    private void clearQrCodeGroup(Long qrCodeId) {
        qrCodeRepo.findById(qrCodeId).ifPresent(qr -> {
            qr.setGroupId(null);
            qrCodeRepo.save(qr);
        });
    }
}

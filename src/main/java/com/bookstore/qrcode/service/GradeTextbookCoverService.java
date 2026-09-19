package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.GradeTextbookCover;
import com.bookstore.qrcode.repository.GradeTextbookCoverRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GradeTextbookCoverService {

    private final GradeTextbookCoverRepository repo;

    @Value("${upload.grade-cover-dir:./data/uploads/grade-covers}")
    private String gradeCoverDir;

    /** 幂等保存某年级封面图：已有则更新，否则新建；重传时清理旧文件。 */
    @Transactional
    public GradeTextbookCover save(String gradeName, String imageUrl) {
        GradeTextbookCover cover = repo.findByGradeName(gradeName)
            .orElseGet(() -> GradeTextbookCover.builder().gradeName(gradeName).build());
        String oldUrl = cover.getImageUrl();
        cover.setImageUrl(imageUrl);
        GradeTextbookCover saved = repo.save(cover);
        if (oldUrl != null && !oldUrl.equals(imageUrl)) {
            deleteFile(oldUrl);
        }
        return saved;
    }

    /** 返回所有封面图映射：年级名 → 图片 URL（按年级名保持插入顺序）。 */
    @Transactional(readOnly = true)
    public Map<String, String> listAsMap() {
        Map<String, String> map = new LinkedHashMap<>();
        repo.findAll().forEach(c -> map.put(c.getGradeName(), c.getImageUrl()));
        return map;
    }

    /** 删除某年级封面图（不存在则静默忽略），并清理磁盘文件。 */
    @Transactional
    public void delete(String gradeName) {
        repo.findByGradeName(gradeName).ifPresent(cover -> {
            repo.delete(cover);
            deleteFile(cover.getImageUrl());
        });
    }

    /** 将 /uploads/grade-covers/{filename} 映射回磁盘路径并删除，失败仅告警不影响主流程。 */
    private void deleteFile(String url) {
        if (url == null || url.isBlank()) return;
        try {
            String filename = url.substring(url.lastIndexOf('/') + 1);
            Path dir = Path.of(gradeCoverDir).toAbsolutePath().normalize();
            Files.deleteIfExists(dir.resolve(filename));
        } catch (Exception e) {
            log.warn("删除封面图文件失败: {}", url, e);
        }
    }
}

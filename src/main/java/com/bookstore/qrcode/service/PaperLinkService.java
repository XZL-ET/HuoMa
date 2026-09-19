package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.PaperLink;
import com.bookstore.qrcode.repository.PaperLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 试题内容链接（年级 × 科目）服务。
 *
 * @author Bookstore Dev
 */
@Service
@RequiredArgsConstructor
public class PaperLinkService {

    private final PaperLinkRepository paperLinkRepo;

    public List<PaperLink> listAll() {
        return paperLinkRepo.findAllByOrderByGradeAscSubjectAsc();
    }

    /** 保存（按 年级+科目 唯一，存在则更新） */
    public PaperLink save(String grade, String subject, String title, String paperUrl) {
        PaperLink existing = paperLinkRepo.findByGradeAndSubject(grade, subject).orElse(null);
        if (existing != null) {
            existing.setTitle(title);
            existing.setPaperUrl(paperUrl);
            return paperLinkRepo.save(existing);
        }
        return paperLinkRepo.save(PaperLink.builder()
            .grade(grade).subject(subject).title(title).paperUrl(paperUrl).build());
    }

    public void delete(Long id) {
        paperLinkRepo.deleteById(id);
    }

    /** 按年级 + 多科目批量查询，返回命中的链接列表（用于提交后返回「领取」入口） */
    public List<PaperLink> findLinks(String grade, List<String> subjects) {
        List<PaperLink> result = new ArrayList<>();
        if (grade == null || subjects == null) return result;
        for (String subject : subjects) {
            paperLinkRepo.findByGradeAndSubject(grade, subject).ifPresent(result::add);
        }
        return result;
    }
}

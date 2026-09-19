package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.PaperLink;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

/**
 * 试题内容链接（年级 × 科目）数据访问层。
 *
 * @author Bookstore Dev
 */
public interface PaperLinkRepository extends JpaRepository<PaperLink, Long> {

    Optional<PaperLink> findByGradeAndSubject(String grade, String subject);

    List<PaperLink> findAllByOrderByGradeAscSubjectAsc();
}

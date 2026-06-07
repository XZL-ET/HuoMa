package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TagRepository extends JpaRepository<Tag, Long> {
    List<Tag> findByType(Tag.TagType type);
    List<Tag> findByParentId(Long parentId);
    Tag findByName(String name);
}

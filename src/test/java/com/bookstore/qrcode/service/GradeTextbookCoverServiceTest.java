package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.GradeTextbookCover;
import com.bookstore.qrcode.repository.GradeTextbookCoverRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(GradeTextbookCoverService.class)
@Sql(scripts = "classpath:schema-test.sql")
@DisplayName("GradeTextbookCoverService 年级课本封面")
class GradeTextbookCoverServiceTest {

    @Autowired private GradeTextbookCoverService service;
    @Autowired private GradeTextbookCoverRepository repo;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "gradeCoverDir", tempDir.toString());
    }

    @Test
    void save_首次保存_创建新记录() {
        GradeTextbookCover c = service.save("一年级", "/uploads/grade-covers/a.png");
        assertThat(c.getGradeName()).isEqualTo("一年级");
        assertThat(c.getImageUrl()).isEqualTo("/uploads/grade-covers/a.png");
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    void save_同年级再次保存_更新不重复() {
        service.save("一年级", "/uploads/grade-covers/a.png");
        service.save("一年级", "/uploads/grade-covers/b.png");
        assertThat(repo.count()).isEqualTo(1);
        assertThat(repo.findByGradeName("一年级").orElseThrow().getImageUrl())
                .isEqualTo("/uploads/grade-covers/b.png");
    }

    @Test
    void listAsMap_返回年级到URL映射() {
        service.save("一年级", "/a.png");
        service.save("八年级", "/b.png");
        Map<String, String> map = service.listAsMap();
        assertThat(map).hasSize(2)
                .containsEntry("一年级", "/a.png")
                .containsEntry("八年级", "/b.png");
    }

    @Test
    void listAsMap_无记录时_返回空映射() {
        assertThat(service.listAsMap()).isEmpty();
    }

    @Test
    void delete_删除后不再返回() {
        service.save("一年级", "/a.png");
        service.delete("一年级");
        assertThat(repo.count()).isZero();
        assertThat(service.listAsMap()).isEmpty();
    }

    @Test
    void delete_不存在时_不报错() {
        service.delete("不存在的年级");
        assertThat(repo.count()).isZero();
    }

    @Test
    void delete_删除后清理磁盘文件() throws Exception {
        Path file = tempDir.resolve("a.png");
        Files.write(file, new byte[]{1});
        service.save("一年级", "/uploads/grade-covers/a.png");
        service.delete("一年级");
        assertThat(repo.count()).isZero();
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void save_重传后清理旧文件() throws Exception {
        Path oldFile = tempDir.resolve("old.png");
        Path newFile = tempDir.resolve("new.png");
        Files.write(oldFile, new byte[]{1});
        Files.write(newFile, new byte[]{2});
        service.save("一年级", "/uploads/grade-covers/old.png");
        service.save("一年级", "/uploads/grade-covers/new.png");
        assertThat(Files.exists(oldFile)).isFalse();
        assertThat(Files.exists(newFile)).isTrue();
        assertThat(repo.findByGradeName("一年级").orElseThrow().getImageUrl())
                .isEqualTo("/uploads/grade-covers/new.png");
    }
}

package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Tag;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TagService 标签创建")
class TagServiceTest {

    @Mock private TagRepository tagRepo;
    @Mock private CustomerTagRepository customerTagRepo;
    @Mock private TagInsertService tagInsertService;
    @Mock private CustomerTagInsertService customerTagInsertService;
    @Mock private CustomerRepository customerRepo;
    @Mock private QrCodeRepository qrCodeRepo;
    @Mock private FormTemplateRepository formTemplateRepo;
    @Mock private FormSubmissionRepository formSubmissionRepo;
    @Mock private WecomApiClient wecomApi;
    @Mock private ObjectMapper objectMapper;
    @Mock private AlertService alertService;

    @InjectMocks private TagService tagService;

    @Test
    @DisplayName("getOrCreateTag — 并发插入冲突时重查复用已有记录")
    void reusesExistingOnConcurrentInsertConflict() {
        when(tagRepo.findFirstByNameAndGroupKeyword("北京", "市州"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(Tag.builder().id(9L).name("北京")
                .groupKeyword("市州").type(Tag.TagType.system).wecomTagId("t9").build()));
        doThrow(new DataIntegrityViolationException("dup uk_tag_name_group"))
            .when(tagInsertService).insertNew(eq("北京"), eq(Tag.TagType.system), isNull(), eq("市州"));

        Tag result = tagService.getOrCreateTag("北京", Tag.TagType.system, null, "市州");

        assertThat(result.getId()).isEqualTo(9L);
        verify(tagInsertService).insertNew(eq("北京"), eq(Tag.TagType.system), isNull(), eq("市州"));
    }

    @Test
    @DisplayName("getOrCreateTag — 冲突后重查仍查不到时原样抛出")
    void rethrowsWhenWinnerMissing() {
        when(tagRepo.findFirstByNameAndGroupKeyword("北京", "市州"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.empty());
        doThrow(new DataIntegrityViolationException("dup uk_tag_name_group"))
            .when(tagInsertService).insertNew(eq("北京"), eq(Tag.TagType.system), isNull(), eq("市州"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> tagService.getOrCreateTag("北京", Tag.TagType.system, null, "市州"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}

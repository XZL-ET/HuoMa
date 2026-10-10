package com.bookstore.qrcode.service;

import com.bookstore.qrcode.repository.CustomerTagRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomerTagInsertServiceRewireTest {

    @Mock CustomerTagRepository customerTagRepo;
    @InjectMocks CustomerTagInsertService service;

    @Test
    @DisplayName("目标行不存在时：就地 UPDATE tag_id，返回 true")
    void rewiresInPlace() {
        when(customerTagRepo.existsByCustomerIdAndTagId(1L, 200L)).thenReturn(false);
        when(customerTagRepo.rewireTag(1L, 100L, 200L)).thenReturn(1);

        assertTrue(service.rewire(1L, 100L, 200L));
        verify(customerTagRepo).rewireTag(1L, 100L, 200L);
        verify(customerTagRepo, never()).deleteByCustomerIdAndTagId(any(), any());
    }

    @Test
    @DisplayName("目标行已存在（重复行）时：删源行，返回 true，避免撞 uk_customer_tag")
    void deletesDuplicateWhenTargetExists() {
        when(customerTagRepo.existsByCustomerIdAndTagId(1L, 200L)).thenReturn(true);

        assertTrue(service.rewire(1L, 100L, 200L));
        verify(customerTagRepo).deleteByCustomerIdAndTagId(1L, 100L);
        verify(customerTagRepo, never()).rewireTag(any(), any(), any());
    }

    @Test
    @DisplayName("源行已被删（客户被删）时：不抛异常，返回 false")
    void returnsFalseWhenSourceGone() {
        when(customerTagRepo.existsByCustomerIdAndTagId(1L, 200L)).thenReturn(false);
        when(customerTagRepo.rewireTag(1L, 100L, 200L)).thenReturn(0);

        assertFalse(service.rewire(1L, 100L, 200L));
    }
}

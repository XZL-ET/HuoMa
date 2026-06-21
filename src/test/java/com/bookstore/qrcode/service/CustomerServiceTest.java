package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerService 客户管理")
class CustomerServiceTest {

    @Mock private CustomerRepository customerRepo;
    @Mock private QrCodeRepository qrCodeRepo;
    @Mock private CustomerTagRepository customerTagRepo;
    @Mock private TagRepository tagRepo;
    @Mock private WecomApiClient wecomApiClient;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private MessageGuardService messageGuardService;
    @Mock private EntityManager entityManager;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private CustomerService customerService;

    @Test
    @DisplayName("handleDelete — 存在客户时标记为已删除")
    void shouldMarkCustomerAsDeleted() throws Exception {
        Customer customer = Customer.builder()
                .id(1L).externalUserid("wm-abc123").status(Customer.CustomerStatus.active).build();
        when(customerRepo.findByExternalUserid("wm-abc123")).thenReturn(Optional.of(customer));

        JsonNode event = objectMapper.readTree("""
            {"external_userid":"wm-abc123","userid":"agent1"}
            """);

        customerService.handleDelete(event);

        assertThat(customer.getStatus()).isEqualTo(Customer.CustomerStatus.deleted);
        verify(customerRepo).save(customer);
    }

    @Test
    @DisplayName("handleDelete — 客户不存在时静默处理")
    void shouldSilentlyHandleMissingCustomerOnDelete() throws Exception {
        when(customerRepo.findByExternalUserid("wm-not-exist")).thenReturn(Optional.empty());

        JsonNode event = objectMapper.readTree("""
            {"external_userid":"wm-not-exist","userid":"agent1"}
            """);

        customerService.handleDelete(event);

        verify(customerRepo, never()).save(any());
    }

    @Test
    @DisplayName("getById — 按 ID 查询客户")
    void shouldGetById() {
        Customer customer = Customer.builder().id(1L).name("测试客户").build();
        when(customerRepo.findById(1L)).thenReturn(Optional.of(customer));

        Customer result = customerService.getById(1L);

        assertThat(result.getName()).isEqualTo("测试客户");
    }

    @Test
    @DisplayName("getById — 不存在抛异常")
    void shouldThrowWhenCustomerNotFound() {
        when(customerRepo.findById(999L)).thenReturn(Optional.empty());

        try {
            customerService.getById(999L);
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).contains("客户不存在");
        }
    }
}

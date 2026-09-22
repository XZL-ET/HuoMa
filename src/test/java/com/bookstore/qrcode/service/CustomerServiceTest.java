package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.bookstore.qrcode.wecom.WecomErrorCodes;
import com.bookstore.qrcode.wecom.WecomPermanentException;
import com.bookstore.qrcode.wecom.WecomTransientException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @Mock private CustomerRelationService customerRelationService;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private CustomerService customerService;

    @Test
    @DisplayName("handleDelete — 客户仍归属其他员工时保持 active 并更新归属")
    void shouldKeepActiveAndUpdateAgentWhenFollowUserStillExists() throws Exception {
        Customer customer = Customer.builder()
                .id(1L).externalUserid("wm-abc123")
                .currentAgent("agent1").status(Customer.CustomerStatus.active).build();
        JsonNode detail = objectMapper.readTree("""
            {"errcode":0,"follow_user":[{"userid":"agent2"}]}
            """);
        when(customerRepo.findByExternalUserid("wm-abc123")).thenReturn(Optional.of(customer));
        when(wecomApiClient.getExternalContact("wm-abc123")).thenReturn(detail);

        JsonNode event = objectMapper.readTree("""
            {"external_userid":"wm-abc123","userid":"agent1"}
            """);

        customerService.handleDelete(event);

        assertThat(customer.getStatus()).isEqualTo(Customer.CustomerStatus.active);
        assertThat(customer.getCurrentAgent()).isEqualTo("agent2");
        verify(customerRepo).save(customer);
    }

    @Test
    @DisplayName("handleDelete — 删除事件同时将对应员工关系置 removed")
    void shouldMarkRelationRemovedOnDelete() throws Exception {
        Customer customer = Customer.builder()
                .id(1L).externalUserid("wm-abc123")
                .currentAgent("agent1").status(Customer.CustomerStatus.active).build();
        JsonNode detail = objectMapper.readTree("""
            {"errcode":0,"follow_user":[{"userid":"agent2"}]}
            """);
        when(customerRepo.findByExternalUserid("wm-abc123")).thenReturn(Optional.of(customer));
        when(wecomApiClient.getExternalContact("wm-abc123")).thenReturn(detail);

        JsonNode event = objectMapper.readTree("""
            {"external_userid":"wm-abc123","userid":"agent1"}
            """);

        customerService.handleDelete(event);

        verify(customerRelationService).markRemoved(1L, "agent1");
    }

    @Test
    @DisplayName("handleDelete — follow_user 为空时标记为已删除")
    void shouldMarkDeletedWhenFollowUserEmpty() throws Exception {
        Customer customer = Customer.builder()
                .id(1L).externalUserid("wm-abc123")
                .currentAgent("agent1").status(Customer.CustomerStatus.active).build();
        JsonNode detail = objectMapper.readTree("""
            {"errcode":0,"follow_user":[]}
            """);
        when(customerRepo.findByExternalUserid("wm-abc123")).thenReturn(Optional.of(customer));
        when(wecomApiClient.getExternalContact("wm-abc123")).thenReturn(detail);

        JsonNode event = objectMapper.readTree("""
            {"external_userid":"wm-abc123","userid":"agent1"}
            """);

        customerService.handleDelete(event);

        assertThat(customer.getStatus()).isEqualTo(Customer.CustomerStatus.deleted);
        verify(customerRepo).save(customer);
    }

    @Test
    @DisplayName("handleDelete — 客户关系不存在(84061)时标记为已删除")
    void shouldMarkDeletedWhenNotExternalContact() throws Exception {
        Customer customer = Customer.builder()
                .id(1L).externalUserid("wm-abc123").status(Customer.CustomerStatus.active).build();
        when(customerRepo.findByExternalUserid("wm-abc123")).thenReturn(Optional.of(customer));
        when(wecomApiClient.getExternalContact("wm-abc123"))
                .thenThrow(new WecomPermanentException(WecomErrorCodes.NOT_EXTERNAL_CONTACT,
                        "not external contact", "{}"));

        JsonNode event = objectMapper.readTree("""
            {"external_userid":"wm-abc123","userid":"agent1"}
            """);

        customerService.handleDelete(event);

        assertThat(customer.getStatus()).isEqualTo(Customer.CustomerStatus.deleted);
        verify(customerRepo).save(customer);
    }

    @Test
    @DisplayName("handleDelete — 企微 API 瞬时故障时不删，抛异常触发重试")
    void shouldRethrowTransientWhenApiFails() throws Exception {
        Customer customer = Customer.builder()
                .id(1L).externalUserid("wm-abc123").status(Customer.CustomerStatus.active).build();
        when(customerRepo.findByExternalUserid("wm-abc123")).thenReturn(Optional.of(customer));
        when(wecomApiClient.getExternalContact("wm-abc123"))
                .thenThrow(new WecomTransientException(-1, "timeout", "{}"));

        JsonNode event = objectMapper.readTree("""
            {"external_userid":"wm-abc123","userid":"agent1"}
            """);

        assertThatThrownBy(() -> customerService.handleDelete(event))
                .isInstanceOf(WecomTransientException.class);

        assertThat(customer.getStatus()).isEqualTo(Customer.CustomerStatus.active);
        verify(customerRepo, never()).save(any());
    }

    @Test
    @DisplayName("handleDelete — 企微 API 永久故障(非84061)时不删，抛异常进 DLQ")
    void shouldRethrowPermanentWhenApiFails() throws Exception {
        Customer customer = Customer.builder()
                .id(1L).externalUserid("wm-abc123").status(Customer.CustomerStatus.active).build();
        when(customerRepo.findByExternalUserid("wm-abc123")).thenReturn(Optional.of(customer));
        when(wecomApiClient.getExternalContact("wm-abc123"))
                .thenThrow(new WecomPermanentException(40003, "invalid userid", "{}"));

        JsonNode event = objectMapper.readTree("""
            {"external_userid":"wm-abc123","userid":"agent1"}
            """);

        assertThatThrownBy(() -> customerService.handleDelete(event))
                .isInstanceOf(WecomPermanentException.class);

        assertThat(customer.getStatus()).isEqualTo(Customer.CustomerStatus.active);
        verify(customerRepo, never()).save(any());
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
        verify(wecomApiClient, never()).getExternalContact(any());
    }

    @Test
    @DisplayName("upsertFromCallback — state 为空（内部转移）仍 upsert 关系（无来源）")
    void upsertRelationWhenStateEmpty() {
        Customer existing = Customer.builder()
                .id(1L).externalUserid("wm-1").currentAgent("svcA")
                .status(Customer.CustomerStatus.active).build();
        when(customerRepo.findByExternalUserid("wm-1")).thenReturn(Optional.of(existing));

        customerService.upsertFromCallback("wm-1", "svcA", null);

        verify(customerRelationService).upsertActive(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("svcA"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any(java.time.LocalDateTime.class));
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

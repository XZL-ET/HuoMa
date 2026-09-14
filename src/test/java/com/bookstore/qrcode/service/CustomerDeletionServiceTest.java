package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerDeletionEvent;
import com.bookstore.qrcode.repository.CustomerDeletionEventRepository;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerDeletionService 客户删除事件记录")
class CustomerDeletionServiceTest {

    @Mock private CustomerDeletionEventRepository deletionRepo;
    @Mock private CustomerRepository customerRepo;
    @Mock private WecomApiClient wecomApi;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private CustomerDeletionService deletionService;

    @Test
    @DisplayName("recordCustomerDeletedAgent — 记录客户删员工事件，方向正确")
    void recordCustomerDeletedAgentSavesEvent() throws Exception {
        Customer customer = Customer.builder().id(1L).externalUserid("wm-c1").name("张小明").build();
        when(customerRepo.findByExternalUserid("wm-c1")).thenReturn(Optional.of(customer));

        JsonNode event = objectMapper.readTree("""
            {"external_userid":"wm-c1","userid":"agent1"}
            """);

        deletionService.recordCustomerDeletedAgent(event);

        ArgumentCaptor<CustomerDeletionEvent> captor = ArgumentCaptor.forClass(CustomerDeletionEvent.class);
        verify(deletionRepo).save(captor.capture());
        CustomerDeletionEvent saved = captor.getValue();
        assertThat(saved.getExternalUserid()).isEqualTo("wm-c1");
        assertThat(saved.getUserid()).isEqualTo("agent1");
        assertThat(saved.getDirection()).isEqualTo(CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT);
        assertThat(saved.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("recordCustomerDeletedAgent — 客户名为未知时兜底补名")
    void recordCustomerDeletedAgentBackfillsName() throws Exception {
        Customer customer = Customer.builder().id(1L).externalUserid("wm-c1").name("未知").build();
        when(customerRepo.findByExternalUserid("wm-c1")).thenReturn(Optional.of(customer));
        when(wecomApi.getExternalContact("wm-c1")).thenReturn(objectMapper.readTree("""
            {"external_contact":{"name":"真实昵称"}}
            """));

        JsonNode event = objectMapper.readTree("""
            {"external_userid":"wm-c1","userid":"agent1"}
            """);

        deletionService.recordCustomerDeletedAgent(event);

        verify(customerRepo).save(customer);
        assertThat(customer.getName()).isEqualTo("真实昵称");
        verify(deletionRepo).save(any(CustomerDeletionEvent.class));
    }

    @Test
    @DisplayName("补名失败时不阻断事件记录")
    void backfillFailureDoesNotBlockRecording() throws Exception {
        Customer customer = Customer.builder().id(1L).externalUserid("wm-c1").name("未知").build();
        when(customerRepo.findByExternalUserid("wm-c1")).thenReturn(Optional.of(customer));
        when(wecomApi.getExternalContact("wm-c1")).thenThrow(new RuntimeException("客户关系不存在"));

        JsonNode event = objectMapper.readTree("""
            {"external_userid":"wm-c1","userid":"agent1"}
            """);

        deletionService.recordCustomerDeletedAgent(event);

        verify(deletionRepo).save(any(CustomerDeletionEvent.class));
    }

    @Test
    @DisplayName("recordAgentDeletedCustomer — 记录员工删客户事件，带 source")
    void recordAgentDeletedCustomerSavesEvent() throws Exception {
        JsonNode event = objectMapper.readTree("""
            {"external_userid":"wm-c2","userid":"agent2","source":"DELETE_BY_TRANSFER"}
            """);

        deletionService.recordAgentDeletedCustomer(event);

        ArgumentCaptor<CustomerDeletionEvent> captor = ArgumentCaptor.forClass(CustomerDeletionEvent.class);
        verify(deletionRepo).save(captor.capture());
        CustomerDeletionEvent saved = captor.getValue();
        assertThat(saved.getDirection()).isEqualTo(CustomerDeletionEvent.Direction.AGENT_DELETED_CUSTOMER);
        assertThat(saved.getSource()).isEqualTo("DELETE_BY_TRANSFER");
        assertThat(saved.getUserid()).isEqualTo("agent2");
    }

    @Test
    @DisplayName("缺少关键字段时静默不保存")
    void skipsWhenMissingFields() throws Exception {
        JsonNode event = objectMapper.readTree("""
            {"external_userid":"wm-c3"}
            """);

        deletionService.recordCustomerDeletedAgent(event);

        verify(deletionRepo, never()).save(any());
    }
}

package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerRelation;
import com.bookstore.qrcode.entity.CustomerRelation.RelationStatus;
import com.bookstore.qrcode.repository.CustomerRelationRepository;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.bookstore.qrcode.wecom.WecomTransientException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerSyncService 全量同步")
class CustomerSyncServiceTest {

    @Mock private WecomApiClient wecomApi;
    @Mock private CustomerRepository customerRepo;
    @Mock private CustomerRelationRepository relationRepo;
    @Mock private CustomerRelationService relationService;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private CustomerSyncService syncService;

    @Test
    @DisplayName("单员工 API 失败整体跳过该员工，不误标 removed")
    void skipEntireEmployeeOnApiFailure() throws Exception {
        JsonNode userList = objectMapper.readTree("""
            {"userlist":[{"userid":"emp1","name":"甲","status":1}]}
            """);
        when(wecomApi.getUserList()).thenReturn(userList);
        when(wecomApi.getExternalContactList("emp1"))
                .thenThrow(new WecomTransientException(-1, "timeout", "{}"));

        syncService.syncOnce();

        // 绝不把调用失败当空列表：不 upsert、不 markRemoved
        verify(relationService, never()).upsertActive(any(), any(), any(), any(), any());
        verify(relationService, never()).markRemoved(any(), any());
    }

    @Test
    @DisplayName("员工返回空列表 → 其名下遗留 active 关系置 removed")
    void emptyListReconcilesRemoved() throws Exception {
        JsonNode userList = objectMapper.readTree("""
            {"userlist":[{"userid":"emp1","name":"甲","status":1}]}
            """);
        JsonNode emptyList = objectMapper.readTree("""
            {"errcode":0,"external_userid":[]}
            """);
        when(wecomApi.getUserList()).thenReturn(userList);
        when(wecomApi.getExternalContactList("emp1")).thenReturn(emptyList);
        CustomerRelation stale = CustomerRelation.builder()
                .id(1L).customerId(1L).employeeUserid("emp1").status(RelationStatus.active).build();
        when(relationRepo.findByEmployeeUseridAndStatus("emp1", RelationStatus.active))
                .thenReturn(List.of(stale));

        syncService.syncOnce();

        verify(relationService).markRemoved(1L, "emp1");
    }
}

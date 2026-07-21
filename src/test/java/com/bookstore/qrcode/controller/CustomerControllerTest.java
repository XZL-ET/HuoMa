package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.repository.TagRepository;
import com.bookstore.qrcode.service.AgentRotationService;
import com.bookstore.qrcode.service.CustomerService;
import com.bookstore.qrcode.service.TagService;
import com.bookstore.qrcode.wecom.WecomApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("CustomerController 客户管理")
class CustomerControllerTest {

    private MockMvc mockMvc;
    private CustomerService customerService;

    @BeforeEach
    void setUp() {
        customerService = mock(CustomerService.class);
        CustomerController controller = new CustomerController(
                customerService,
                mock(AgentRotationService.class),
                mock(AgentRepository.class),
                mock(QrCodeRepository.class),
                mock(TagRepository.class),
                mock(TagService.class),
                mock(WecomApiClient.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /customers — 返回客户列表页")
    void shouldReturnCustomerListPage() throws Exception {
        when(customerService.search(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/customers"))
                .andExpect(status().isOk())
                .andExpect(view().name("customer/list"));
    }

    @Test
    @DisplayName("GET /customers/{id} — 返回客户详情页")
    void shouldReturnCustomerDetail() throws Exception {
        Customer customer = Customer.builder()
                .id(1L).externalUserid("wm-abc123").name("测试客户").build();
        when(customerService.getById(1L)).thenReturn(customer);
        when(customerService.getTags(1L)).thenReturn(List.of());

        mockMvc.perform(get("/customers/1"))
                .andExpect(status().isOk())
                .andExpect(view().name("customer/detail"))
                .andExpect(model().attributeExists("customer"));
    }
}
